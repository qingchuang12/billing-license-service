package com.billing.license.service;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.entity.Product;
import com.billing.license.entity.RedeemCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RedeemCodeService 兑换流程「客户标识邮箱化」测试（plan-3.0 §三 E4 / E5）。
 *
 * <p>覆盖：未注册邮箱兑换 → 自动建访客账户且 License 挂到该 userId；
 * 已注册邮箱 → 复用同一 userId，不重复建号。
 *
 * <p>plan-7.0 / A1 后签发已收敛到 {@link LicenseService#bindToMachine} 内核，
 * 本测试以 mock 内核 + Answer 模拟绑定与签名，并校验调用方传入的机器码与来源标记。
 */
class RedeemCodeServiceTest {

    private RedeemCodeRepository redeemCodeRepository;
    private LicenseRepository licenseRepository;
    private ProductRepository productRepository;
    private OrderRepository orderRepository;
    private RateLimitService rateLimitService;
    private CustomerIdentityService customerIdentityService;
    private LicenseService licenseService;
    private RedeemCodeService redeemCodeService;

    @BeforeEach
    void setUp() {
        redeemCodeRepository = mock(RedeemCodeRepository.class);
        licenseRepository = mock(LicenseRepository.class);
        productRepository = mock(ProductRepository.class);
        orderRepository = mock(OrderRepository.class);
        rateLimitService = mock(RateLimitService.class);
        customerIdentityService = mock(CustomerIdentityService.class);
        licenseService = mock(LicenseService.class);
        redeemCodeService = new RedeemCodeService(
            redeemCodeRepository, licenseRepository, productRepository,
            orderRepository, rateLimitService, customerIdentityService, licenseService);
    }

    /**
     * 桩一个可用的未使用兑换码及其签发/落库路径。
     *
     * <p>内核以 Answer 模拟：把机器码写回 License 并赋 signature token——
     * 与真实 {@code bindToMachine} 的「先写 machineCode 再签名」顺序一致，
     * 使「兑换后 License 已绑机」这一断言仍有意义（而非被 mock 吞掉）。
     */
    private void stubUnusedCode(String code) {
        RedeemCode redeemCode = RedeemCode.builder()
            .code(code)
            .status(RedeemCode.RedeemCodeStatus.UNUSED)
            .product(Product.builder().sku("pro").licenseDurationDays(365).build())
            .maxUses(1).currentUses(0)
            .build();
        when(redeemCodeRepository.findByCode(code)).thenReturn(Optional.of(redeemCode));
        when(licenseService.bindToMachine(any(License.class), any(), any())).thenAnswer(i -> {
            License l = i.getArgument(0);
            String machineCode = i.getArgument(1);
            if (machineCode != null && !machineCode.isBlank()) {
                l.setMachineCode(machineCode);
            }
            l.setSignedToken("sig.token.value");
            return l;
        });
        when(redeemCodeRepository.save(any(RedeemCode.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void redeemCode_shouldBindLicenseToGuestUserId_whenEmailUnregistered() {
        stubUnusedCode("CODE-GUEST");
        UUID guestId = UUID.randomUUID();
        when(customerIdentityService.resolveOrCreate("guest@example.com")).thenReturn(guestId);

        RedeemCodeRequest req = RedeemCodeRequest.builder()
            .code("CODE-GUEST").customerEmail("guest@example.com").machineId("M1").build();
        License license = redeemCodeService.redeemCode(req);

        // 未注册邮箱自动建访客账户：License 挂到该 userId
        assertEquals(guestId, license.getCustomerId());
        assertEquals("M1", license.getMachineCode());
        verify(customerIdentityService).resolveOrCreate("guest@example.com");
        // A1：签发已收敛到内核，校验兑换侧确实把「本次机器码 + REDEEM 来源」交给内核
        verify(licenseService).bindToMachine(license, "M1", MachineRegistryService.SRC_REDEEM);
    }

    @Test
    void redeemCode_shouldReuseExistingUserId_whenEmailRegistered() {
        stubUnusedCode("CODE-MEMBER");
        UUID memberId = UUID.randomUUID();
        when(customerIdentityService.resolveOrCreate("member@example.com")).thenReturn(memberId);

        RedeemCodeRequest req = RedeemCodeRequest.builder()
            .code("CODE-MEMBER").customerEmail("member@example.com").build();
        License license = redeemCodeService.redeemCode(req);

        // 已注册邮箱：复用既有 userId，不重复建号
        assertEquals(memberId, license.getCustomerId());
    }

    /**
     * B4（2026-09-23）：未知凭证统一返回 CREDENTIAL_NOT_FOUND。
     *
     * <p>本方法被两个端点共用——{@code POST /api/redeem/redeem} 与 {@code POST /api/licenses/activate}
     * （后者的兑换码分支整段委托本服务）——故这条断言同时锁住「两端点对同一枚未知码返回同一个码」。
     * 若日后有人在 {@code CredentialBindingService} 里加「异常码翻译层」，本用例会失败。
     */
    @Test
    void redeemCode_shouldReturnCredentialNotFound_whenCodeUnknown() {
        when(redeemCodeRepository.findByCode("RC-UNKNOWN")).thenReturn(Optional.empty());

        RedeemCodeRequest req = RedeemCodeRequest.builder()
            .code("RC-UNKNOWN").customerEmail("guest@example.com").build();
        BusinessException ex = assertThrows(BusinessException.class,
            () -> redeemCodeService.redeemCode(req));

        assertEquals("CREDENTIAL_NOT_FOUND", ex.getErrorCode());
        verify(licenseService, never()).bindToMachine(any(), any(), any());
    }
}
