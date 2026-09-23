package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ActivateResponse;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.infrastructure.kms.KmsService;
import com.billing.license.repository.LicenseEventRepository;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.notification.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * LicenseService 单元测试 - 覆盖签发、事件记录、换机重发、作废、校验失败（外围接口实现）
 * 使用内存 Ed25519 KmsService 桩 + Mockito 仓储，不连接数据库。
 */
class LicenseServiceTest {

    private OrderRepository orderRepository;
    private LicenseRepository licenseRepository;
    private LicenseEventRepository licenseEventRepository;
    private LicenseIssuer licenseIssuer;
    private UserRepository userRepository;
    private MachineRegistryService machineRegistryService;
    private LicenseService licenseService;

    private UUID customerId;
    private UUID productId;
    private UUID orderId;
    private String orderNumber;

    @BeforeEach
    void setUp() throws Exception {
        orderRepository = mock(OrderRepository.class);
        licenseRepository = mock(LicenseRepository.class);
        licenseEventRepository = mock(LicenseEventRepository.class);
        BillingProperties billingProperties = new BillingProperties();
        billingProperties.setDefaultLicenseDurationDays(365);
        EmailNotificationService email = mock(EmailNotificationService.class);

        // 内存 Ed25519 KMS 桩
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        KmsService kms = new KmsService() {
            public byte[] sign(byte[] data) {
                try {
                    Signature s = Signature.getInstance("Ed25519");
                    s.initSign(kp.getPrivate());
                    s.update(data);
                    return s.sign();
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            public boolean verify(byte[] data, byte[] sig) {
                try {
                    Signature s = Signature.getInstance("Ed25519");
                    s.initVerify(kp.getPublic());
                    s.update(data);
                    return s.verify(sig);
                } catch (Exception e) { return false; }
            }
            public byte[] getPublicKey() { return kp.getPublic().getEncoded(); }
            public String getAlgorithm() { return "Ed25519"; }
        };
        licenseIssuer = new LicenseIssuer(kms);

        userRepository = mock(UserRepository.class);
        machineRegistryService = mock(MachineRegistryService.class);
        licenseService = new LicenseService(
            licenseRepository, orderRepository, licenseEventRepository,
            licenseIssuer, billingProperties, email,
            mock(com.billing.license.service.risk.RateLimitService.class),
            userRepository,
            machineRegistryService);

        customerId = UUID.randomUUID();
        productId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        orderNumber = "ORD-TEST-1";
    }

    private Order paidOrder(String machineCode) {
        Product product = Product.builder()
            .id(productId).sku("pro").name("Pro").licenseDurationDays(365).build();
        OrderItem item = OrderItem.builder()
            .order(null).product(product).quantity(1)
            .unitPrice(BigDecimal.valueOf(99)).totalPrice(BigDecimal.valueOf(99)).build();
        Order order = Order.builder()
            .id(orderId).orderNumber(orderNumber).customerId(customerId)
            .paymentStatus(Order.PaymentStatus.PAID).machineCode(machineCode).build();
        order.setOrderItems(List.of(item));
        return order;
    }

    @Test
    void issueLicense_shouldIssueAndRecordIssuedEvent() {
        Order order = paidOrder("MACHINE-1");
        when(orderRepository.findByOrderNumber(orderNumber)).thenReturn(Optional.of(order));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        License license = licenseService.issueLicense(orderNumber, "MACHINE-1");

        assertNotNull(license.getSignedToken());
        assertTrue(license.getSignedToken().contains("."));
        verify(licenseRepository).save(any(License.class));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.ISSUED
            && "MACHINE-1".equals(e.getMachineId())));
    }

    @Test
    void issueLicense_shouldThrow_whenOrderNotPaid() {
        Order order = paidOrder("MACHINE-1");
        order.setPaymentStatus(Order.PaymentStatus.UNPAID);
        when(orderRepository.findByOrderNumber(orderNumber)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class,
            () -> licenseService.issueLicense(orderNumber, "MACHINE-1"));
    }

