package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
    private CustomerIdentityService customerIdentityService;
    private UserRepository userRepository;
    private LicenseService licenseService;

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
        customerIdentityService = mock(CustomerIdentityService.class);
        userRepository = mock(UserRepository.class);
        // D6：退款吊销须写 license_events，事件走 LicenseService#recordLicenseEvent。此处用 mock，
        // 既避免拉入 LicenseService 的真实依赖，也便于对「退款是否留痕」单独断言。
        licenseService = mock(LicenseService.class);
        adminService = new AdminService(
                orderRepository, orderService, licenseRepository,
                paymentServiceFactory, paymentService, emailNotificationService, paymentRepository,
                customerIdentityService, userRepository, licenseService);
        // self 为 @Lazy 自注入代理，脱离 Spring 容器时为 null，直调 self.persistRefundFailed 会 NPE。
        // 单测无事务，将 self 指向自身即可让 persistRefundFailed 正常执行（等价直调）。
        ReflectionTestUtils.setField(adminService, "self", adminService);
    }

    private Order paidOrder(PaymentMethod provider) {
        return Order.builder()
                .id(UUID.randomUUID())
                .orderNumber("ORD-REF-1")
                .totalAmount(new BigDecimal("10.00"))
                .currency(Currency.USD)
                .paymentProvider(provider)
                .email("buyer@example.com")
                .status(Order.OrderStatus.PAID)
                .paymentStatus(Order.PaymentStatus.PAID)
                .build();
    }

    @Test
    void refundOrder_shouldThrowRefundFailed_whenChannelReturnsFalse() {
        Order order = paidOrder(PaymentMethod.STRIPE);
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
        Order order = paidOrder(PaymentMethod.STRIPE);
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
        Order order = paidOrder(PaymentMethod.ALIPAY);
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
        // D6：退款吊销必须写 license_events 留痕（否则流水账查不到作废历史）
        verify(licenseService).recordLicenseEvent(eq(license), eq(LicenseEvent.EventType.REVOKED),
                any(), contains("ORD-REF-1"));
        // 退款通知已发送（M5 专用文案）
        verify(emailNotificationService).sendRefundProcessedEmail(eq("buyer@example.com"), eq("ORD-REF-1"), anyString());
    }

    @Test
    void refundOrder_shouldWriteRefundedPayment_whenChannelSucceeds() {
        Order order = paidOrder(PaymentMethod.STRIPE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(true);
        when(paymentServiceFactory.getStrategy(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        // 原支付记录：退款流水的 transactionId 应关联其 paymentId 以便追溯
        Payment original = Payment.builder().paymentId("ch_real_abc123").build();
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.of(original));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        adminService.refundOrder("ORD-REF-1", "用户申请");

        // 退款成功后应写入一条 REFUNDED 支付流水，金额/币种与订单一致，channel/method 同源
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment refund = captor.getValue();
        assertEquals(PaymentStatus.REFUNDED, refund.getStatus());
        assertEquals(new BigDecimal("10.00"), refund.getAmount());
        assertEquals(Currency.USD, refund.getCurrency());
        assertEquals(PaymentMethod.STRIPE, refund.getChannel());
        assertEquals(PaymentMethod.STRIPE, refund.getMethod());
        assertEquals("ch_real_abc123", refund.getTransactionId());
        assertEquals(order.getId().toString(), refund.getOrderIdStr());
        assertTrue(refund.getPaymentId().startsWith("REFUND-"));
    }

    @Test
    void refundOrder_shouldNotWritePayment_whenChannelFails() {
        Order order = paidOrder(PaymentMethod.STRIPE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(false);
        when(paymentServiceFactory.getStrategy(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> adminService.refundOrder("ORD-REF-1", "用户申请"));

        // 渠道退款失败：绝不能写入 REFUNDED 流水（钱没退成）
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    // ============ plan-4.1：部分退款与降级 ============

    /** 部分退款：折算额须同时贯通「渠道调用」与「退款流水」，状态置 PARTIALLY_REFUNDED */
    @Test
    void refundOrder_withPartialAmount_passesAmountToChannelAndLedger() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), eq(new BigDecimal("4.00")))).thenReturn(true);
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        License license = new License();
        license.setStatus(License.LicenseStatus.ACTIVE);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of(license));
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        adminService.refundOrder("ORD-REF-1", "按使用时间折算", new BigDecimal("4.00"));

        // 渠道实收折算额（而非订单全额）
        verify(strategy, times(1)).refundPayment(eq(order), any(), eq(new BigDecimal("4.00")));
        // 状态：部分退款只动 paymentStatus，status 保持 PAID（canFulfill 仍为 false）
        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.PAID, order.getStatus());
        // 权益同步收回
        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
        // 流水金额 = 实退额，保证「状态已退」与「资金流水」一致
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals(new BigDecimal("4.00"), captor.getValue().getAmount());
    }

    /** 降级（拍板）：渠道不受理部分金额 → 自动改发全额退，并留痕 metadata 供对账追溯 */
    @Test
    void refundOrder_shouldDegradeToFullRefund_whenChannelRejectsPartial() {
        Order order = paidOrder(PaymentMethod.PADDLE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), eq(new BigDecimal("4.00")))).thenReturn(false);
        when(strategy.refundPayment(any(), any(), eq(new BigDecimal("10.00")))).thenReturn(true);
        when(paymentServiceFactory.getStrategy(PaymentMethod.PADDLE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        adminService.refundOrder("ORD-REF-1", "按使用时间折算", new BigDecimal("4.00"));

        // 降级后按全额退款 → 走全额路径
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.REFUNDED, order.getStatus());
        // 留痕：降级事实与原始折算额都要可追溯
        assertTrue(order.getMetadata().contains("refundDegraded"));
        assertTrue(order.getMetadata().contains("4.00"));
        // 流水记录的是实际退还的全额
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertEquals(new BigDecimal("10.00"), captor.getValue().getAmount());
    }

    /** 部分与全额均失败 → 仍按 H4 口径标记 REFUND_FAILED，paymentStatus 保持 PAID */
    @Test
    void refundOrder_shouldMarkRefundFailed_whenBothPartialAndFullRejected() {
        Order order = paidOrder(PaymentMethod.PADDLE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), any())).thenReturn(false);
        when(paymentServiceFactory.getStrategy(PaymentMethod.PADDLE)).thenReturn(strategy);
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.refundOrder("ORD-REF-1", "按使用时间折算", new BigDecimal("4.00")));

        assertEquals("REFUND_FAILED", ex.getErrorCode());
        assertEquals(Order.OrderStatus.REFUND_FAILED, order.getStatus());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
        // 尝试金额留痕，便于人工复核「到底试过退多少」
        assertTrue(order.getMetadata().contains("refundAttemptedAmount"));
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    /** 一单只退一次：已部分退款的订单不得再退剩余 */
    @Test
    void refundOrder_shouldReject_whenAlreadyPartiallyRefunded() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        order.markPartiallyRefunded();
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.refundOrder("ORD-REF-1", "再退一次", new BigDecimal("6.00")));

        assertEquals("ALREADY_REFUNDED", ex.getErrorCode());
        verify(paymentServiceFactory, never()).getStrategy(any());
    }

    /** 非法金额（0 / 负数）必须拒绝，不允许「退 0 元」把订单标成已退 */
    @Test
    void refundOrder_shouldRejectNonPositiveAmount() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.refundOrder("ORD-REF-1", "零元退款", BigDecimal.ZERO));

        assertEquals("INVALID_REFUND_AMOUNT", ex.getErrorCode());
        verify(paymentServiceFactory, never()).getStrategy(any());
    }

    /** 金额超过实付额时按全额处理（不信任调用方金额） */
    @Test
    void refundOrder_shouldClampToTotal_whenAmountExceedsTotal() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refundPayment(any(), any(), eq(new BigDecimal("10.00")))).thenReturn(true);
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        adminService.refundOrder("ORD-REF-1", "越界金额", new BigDecimal("999.00"));

        verify(strategy, times(1)).refundPayment(eq(order), any(), eq(new BigDecimal("10.00")));
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
    }

    @Test
    void refundOrder_shouldResolveProviderFromPayment_whenOrderProviderNull() {        // C2 兜底：存量订单 paymentProvider 为 NULL，应从 Payment.method 回补渠道
        Order order = paidOrder(null); // 模拟存量订单：从未落库 provider
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        Payment payment = Payment.builder().paymentId("ch_real_abc123").method(PaymentMethod.STRIPE).build();
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
