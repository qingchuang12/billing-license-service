package com.billing.license.service;

import com.billing.license.config.BillingProperties;
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
        licenseService = new LicenseService(
            licenseRepository, orderRepository, licenseEventRepository,
            licenseIssuer, billingProperties, email,
            mock(com.billing.license.service.risk.RateLimitService.class),
            userRepository);

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
    void revokeLicense_shouldMarkRevokedAndRecordEvent() {
        License license = License.builder()
            .id(UUID.randomUUID()).licenseKey("LIC-2").customerId(customerId)
            .status(License.LicenseStatus.ACTIVE).machineCode("M1").build();
        when(licenseRepository.findByLicenseKey("LIC-2")).thenReturn(Optional.of(license));
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));

        licenseService.revokeLicense("LIC-2");

        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
        assertNotNull(license.getRevokedAt());
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.REVOKED));
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

        licenseService.reissueLicense("LIC-OLD", "MACHINE-NEW", "changed_pc");

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
}