    @Test
    void verifyLicense_shouldRecordVerifyFailed_whenInactive() {
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-1").customerId(customerId)
            .status(License.LicenseStatus.REVOKED).machineCode("M1").build();
        when(licenseRepository.findByLicenseKey("LIC-1")).thenReturn(Optional.of(license));

        assertThrows(BusinessException.class, () -> licenseService.verifyLicense("LIC-1"));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.VERIFY_FAILED));
    }

    @Test
    void revokeLicense_shouldMarkRevokedAndRecordEventWithReason() {
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-2").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).machineCode("M1").build();
        when(licenseRepository.findByLicenseKey("LIC-2")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.revokeLicense("LIC-2", "violation");

        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
        assertNotNull(license.getRevokedAt());
        // D6：作废必须写 license_events 留痕，且 detail 带上操作原因与作废前机器码
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.REVOKED
                && "M1".equals(e.getMachineId())
                && e.getDetail() != null && e.getDetail().contains("violation")));
    }

    @Test
    void revokeLicense_shouldThrow_whenLicenseNotFound() {
        when(licenseRepository.findByLicenseKey("NOPE")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> licenseService.revokeLicense("NOPE", "x"));
        verify(licenseEventRepository, never()).save(any(LicenseEvent.class));
    }

    /** 构造一条 ACTIVE 且已签名（签名有效）的 License */
    private License signedActiveLicense(String key) {
        Product product = Product.builder()
            .id(productId).sku("pro").name("Pro").licenseDurationDays(365).build();
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey(key).customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).product(product)
            .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(30))
            .machineCode("M1").build();
        license.setSignedToken(licenseIssuer.issueLicense(license));
        return license;
    }

    // ---------------- A1：服务端校验补验签 ----------------

    @Test
    void verifyLicense_shouldPass_whenSignatureValid() {
        License license = signedActiveLicense("LIC-OK");
        when(licenseRepository.findByLicenseKey("LIC-OK")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        LicenseResponse response = licenseService.verifyLicense("LIC-OK");

        assertEquals("LIC-OK", response.getLicenseKey());
        // E3：verify 为公开端点，客户邮箱不回显（置 null），避免向持 licenseKey 者泄露归属邮箱
        assertNull(response.getCustomerEmail());
        assertNotNull(license.getLastVerifiedAt());
        verify(licenseEventRepository, never()).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.VERIFY_FAILED));
    }

    @Test
    void verifyLicense_shouldThrowAndRecordVerifyFailed_whenTokenTampered() {
        License license = signedActiveLicense("LIC-TAMPERED");
        // 篡改 payload 段（不碰签名段）：signingInput 变化 → 验签必然失败。
        // 注意：不能只翻转签名末字符——base64 末字符含 2 个无效位，翻转后解码字节可能不变（测试会偶发通过）。
        String[] parts = license.getSignedToken().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"lic\":\"FORGED\"}".getBytes(StandardCharsets.UTF_8));
        license.setSignedToken(parts[0] + "." + forgedPayload + "." + parts[2]);
        when(licenseRepository.findByLicenseKey("LIC-TAMPERED")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> licenseService.verifyLicense("LIC-TAMPERED"));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.VERIFY_FAILED));
    }

    @Test
    void verifyLicense_shouldThrowAndRecordVerifyFailed_whenSignedTokenMissing() {
        License license = signedActiveLicense("LIC-NOTOKEN");
        license.setSignedToken(null);
        when(licenseRepository.findByLicenseKey("LIC-NOTOKEN")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> licenseService.verifyLicense("LIC-NOTOKEN"));

        License blankTokenLicense = signedActiveLicense("LIC-BLANKTOKEN");
        blankTokenLicense.setSignedToken("   ");
        when(licenseRepository.findByLicenseKey("LIC-BLANKTOKEN")).thenReturn(Optional.of(blankTokenLicense));

        assertThrows(BusinessException.class, () -> licenseService.verifyLicense("LIC-BLANKTOKEN"));
        verify(licenseEventRepository, times(2)).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.VERIFY_FAILED));
    }

    @Test
    void verifyLicense_shouldNotRefreshLastVerifiedAt_whenVerificationFails() {
        // K7：lastVerifiedAt 语义 = 最后一次**成功**校验；失败路径不得刷新该字段（旧实现先写后判）
        License license = signedActiveLicense("LIC-K7");
        license.setSignedToken("   ");
        license.setLastVerifiedAt(null);
        when(licenseRepository.findByLicenseKey("LIC-K7")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> licenseService.verifyLicense("LIC-K7"));

        assertNull(license.getLastVerifiedAt(), "校验失败不应刷新 lastVerifiedAt");
        verify(licenseRepository, never()).save(any(License.class));
    }

    // ---------------- A2：批量签发绑 machineCode ----------------
    @Test
    void issueLicensesForOrder_shouldBindMachineCode_whenOrderHasMachineCode() {
        Order order = paidOrder("MACHINE-BATCH");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(licenseRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        List<LicenseResponse> responses = licenseService.issueLicensesForOrder(orderId);

        assertEquals(1, responses.size());
        Map<String, Object> payload = licenseIssuer.decodePayload(responses.get(0).getSignedToken());
        assertEquals("MACHINE-BATCH", payload.get("mid"));
    }

    @Test
    void issueLicensesForOrder_shouldStillIssueWithoutMid_whenOrderHasNoMachineCode() {
        Order order = paidOrder(null);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(licenseRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        List<LicenseResponse> responses = licenseService.issueLicensesForOrder(orderId);

        assertEquals(1, responses.size(), "无机器码仍应照常签发 License");
        String token = responses.get(0).getSignedToken();
        assertNotNull(token);
        Map<String, Object> payload = licenseIssuer.decodePayload(token);
        assertFalse(payload.containsKey("mid"));
    }

    @Test
    void reissueLicense_shouldMarkOriginalReissuedAndRecordEvents() {
        Product product = Product.builder().id(productId).sku("pro").licenseDurationDays(365).build();
        Order order = paidOrder("MACHINE-OLD");
        License original = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-OLD").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).product(product).order(order)
            .machineCode("MACHINE-OLD").build();
        when(licenseRepository.findByLicenseKey("LIC-OLD")).thenReturn(Optional.of(original));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.reissueLicense("LIC-OLD", "MACHINE-NEW", "changed_pc", false);

        // 原 License 被标记 REISSUED（D3：换机失效 ≠ 退款吊销，且不写 revokedAt）
        assertEquals(License.LicenseStatus.REISSUED, original.getStatus());
        assertNull(original.getRevokedAt());
        // 应记录至少 REISSUED + 新 LICENSE ISSUED 两条事件
        verify(licenseEventRepository, atLeast(2)).save(any(LicenseEvent.class));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.REISSUED));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.ISSUED
            && "MACHINE-NEW".equals(e.getMachineId())));
    }

    // ---------------- 主体三：重发次数上限与管理端豁免 ----------------

    /** 构造 N 条 REISSUED 事件，用于把某 License 顶到重发上限 */
    private List<LicenseEvent> reissuedEvents(String licenseKey, int count) {
        List<LicenseEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(LicenseEvent.builder()
                .licenseKey(licenseKey).eventType(LicenseEvent.EventType.REISSUED)
                .machineId("M" + i).detail("reissue #" + i).build());
        }
        return events;
    }

    @Test
    void reissueLicense_shouldReject_whenOverLimitAndNotForced() {
        License original = signedActiveLicense("LIC-LIMIT");
        when(licenseRepository.findByLicenseKey("LIC-LIMIT")).thenReturn(Optional.of(original));
        when(licenseEventRepository.findByLicenseKey("LIC-LIMIT"))
            .thenReturn(reissuedEvents("LIC-LIMIT", 5));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.reissueLicense("LIC-LIMIT", null, "over", false));

        assertEquals("LICENSE_REISSUE_LIMIT", ex.getErrorCode());
        // 未放行：原证不得被置 REISSUED，也不得签发新证
        assertEquals(License.LicenseStatus.ACTIVE, original.getStatus());
        verify(licenseRepository, never()).save(any(License.class));
    }

    @Test
    void reissueLicense_shouldPassAndMarkForced_whenOverLimitButForced() {
        License original = signedActiveLicense("LIC-FORCE");
        when(licenseRepository.findByLicenseKey("LIC-FORCE")).thenReturn(Optional.of(original));
        when(licenseEventRepository.findByLicenseKey("LIC-FORCE"))
            .thenReturn(reissuedEvents("LIC-FORCE", 5));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.reissueLicense("LIC-FORCE", null, "customer service", true);

        assertEquals(License.LicenseStatus.REISSUED, original.getStatus());
        // 人工越限必须在事件 detail 留痕，否则审计看不出这是豁免操作（与常规重发无法区分）
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.REISSUED
            && e.getDetail() != null && e.getDetail().contains("forced over limit=5")));
    }

    @Test
    void reissueLicense_shouldNotMarkForced_whenUnderLimit() {
        License original = signedActiveLicense("LIC-UNDER");
        when(licenseRepository.findByLicenseKey("LIC-UNDER")).thenReturn(Optional.of(original));
        when(licenseEventRepository.findByLicenseKey("LIC-UNDER"))
            .thenReturn(reissuedEvents("LIC-UNDER", 1));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.reissueLicense("LIC-UNDER", "MACHINE-NEW", "normal", false);

        // 未越限时不得打 forced 标记，否则「豁免」这一信号在审计里被稀释
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.REISSUED
            && (e.getDetail() == null || !e.getDetail().contains("forced"))));
    }

    // ---------------- 主体三：管理端解绑 ----------------

    @Test
    void unbindByAdmin_shouldClearMachineCodeAndRecordUnboundEvent() {
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-ADMIN-UNBIND").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).machineCode("MACHINE-X").build();
        when(licenseRepository.findByLicenseKey("LIC-ADMIN-UNBIND")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        String previous = licenseService.unbindByAdmin("LIC-ADMIN-UNBIND", "user changed PC");

        assertEquals("MACHINE-X", previous);
        assertNull(license.getMachineCode());
        // 只清设备绑定：授权状态与归属都不动（与「作废」的本质区别）
        assertEquals(License.LicenseStatus.ACTIVE, license.getStatus());
        assertEquals(customerId, license.getCustomerId());
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.UNBOUND
            && "MACHINE-X".equals(e.getMachineId())
            && e.getDetail() != null && e.getDetail().contains("admin")
            && e.getDetail().contains("user changed PC")));
    }

    @Test
    void unbindByAdmin_shouldBeIdempotent_whenNotBound() {
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-NOT-BOUND").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).machineCode(null).build();
        when(licenseRepository.findByLicenseKey("LIC-NOT-BOUND")).thenReturn(Optional.of(license));

        assertNull(licenseService.unbindByAdmin("LIC-NOT-BOUND", "noop"));

        // 本就未绑定：幂等返回，不落库、不重复留痕
        verify(licenseRepository, never()).save(any(License.class));
        verify(licenseEventRepository, never()).save(any(LicenseEvent.class));
    }

    @Test
    void unbindByAdmin_shouldReject_whenLicenseRevoked() {
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-REVOKED-UNBIND").customerId(customerId)
            .status(License.LicenseStatus.REVOKED).machineCode("MACHINE-X").build();
        when(licenseRepository.findByLicenseKey("LIC-REVOKED-UNBIND")).thenReturn(Optional.of(license));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.unbindByAdmin("LIC-REVOKED-UNBIND", "n/a"));

        // 与账号侧 unbindByOwner 逐字同码，避免同一语义在两个入口分叉
        assertEquals("LICENSE_REVOKED", ex.getErrorCode());
        verify(licenseRepository, never()).save(any(License.class));
    }

    @Test
    void unbindByAdmin_shouldThrowNotFound_whenUnknown() {
        when(licenseRepository.findByLicenseKey("LIC-MISSING")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.unbindByAdmin("LIC-MISSING", "n/a"));

        assertEquals("LICENSE_NOT_FOUND", ex.getErrorCode());
    }

    // ---------------- plan-7.0 决策 B6：账号页自助解绑（换机恢复路径） ----------------

    @Test
    void unbindByOwner_shouldReleaseBindingAndRecordEvent_whenOwnerMatches() {
        License license = signedActiveLicense("LIC-OWNER-OK");
        when(licenseRepository.findByLicenseKey("LIC-OWNER-OK")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        String previous = licenseService.unbindByOwner("LIC-OWNER-OK", customerId);

        assertEquals("M1", previous, "应返回解绑前的机器码");
        assertNull(license.getMachineCode(), "只清设备绑定，不吊销授权");
        assertEquals(License.LicenseStatus.ACTIVE, license.getStatus());
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.UNBOUND && "M1".equals(e.getMachineId())));
    }

    @Test
    void unbindByOwner_shouldThrowNotFound_whenNotOwner() {
        License license = signedActiveLicense("LIC-OWNER-OTHER");
        when(licenseRepository.findByLicenseKey("LIC-OWNER-OTHER")).thenReturn(Optional.of(license));

        // 越权与不存在同码返回，不泄露他人 License 存在性
        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.unbindByOwner("LIC-OWNER-OTHER", UUID.randomUUID()));
        assertEquals("LICENSE_NOT_FOUND", ex.getErrorCode());
        verify(licenseRepository, never()).save(any(License.class));
        verify(licenseEventRepository, never()).save(any(LicenseEvent.class));
    }

    @Test
    void unbindByOwner_shouldBeIdempotent_whenNotBound() {
        License license = signedActiveLicense("LIC-OWNER-UNBOUND");
        license.setMachineCode(null);
        when(licenseRepository.findByLicenseKey("LIC-OWNER-UNBOUND")).thenReturn(Optional.of(license));

        assertNull(licenseService.unbindByOwner("LIC-OWNER-UNBOUND", customerId));
        // 本就未绑定：不重复落库、不重复留痕
        verify(licenseRepository, never()).save(any(License.class));
        verify(licenseEventRepository, never()).save(any(LicenseEvent.class));
    }

    @Test
    void unbindByOwner_shouldThrow_whenLicenseRevoked() {
        License license = signedActiveLicense("LIC-OWNER-REV");
        license.setStatus(License.LicenseStatus.REVOKED);
        when(licenseRepository.findByLicenseKey("LIC-OWNER-REV")).thenReturn(Optional.of(license));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.unbindByOwner("LIC-OWNER-REV", customerId));
        assertEquals("LICENSE_REVOKED", ex.getErrorCode());
        verify(licenseRepository, never()).save(any(License.class));
    }

    // ---------------- plan-7.0 / A1：绑定内核 ----------------

    @Test
    void bindToMachine_shouldSetMachineCodeBeforeSigning_andTouchRegistry() {
        License license = License.builder()
            .licenseKey("LIC-KERNEL").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(30))
            .build();
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.bindToMachine(license, "NEW-MACHINE", MachineRegistryService.SRC_ACTIVATE);

        assertEquals("NEW-MACHINE", license.getMachineCode());
        assertNotNull(license.getSignedToken(), "内核必须完成签发");
        // 顺序要紧（真实机械验证）：token 载荷里的 mid 必须已是本次机器码，
        // 否则说明是「先签名后写库」，客户端验签拿到的会是旧绑定
        assertEquals("NEW-MACHINE", licenseIssuer.decodePayload(license.getSignedToken()).get("mid"),
            "签名必须发生在 machineCode 落定之后");
        verify(licenseRepository).save(license);
        verify(machineRegistryService).touch("NEW-MACHINE", MachineRegistryService.SRC_ACTIVATE);
    }

    @Test
    void bindToMachine_shouldSkipBinding_whenMachineCodeBlank() {
        License license = License.builder()
            .licenseKey("LIC-KERNEL-BLANK").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(30))
            .build();
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.bindToMachine(license, "   ", MachineRegistryService.SRC_PURCHASE);

        assertNull(license.getMachineCode(), "空机器码表示签发「不绑定设备」的 License");
        assertNotNull(license.getSignedToken(), "仍须签发 token（客户端首次启动再激活）");
        verify(machineRegistryService, never()).touch(any(), any());
        verify(machineRegistryService, never()).markConverted(any(), any());
    }

    /** B7 = B（plan-7.0 / D3）：完成正式绑定即「已转正」——内核统一落点，四条绑定路径全覆盖 */
    @Test
    void bindToMachine_shouldMarkMachineConverted() {
        License license = License.builder()
            .licenseKey("LIC-KERNEL").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(30))
            .build();
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.bindToMachine(license, "NEW-MACHINE", MachineRegistryService.SRC_ACTIVATE);

        verify(machineRegistryService).markConverted("NEW-MACHINE", MachineRegistryService.SRC_ACTIVATE);
    }

    // ---------------- plan-7.0 / D2：客户端自动上报绑定 ----------------

    /** 构造一条「未绑定」的 ACTIVE License，并签发 token（模拟兑换码激活后客户端持有的状态） */
    private License unboundSignedLicense(String key) {
        Product product = Product.builder()
            .id(productId).sku("pro").name("Pro").licenseDurationDays(365).build();
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey(key).customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).product(product)
            .issuedAt(LocalDateTime.now()).expiresAt(LocalDateTime.now().plusDays(30))
            .build();
        license.setSignedToken(licenseIssuer.issueLicense(license));
        return license;
    }

    @Test
    void reportBinding_shouldBindAndRecordEvent_whenUnboundAndTokenValid() {
        License license = unboundSignedLicense("LIC-REPORT");
        String token = license.getSignedToken();
        when(licenseRepository.findByLicenseKey("LIC-REPORT")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        ActivateResponse resp = licenseService.reportBinding(token, "NEW-M");

        assertEquals("NEW-M", license.getMachineCode());
        assertEquals("NEW-M", resp.getMachineId());
        assertEquals("ACTIVE", resp.getStatus());
        assertNotNull(resp.getSignedToken());
        // 重签的 token 必须已含本次绑定（先写机器码再签名）
        assertEquals("NEW-M", licenseIssuer.decodePayload(resp.getSignedToken()).get("mid"));
        verify(machineRegistryService).touch("NEW-M", MachineRegistryService.SRC_REPORT);
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.BOUND_BY_REPORT));
    }

    @Test
    void reportBinding_shouldBeIdempotent_whenSameMachine() {
        License license = unboundSignedLicense("LIC-IDEM");
        license.setMachineCode("SAME-M");
        when(licenseRepository.findByLicenseKey("LIC-IDEM")).thenReturn(Optional.of(license));

        ActivateResponse resp = licenseService.reportBinding(license.getSignedToken(), "SAME-M");

        assertEquals("SAME-M", resp.getMachineId());
        // 幂等：不二次落库、不记事件
        verify(licenseRepository, never()).save(any(License.class));
        verify(licenseEventRepository, never()).save(any(LicenseEvent.class));
    }

    @Test
    void reportBinding_shouldReject_whenBoundToAnotherMachine() {
        License license = unboundSignedLicense("LIC-OTHER");
        license.setMachineCode("OTHER-M");
        when(licenseRepository.findByLicenseKey("LIC-OTHER")).thenReturn(Optional.of(license));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.reportBinding(license.getSignedToken(), "NEW-M"));

        assertEquals("MACHINE_MISMATCH", ex.getErrorCode());
        verify(licenseRepository, never()).save(any(License.class));
    }

    @Test
    void reportBinding_shouldThrowCredentialNotFound_whenTokenTampered() {
        License license = unboundSignedLicense("LIC-TAMPER");
        String tampered = license.getSignedToken() + "x";

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.reportBinding(tampered, "NEW-M"));

        assertEquals("CREDENTIAL_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void reportBinding_shouldThrowLicenseNotActive_whenRevoked() {
        License license = unboundSignedLicense("LIC-REVOKED");
        license.setStatus(License.LicenseStatus.REVOKED);
        when(licenseRepository.findByLicenseKey("LIC-REVOKED")).thenReturn(Optional.of(license));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> licenseService.reportBinding(license.getSignedToken(), "NEW-M"));

        assertEquals("LICENSE_NOT_ACTIVE", ex.getErrorCode());
    }
}
