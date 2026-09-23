package com.billing.license.service;

import com.billing.license.dto.ActivateRequest;
import com.billing.license.dto.ActivateResponse;
import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.entity.LicenseEvent;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 凭证激活统一入口测试（plan-7.0 方案 A / A8 测试矩阵）。
 *
 * <p>覆盖两条分支与全部边界：分流判定、兑换侧整段委托、密钥侧「必须登录 + 归属校验」、
 * 同机幂等（不二次签发）、已绑他机拒绝、非 ACTIVE 拒绝、失败计入暴力猜测风控。
 */
class CredentialBindingServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String LICENSE_KEY = "2F8A-7C31-9D04-B5E6";
    private static final String IP = "203.0.113.7";

    private LicenseRepository licenseRepository;
    private UserRepository userRepository;
    private RedeemCodeService redeemCodeService;
    private LicenseService licenseService;
    private RateLimitService rateLimitService;
    private CredentialBindingService service;

    @BeforeEach
    void setUp() {
        licenseRepository = mock(LicenseRepository.class);
        userRepository = mock(UserRepository.class);
        redeemCodeService = mock(RedeemCodeService.class);
        licenseService = mock(LicenseService.class);
        rateLimitService = mock(RateLimitService.class);
        service = new CredentialBindingService(
            licenseRepository, userRepository, redeemCodeService, licenseService, rateLimitService);
    }

    private ActivateRequest request(String credential, String machineId) {
        ActivateRequest req = ActivateRequest.builder()
            .credential(credential).machineId(machineId).build();
        req.setClientIp(IP);
        return req;
    }

    private License license(UUID ownerId, License.LicenseStatus status, String boundMachine) {
        return License.builder()
            .licenseKey(LICENSE_KEY)
            .customerId(ownerId)
            .status(status)
            .machineCode(boundMachine)
            .signedToken("old.sig.token")
            .build();
    }

    private void stubLicense(License license) {
        when(licenseRepository.findByLicenseKey(LICENSE_KEY)).thenReturn(Optional.of(license));
    }

    // ---------- 分流与入参校验 ----------

    @Test
    void activate_rejects_whenCredentialBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request("   ", "M1"), OWNER_ID));
        assertEquals("CREDENTIAL_REQUIRED", ex.getErrorCode());
    }

    @Test
    void activate_rejects_whenMachineIdBlank() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, null), OWNER_ID));
        assertEquals("MACHINE_ID_REQUIRED", ex.getErrorCode());
    }

    // ---------- 兑换码分支 ----------

    @Test
    void activate_delegatesToRedeemService_whenCredentialIsRedeemCode() {
        License issued = license(OWNER_ID, License.LicenseStatus.ACTIVE, "M1");
        when(redeemCodeService.redeemCode(any(RedeemCodeRequest.class))).thenReturn(issued);

        ActivateResponse resp = service.activate(request("rc-8f3c-1d2e-9a4b", "M1"), OWNER_ID);

        assertEquals(LICENSE_KEY, resp.getLicenseKey());
        assertEquals("M1", resp.getMachineId());
        assertTrue(resp.isSuccess());
        assertNotNull(resp.getServerTime(), "serverTime 必须回填（防系统时间回拨）");

        // 兑换侧整段委托：只传一次，且 clientIp 必须透传给其内部风控
        ArgumentCaptor<RedeemCodeRequest> captor = ArgumentCaptor.forClass(RedeemCodeRequest.class);
        verify(redeemCodeService).redeemCode(captor.capture());
        RedeemCodeRequest forwarded = captor.getValue();
        assertEquals("rc-8f3c-1d2e-9a4b", forwarded.getCode());
        assertEquals("M1", forwarded.getMachineId());
        assertEquals(IP, forwarded.getClientIp());
        // 密钥分支的内核/归属校验不得被触碰
        verifyNoInteractions(licenseRepository);
        verify(licenseService, never()).bindToMachine(any(), any(), any());
    }

    @Test
    void activate_redeemBranch_usesLoggedInEmail_overRequestBodyEmail() {
        when(userRepository.findById(OWNER_ID))
            .thenReturn(Optional.of(User.builder().email("owner@example.com").build()));
        when(redeemCodeService.redeemCode(any(RedeemCodeRequest.class)))
            .thenReturn(license(OWNER_ID, License.LicenseStatus.ACTIVE, "M1"));

        // 请求体里塞一个别人的邮箱，登录态必须覆盖它
        ActivateRequest req = request("RC-AAAA-BBBB", "M1");
        req.setCustomerEmail("victim@example.com");
        service.activate(req, OWNER_ID);

        ArgumentCaptor<RedeemCodeRequest> captor = ArgumentCaptor.forClass(RedeemCodeRequest.class);
        verify(redeemCodeService).redeemCode(captor.capture());
        assertEquals("owner@example.com", captor.getValue().getCustomerEmail());
    }

    @Test
    void activate_redeemBranch_fallsBackToRequestBodyEmail_whenAnonymous() {
        when(redeemCodeService.redeemCode(any(RedeemCodeRequest.class)))
            .thenReturn(license(OWNER_ID, License.LicenseStatus.ACTIVE, "M1"));

        ActivateRequest req = request("RC-AAAA-BBBB", "M1");
        req.setCustomerEmail("gift@example.com");
        service.activate(req, null);

        ArgumentCaptor<RedeemCodeRequest> captor = ArgumentCaptor.forClass(RedeemCodeRequest.class);
        verify(redeemCodeService).redeemCode(captor.capture());
        assertEquals("gift@example.com", captor.getValue().getCustomerEmail());
        verifyNoInteractions(userRepository);
    }

    // ---------- 密钥分支：安全红线 ----------

    @Test
    void activate_licenseKeyBranch_requiresLogin() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "M1"), null));

        assertEquals("LOGIN_REQUIRED", ex.getErrorCode());
        // 未登录时连密钥都不该去查库（不给匿名探测存在性的机会）
        verifyNoInteractions(licenseRepository);
    }

    @Test
    void activate_licenseKeyBranch_rejects_whenNotOwner_withSameErrorAsNotFound() {
        stubLicense(license(OTHER_ID, License.LicenseStatus.ACTIVE, null));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "M1"), OWNER_ID));

        // 越权与不存在同码返回，不泄露他人 License 存在性；B4 起与兑换码分支统一为 CREDENTIAL_NOT_FOUND
        assertEquals("CREDENTIAL_NOT_FOUND", ex.getErrorCode());
        verify(licenseService, never()).bindToMachine(any(), any(), any());
    }

    @Test
    void activate_licenseKeyBranch_rejects_whenNotActive() {
        stubLicense(license(OWNER_ID, License.LicenseStatus.REVOKED, null));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "M1"), OWNER_ID));

        assertEquals("LICENSE_NOT_ACTIVE", ex.getErrorCode());
        verify(licenseService, never()).bindToMachine(any(), any(), any());
    }

    // ---------- 密钥分支：幂等与换机 ----------

    @Test
    void activate_licenseKeyBranch_isIdempotent_whenSameMachine() {
        stubLicense(license(OWNER_ID, License.LicenseStatus.ACTIVE, "M1"));

        ActivateResponse resp = service.activate(request(LICENSE_KEY, "M1"), OWNER_ID);

        // 同机重试返回既有令牌，绝不二次签发
        assertEquals("old.sig.token", resp.getSignedToken());
        verify(licenseService, never()).bindToMachine(any(), any(), any());
        verify(licenseService, never()).recordLicenseEvent(any(), any(), any(), any());
    }

    @Test
    void activate_licenseKeyBranch_rejects_whenBoundToAnotherMachine() {
        stubLicense(license(OWNER_ID, License.LicenseStatus.ACTIVE, "OLD-MACHINE"));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "NEW-MACHINE"), OWNER_ID));

        // 决策 B6：不自动改绑，引导用户到账号页手动解绑（恢复路径见 unbindByOwner）
        assertEquals("MACHINE_MISMATCH", ex.getErrorCode());
        verify(licenseService, never()).bindToMachine(any(), any(), any());
    }

    @Test
    void activate_licenseKeyBranch_bindsAndRecordsActivatedEvent_whenUnbound() {
        License license = license(OWNER_ID, License.LicenseStatus.ACTIVE, null);
        stubLicense(license);

        ActivateResponse resp = service.activate(request(LICENSE_KEY, "M1"), OWNER_ID);

        verify(licenseService).bindToMachine(license, "M1", MachineRegistryService.SRC_ACTIVATE);
        verify(licenseService).recordLicenseEvent(license, LicenseEvent.EventType.ACTIVATED, "M1",
            "Activated by license key");
        assertTrue(resp.isSuccess());
    }

    // ---------- 风控 ----------

    @Test
    void activate_licenseKeyBranch_returnsLimitError_whenIpFlooded() {
        when(rateLimitService.checkRedeemIp(IP))
            .thenThrow(new RateLimitService.RateLimitExceededException("redeem-ip", IP, 21, 20));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "M1"), OWNER_ID));

        assertEquals("REDEEM_IP_LIMIT", ex.getErrorCode());
        verifyNoInteractions(licenseRepository);
    }

    @Test
    void activate_licenseKeyBranch_countsFailure_whenCredentialInvalid() {
        when(licenseRepository.findByLicenseKey(LICENSE_KEY)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "M1"), OWNER_ID));

        assertEquals("CREDENTIAL_NOT_FOUND", ex.getErrorCode());
        // 与兑换侧同口径：失败计入暴力猜测风控
        verify(rateLimitService).recordRedeemFailure(IP);
    }

    @Test
    void activate_licenseKeyBranch_bruteForceLock_overridesOriginalError() {
        when(licenseRepository.findByLicenseKey(LICENSE_KEY)).thenReturn(Optional.empty());
        when(rateLimitService.recordRedeemFailure(IP))
            .thenThrow(new RateLimitService.RateLimitExceededException("redeem-fail", IP, 11, 10));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(request(LICENSE_KEY, "M1"), OWNER_ID));

        // 达到失败上限时以「已锁定」为准，与 RedeemCodeService.redeemCode 完全一致
        assertEquals("REDEEM_BRUTE_FORCE", ex.getErrorCode());
    }

    @Test
    void activate_skipsIpRiskControl_whenIpUnresolvable() {
        stubLicense(license(OWNER_ID, License.LicenseStatus.ACTIVE, null));
        ActivateRequest req = ActivateRequest.builder()
            .credential(LICENSE_KEY).machineId("M1").build();
        req.setClientIp(null);

        service.activate(req, OWNER_ID);

        verify(rateLimitService, never()).checkRedeemIp(any());
        verify(licenseService).bindToMachine(any(), eq("M1"), any());
    }
}
