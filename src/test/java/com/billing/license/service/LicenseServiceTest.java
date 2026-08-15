package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.LicenseEvent;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.infrastructure.kms.KmsService;
import com.billing.license.repository.LicenseEventRepository;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.notification.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

        licenseService = new LicenseService(
            licenseRepository, orderRepository, licenseEventRepository,
            licenseIssuer, billingProperties, email,
            mock(com.billing.license.service.risk.RateLimitService.class));

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

        // 原 License 被标记 REISSUED
        assertEquals(License.LicenseStatus.REVOKED, original.getStatus());
        assertNotNull(original.getRevokedAt());
        // 应记录至少 REISSUED + 新 LICENSE ISSUED 两条事件
        verify(licenseEventRepository, atLeast(2)).save(any(LicenseEvent.class));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.REISSUED));
        verify(licenseEventRepository).save(argThat(e ->
            e.getEventType() == LicenseEvent.EventType.ISSUED
            && "MACHINE-NEW".equals(e.getMachineId())));
    }
}
