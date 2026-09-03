package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminService.refundOrder 单元测试（H4 资损防护 + H5 渠道交易号装配）。
 * - 渠道退款失败 → 标记 REFUND_FAILED（保留 PAID），绝不谎报 REFUNDED。
 * - 渠道退款成功 → 标记 REFUNDED、作废 License、发送退款通知。
 * - 退款目标交易号必须来自 Payment 实体（order.paymentIntentId 从未被赋值）。
 */
class AdminServiceRefundTest {

    private OrderRepository orderRepository;
    private OrderService orderService;
    private LicenseRepository licenseRepository;
    private PaymentServiceFactory paymentServiceFactory;
    private PaymentService paymentService;
    private EmailNotificationService emailNotificationService;
    private PaymentRepository paymentRepository;

    private AdminService adminService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderService = mock(OrderService.class);
        licenseRepository = mock(LicenseRepository.class);
        paymentServiceFactory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        emailNotificationService = mock(EmailNotificationService.class);
        paymentRepository = mock(PaymentRepository.class);
        adminService = new AdminService(
                orderRepository, orderService, licenseRepository,
                paymentServiceFactory, paymentService, emailNotificationService, paymentRepository);
    }

    private Order paidOrder(String provider) {
        return Order.builder()
                .id(UUID.randomUUID())
                .orderNumber("ORD-REF-1")
                .totalAmount(new BigDecimal("10.00"))
                .currency("USD")
                .paymentProvider(provider)
                .email("buyer@example.com")
                .status(Order.OrderStatus.PAID)
                .paymentStatus(Order.PaymentStatus.PAID)
                .build();
    }

    @Test
    void refundOrder_shouldThrowRefundFailed_whenChannelReturnsFalse() {
        Order order = paidOrder("STRIPE");
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(false);
        when(paymentServiceFactory.getStrategy(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.refundOrder("ORD-REF-1", "用户申请"));
        assertEquals("REFUND_FAILED", ex.getErrorCode());

        // H4：绝不能标记 REFUNDED，必须保留 PAID 以示意"钱还在"
        assertEquals(Order.OrderStatus.REFUND_FAILED, order.getStatus());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
    }

    @Test
    void refundOrder_shouldUsePaymentEntityTransactionId() {
        Order order = paidOrder("STRIPE");
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(false);
        when(paymentServiceFactory.getStrategy(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        // H5 修正：交易号来自 Payment 实体，而不是从未赋值的 order.paymentIntentId
        Payment payment = Payment.builder().paymentId("ch_real_abc123").build();
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.of(payment));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> adminService.refundOrder("ORD-REF-1", "用户申请"));

        // 验证退款确实使用了 Payment.paymentId 作为目标交易号
        verify(strategy).refundPayment(eq(order), eq("ch_real_abc123"), eq(new BigDecimal("10.00")));
    }

    @Test
    void refundOrder_shouldMarkRefunded_andRevokeLicenses_whenChannelSucceeds() {
        Order order = paidOrder("ALIPAY");
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(true);
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);

        License license = new License();
        license.setStatus(License.LicenseStatus.ACTIVE);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of(license));
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        adminService.refundOrder("ORD-REF-1", "用户申请");

        // 状态机出口一致：status 与 paymentStatus 均置 REFUNDED
        assertEquals(Order.OrderStatus.REFUNDED, order.getStatus());
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
        // License 被作废
        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
        assertNotNull(license.getRevokedAt());
        // 退款通知已发送（M5 专用文案）
        verify(emailNotificationService).sendRefundProcessedEmail(eq("buyer@example.com"), eq("ORD-REF-1"), anyString());
    }

    @Test
    void refundOrder_shouldResolveProviderFromPayment_whenOrderProviderNull() {
        // C2 兜底：存量订单 paymentProvider 为 NULL，应从 Payment.method 回补渠道
        Order order = paidOrder(null); // 模拟存量订单：从未落库 provider
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        Payment payment = Payment.builder().paymentId("ch_real_abc123").method("STRIPE").build();
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.of(payment));

        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(true);
        when(paymentServiceFactory.getStrategy(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        adminService.refundOrder("ORD-REF-1", "用户申请");

        // 验证：即使 order.paymentProvider=null，也能通过 Payment.method 解析到 STRIPE 并发起退款
        verify(paymentServiceFactory).getStrategy(PaymentMethod.STRIPE);
        verify(strategy).refundPayment(eq(order), eq("ch_real_abc123"), eq(new BigDecimal("10.00")));
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
    }
}
