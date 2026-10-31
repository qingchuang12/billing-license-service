package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.RefundResult;
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
    private CustomerIdentityService customerIdentityService;
    private UserRepository userRepository;
    private LicenseService licenseService;
    private com.billing.license.repository.CheckoutSessionRepository checkoutSessionRepository;
    private com.billing.license.repository.RedeemCodeRepository redeemCodeRepository;
    private com.billing.license.repository.PaymentLedgerRepository ledgerRepository;
    private RefundSettlementService refundSettlementService;
    private com.billing.license.repository.RefundRequestRepository refundRequestRepository;
    private com.billing.license.repository.SubscriptionRepository subscriptionRepository;
    private final java.util.Map<UUID, com.billing.license.entity.RefundRequest> refundRequests = new java.util.HashMap<>();

    private RefundService refundService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        orderService = mock(OrderService.class);
        licenseRepository = mock(LicenseRepository.class);
        paymentServiceFactory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        emailNotificationService = mock(EmailNotificationService.class);
        customerIdentityService = mock(CustomerIdentityService.class);
        userRepository = mock(UserRepository.class);
        // D6：退款吊销须写 license_events，事件走 LicenseService#recordLicenseEvent。此处用 mock，
        // 既避免拉入 LicenseService 的真实依赖，也便于对「退款是否留痕」单独断言。
        licenseService = mock(LicenseService.class);
        checkoutSessionRepository = mock(com.billing.license.repository.CheckoutSessionRepository.class);
        redeemCodeRepository = mock(com.billing.license.repository.RedeemCodeRepository.class);
        ledgerRepository = mock(com.billing.license.repository.PaymentLedgerRepository.class);
        // 用真实结算服务（其依赖均为 mock），保留原有「吊销 License / 承诺退款流水」断言
        refundSettlementService = new RefundSettlementService(
                licenseRepository, redeemCodeRepository, ledgerRepository, paymentService, licenseService);
        refundRequestRepository = mock(com.billing.license.repository.RefundRequestRepository.class);
        subscriptionRepository = mock(com.billing.license.repository.SubscriptionRepository.class);
        when(subscriptionRepository.findByOrderId(any())).thenReturn(java.util.List.of());
        when(refundRequestRepository.findByOrderId(any())).thenAnswer(inv -> refundRequests.values().stream()
                .filter(r -> r.getOrderId().equals(inv.getArgument(0))).findFirst());
        when(refundRequestRepository.findById(any())).thenAnswer(inv -> java.util.Optional.ofNullable(refundRequests.get(inv.getArgument(0))));
        when(refundRequestRepository.findByIdForUpdate(any())).thenAnswer(inv -> java.util.Optional.ofNullable(refundRequests.get(inv.getArgument(0))));
        when(refundRequestRepository.findTop50ByStatusInOrderByUpdatedAtAsc(any())).thenAnswer(inv -> {
            java.util.Collection<com.billing.license.entity.RefundRequest.Status> statuses = inv.getArgument(0);
            return refundRequests.values().stream()
                    .filter(r -> statuses.contains(r.getStatus())).toList();
        });
        when(refundRequestRepository.save(any())).thenAnswer(i -> {
            com.billing.license.entity.RefundRequest r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            refundRequests.put(r.getId(), r);
            return r;
        });
        when(refundRequestRepository.saveAndFlush(any())).thenAnswer(i -> {
            com.billing.license.entity.RefundRequest r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            refundRequests.put(r.getId(), r);
            return r;
        });
        refundService = new RefundService(
                orderRepository, orderService, paymentServiceFactory, paymentService,
                refundSettlementService, refundRequestRepository, subscriptionRepository,
                emailNotificationService, checkoutSessionRepository);
        // self 为 @Lazy 自注入代理，脱离 Spring 容器时指向自身（单测无事务语义）。
        ReflectionTestUtils.setField(refundService, "self", refundService);
        // 单测没有真实行锁：把 findByOrderNumberForUpdate 委托给普通查询，保持既有用例断言不变。
        when(orderRepository.findByOrderNumberForUpdate(anyString()))
                .thenAnswer(inv -> orderRepository.findByOrderNumber(inv.getArgument(0)));
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
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.rejected("rejected"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "用户申请"));
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
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.rejected("rejected"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        // H5 修正：交易号来自 Payment 实体，而不是从未赋值的 order.paymentIntentId
        PaymentAttempt payment = PaymentAttempt.builder().paymentId("ch_real_abc123").build();
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(payment);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> refundService.refundOrder("ORD-REF-1", "用户申请"));

        // 验证退款确实使用了 Payment.paymentId 作为目标交易号
        verify(strategy).refund(eq(order), eq("ch_real_abc123"), eq(new BigDecimal("10.00")), anyString());
    }

    @Test
    void refundOrder_shouldMarkRefunded_andRevokeLicenses_whenChannelSucceeds() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.success("rf_1"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);

        License license = new License();
        license.setStatus(License.LicenseStatus.ACTIVE);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of(license));
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.refundOrder("ORD-REF-1", "用户申请");

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
        verify(emailNotificationService).sendRefundProcessedEmail(eq("buyer@example.com"), eq("ORD-REF-1"), anyString(), isNull());
    }

    @Test
    void refundOrder_shouldWriteRefundedPayment_whenChannelSucceeds() {
        Order order = paidOrder(PaymentMethod.STRIPE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.success("rf_1"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        // 原支付记录：退款流水的 transactionId 应关联其 paymentId 以便追溯
        PaymentAttempt original = PaymentAttempt.builder().paymentId("ch_real_abc123").build();
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(original);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.refundOrder("ORD-REF-1", "用户申请");

        // 退款成功写不可变退款分录：金额/币种/渠道与原收款交易号一致
        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.STRIPE), anyString(),
            eq(new BigDecimal("10.00")), eq(Currency.USD), isNull(), eq("ch_real_abc123"), anyString());
    }

    @Test
    void refundOrder_shouldNotWritePayment_whenChannelFails() {
        Order order = paidOrder(PaymentMethod.STRIPE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.rejected("rejected"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        assertThrows(BusinessException.class, () -> refundService.refundOrder("ORD-REF-1", "用户申请"));

        // 渠道退款失败：绝不能写入 REFUNDED 流水（钱没退成）
        verify(paymentService, never()).recordRefund(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ============ plan-4.1：部分退款与降级 ============

    /** 部分退款：折算额须同时贯通「渠道调用」与「退款流水」，状态置 PARTIALLY_REFUNDED */
    @Test
    void refundOrder_withPartialAmount_passesAmountToChannelAndLedger() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), eq(new BigDecimal("4.00")), any())).thenReturn(RefundResult.success("rf_partial"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);
        License license = new License();
        license.setStatus(License.LicenseStatus.ACTIVE);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of(license));
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.refundOrder("ORD-REF-1", "按使用时间折算", new BigDecimal("4.00"));

        // 渠道实收折算额（而非订单全额）
        verify(strategy, times(1)).refund(eq(order), any(), eq(new BigDecimal("4.00")), anyString());
        // 状态：部分退款只动 paymentStatus，status 保持 PAID（canFulfill 仍为 false）
        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.PAID, order.getStatus());
        // 权益同步收回
        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
        // 分录金额 = 实退额，保证「状态已退」与「资金流水」一致
        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.ALIPAY), anyString(),
            eq(new BigDecimal("4.00")), eq(Currency.USD), isNull(), any(), anyString());
    }

    /** 降级（拍板）：渠道不受理部分金额 → 自动改发全额退，并留痕 metadata 供对账追溯 */
    @Test
    void refundOrder_shouldDegradeToFullRefund_whenChannelRejectsPartial() {
        Order order = paidOrder(PaymentMethod.PADDLE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        // 只有「明确不支持部分退款」才允许降级；拒绝/未知不得改发第二笔
        when(strategy.refund(any(), any(), eq(new BigDecimal("4.00")), any()))
            .thenReturn(RefundResult.unsupported("不支持部分退款"));
        when(strategy.refund(any(), any(), eq(new BigDecimal("10.00")), any()))
            .thenReturn(RefundResult.success("rf_full"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.PADDLE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.refundOrder("ORD-REF-1", "按使用时间折算", new BigDecimal("4.00"));

        // 降级后按全额退款 → 走全额路径
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.REFUNDED, order.getStatus());
        // 留痕：降级事实与原始折算额都要可追溯
        assertTrue(order.getMetadata().contains("refundDegraded"));
        assertTrue(order.getMetadata().contains("4.00"));
        // 分录记录的是实际退还的全额
        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.PADDLE), anyString(),
            eq(new BigDecimal("10.00")), eq(Currency.USD), isNull(), any(), anyString());
    }

    /** 部分与全额均失败 → 仍按 H4 口径标记 REFUND_FAILED，paymentStatus 保持 PAID */
    @Test
    void refundOrder_shouldMarkRefundFailed_whenBothPartialAndFullRejected() {
        Order order = paidOrder(PaymentMethod.PADDLE);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.rejected("rejected"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.PADDLE)).thenReturn(strategy);
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "按使用时间折算", new BigDecimal("4.00")));

        assertEquals("REFUND_FAILED", ex.getErrorCode());
        assertEquals(Order.OrderStatus.REFUND_FAILED, order.getStatus());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
        // 尝试金额留痕，便于人工复核「到底试过退多少」
        assertTrue(order.getMetadata().contains("refundAttemptedAmount"));
        verify(paymentService, never()).recordRefund(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** 一单只退一次：已部分退款的订单不得再退剩余 */
    @Test
    void refundOrder_shouldReject_whenAlreadyPartiallyRefunded() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        order.markPartiallyRefunded();
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "再退一次", new BigDecimal("6.00")));

        assertEquals("ALREADY_REFUNDED", ex.getErrorCode());
        verify(paymentServiceFactory, never()).getStrategyForExistingPayment(any());
    }

    /** 非法金额（0 / 负数）必须拒绝，不允许「退 0 元」把订单标成已退 */
    @Test
    void refundOrder_shouldRejectNonPositiveAmount() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "零元退款", BigDecimal.ZERO));

        assertEquals("INVALID_REFUND_AMOUNT", ex.getErrorCode());
        verify(paymentServiceFactory, never()).getStrategyForExistingPayment(any());
    }

    /** 金额超过实付额时按全额处理（不信任调用方金额） */
    @Test
    void refundOrder_shouldClampToTotal_whenAmountExceedsTotal() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), eq(new BigDecimal("10.00")), any())).thenReturn(RefundResult.success("rf_full"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.refundOrder("ORD-REF-1", "越界金额", new BigDecimal("999.00"));

        verify(strategy, times(1)).refund(eq(order), any(), eq(new BigDecimal("10.00")), anyString());
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
    }

    @Test
    void refundOrder_shouldResolveProviderFromPayment_whenOrderProviderNull() {        // C2 兜底：存量订单 paymentProvider 为 NULL，应从 Payment.method 回补渠道
        Order order = paidOrder(null); // 模拟存量订单：从未落库 provider
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentAttempt payment = PaymentAttempt.builder().paymentId("ch_real_abc123").method(PaymentMethod.STRIPE).build();
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(payment);

        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.success("rf_1"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.STRIPE)).thenReturn(strategy);
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.refundOrder("ORD-REF-1", "用户申请");

        // 验证：即使 order.paymentProvider=null，也能通过 Payment.method 解析到 STRIPE 并发起退款
        verify(paymentServiceFactory).getStrategyForExistingPayment(PaymentMethod.STRIPE);
        verify(strategy).refund(eq(order), eq("ch_real_abc123"), eq(new BigDecimal("10.00")), anyString());
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
    }

    /** 渠道已退款但本地结算失败：outbox 必须保留 CHANNEL_SUCCEEDED，后台重试完成 SETTLED。 */
    @Test
    void refundOrder_channelSucceeds_settlementFailure_isRetriedByOutbox() {
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.success("rf_1"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));
        // 第一次本地结算失败：模拟 DB/锁异常
        when(licenseRepository.findByOrder(order))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "用户申请"));
        assertEquals("REFUND_CHANNEL_DONE_SETTLEMENT_PENDING", ex.getErrorCode());

        com.billing.license.entity.RefundRequest req = refundRequests.values().iterator().next();
        assertEquals(com.billing.license.entity.RefundRequest.Status.CHANNEL_SUCCEEDED, req.getStatus());

        refundService.retryPendingRefunds();

        assertEquals(com.billing.license.entity.RefundRequest.Status.SETTLED, req.getStatus());
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
    }

    /** Creem API 无部分退款：应进入人工队列，不调用渠道、不降级全额退。 */
    @Test
    void refundOrder_creemPartial_entersManualQueue() {
        Order order = paidOrder(PaymentMethod.CREEM);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "partial", new BigDecimal("4.00")));
        assertEquals("CREEM_REFUND_PENDING_REVIEW", ex.getErrorCode());

        verify(paymentServiceFactory, never()).getStrategyForExistingPayment(any());
        assertEquals(1, refundRequests.size());
        assertEquals(com.billing.license.entity.RefundRequest.Status.PENDING_REVIEW,
            refundRequests.values().iterator().next().getStatus());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus(), "人工退款未完成前订单仍为已支付");
        verify(emailNotificationService).sendRefundStatusEmail(eq("buyer@example.com"), eq("ORD-REF-1"),
            anyString(), anyString(), anyString(), isNull());
        verify(emailNotificationService).sendRefundOpsEmail(eq("ORD-REF-1"), anyString(), anyString());
    }

    @Test
    void manualRefund_settlesOutbox_whenChannelWebhookAlreadyRefundedOrder() {
        Order order = paidOrder(PaymentMethod.CREEM);
        com.billing.license.entity.RefundRequest req = com.billing.license.entity.RefundRequest.builder()
            .id(UUID.randomUUID()).orderId(order.getId()).orderNumber("ORD-REF-1")
            .provider(PaymentMethod.CREEM).amount(new BigDecimal("4.00"))
            .requestedAmount(new BigDecimal("4.00")).currency(Currency.USD)
            .status(com.billing.license.entity.RefundRequest.Status.MANUAL_REQUIRED)
            .refundRequestId("RF-1").build();
        refundRequests.put(req.getId(), req);
        order.markPartiallyRefunded();
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));

        refundService.retryPendingRefunds();

        assertEquals(com.billing.license.entity.RefundRequest.Status.SETTLED, req.getStatus());
    }

    @Test
    void refundOrder_creemFull_entersReviewWithoutAutoChannelCall() {
        Order order = paidOrder(PaymentMethod.CREEM);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "full", null));
        assertEquals("CREEM_REFUND_PENDING_REVIEW", ex.getErrorCode());
        verify(paymentServiceFactory, never()).getStrategyForExistingPayment(any());
        assertEquals(com.billing.license.entity.RefundRequest.Status.PENDING_REVIEW,
            refundRequests.values().iterator().next().getStatus());
    }

    @Test
    void approveRefund_creemFull_executesAutomaticRefund() {
        Order order = paidOrder(PaymentMethod.CREEM);
        com.billing.license.entity.RefundRequest req = com.billing.license.entity.RefundRequest.builder()
            .id(UUID.randomUUID()).orderId(order.getId()).orderNumber("ORD-REF-1")
            .provider(PaymentMethod.CREEM).amount(new BigDecimal("10.00"))
            .requestedAmount(new BigDecimal("10.00")).currency(Currency.USD)
            .status(com.billing.license.entity.RefundRequest.Status.PENDING_REVIEW)
            .refundRequestId("RF-1").channelPaymentId("ch_pg").build();
        refundRequests.put(req.getId(), req);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.refund(any(), any(), any(), any())).thenReturn(RefundResult.success("rf_creem"));
        when(paymentServiceFactory.getStrategyForExistingPayment(PaymentMethod.CREEM)).thenReturn(strategy);

        com.billing.license.dto.accounting.ManualRefundView view =
            refundService.approveRefund(req.getId(), UUID.randomUUID(), "ok");

        assertEquals(com.billing.license.entity.RefundRequest.Status.SETTLED, view.getStatus());
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
        verify(strategy).refund(eq(order), eq("ch_pg"), eq(new BigDecimal("10.00")), anyString());
    }

    @Test
    void approveRefund_creemPartial_movesToTransferPending() {
        Order order = paidOrder(PaymentMethod.CREEM);
        com.billing.license.entity.RefundRequest req = com.billing.license.entity.RefundRequest.builder()
            .id(UUID.randomUUID()).orderId(order.getId()).orderNumber("ORD-REF-1")
            .provider(PaymentMethod.CREEM).amount(new BigDecimal("4.00"))
            .requestedAmount(new BigDecimal("4.00")).currency(Currency.USD)
            .status(com.billing.license.entity.RefundRequest.Status.PENDING_REVIEW)
            .refundRequestId("RF-1").channelPaymentId("ch_pg").build();
        refundRequests.put(req.getId(), req);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));

        com.billing.license.dto.accounting.ManualRefundView view =
            refundService.approveRefund(req.getId(), UUID.randomUUID(), "ok");

        assertEquals(com.billing.license.entity.RefundRequest.Status.MANUAL_REQUIRED, view.getStatus());
        verify(paymentServiceFactory, never()).getStrategyForExistingPayment(any());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
    }

    @Test
    void confirmManualTransfer_settlesPartialRefund() {
        Order order = paidOrder(PaymentMethod.CREEM);
        com.billing.license.entity.RefundRequest req = com.billing.license.entity.RefundRequest.builder()
            .id(UUID.randomUUID()).orderId(order.getId()).orderNumber("ORD-REF-1")
            .provider(PaymentMethod.CREEM).amount(new BigDecimal("4.00"))
            .requestedAmount(new BigDecimal("4.00")).currency(Currency.USD)
            .status(com.billing.license.entity.RefundRequest.Status.MANUAL_REQUIRED)
            .refundRequestId("RF-1").channelPaymentId("ch_pg").build();
        refundRequests.put(req.getId(), req);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        when(orderRepository.findByOrderNumberForUpdate("ORD-REF-1")).thenReturn(Optional.of(order));
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderService.mapToResponse(any())).thenReturn(mock(OrderResponse.class));

        refundService.confirmManualTransfer(req.getId(), "BANK", "TRX-2026-001",
            UUID.randomUUID(), "transferred");

        assertEquals(com.billing.license.entity.RefundRequest.Status.SETTLED, req.getStatus());
        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.CREEM), anyString(),
            eq(new BigDecimal("4.00")), eq(Currency.USD), isNull(), eq("TRX-2026-001"), anyString());
    }

    @Test
    void rejectRefund_keepsOrderPaid() {
        Order order = paidOrder(PaymentMethod.CREEM);
        com.billing.license.entity.RefundRequest req = com.billing.license.entity.RefundRequest.builder()
            .id(UUID.randomUUID()).orderId(order.getId()).orderNumber("ORD-REF-1")
            .provider(PaymentMethod.CREEM).amount(new BigDecimal("10.00"))
            .requestedAmount(new BigDecimal("10.00")).currency(Currency.USD)
            .status(com.billing.license.entity.RefundRequest.Status.PENDING_REVIEW)
            .refundRequestId("RF-1").build();
        refundRequests.put(req.getId(), req);

        refundService.rejectRefund(req.getId(), UUID.randomUUID(), "not eligible");

        assertEquals(com.billing.license.entity.RefundRequest.Status.REJECTED, req.getStatus());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
        assertNull(req.getTransferReference());
    }

    @Test
    void alipayRefund_whenApprovalEnabled_entersReviewWithoutChannelCall() {
        ReflectionTestUtils.setField(refundService, "requireApproval", true);
        Order order = paidOrder(PaymentMethod.ALIPAY);
        when(orderRepository.findByOrderNumber("ORD-REF-1")).thenReturn(Optional.of(order));
        when(paymentService.lockLatestSuccessfulAttempt(order.getId().toString())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> refundService.refundOrder("ORD-REF-1", "audit", null));

        assertEquals("REFUND_PENDING_REVIEW", ex.getErrorCode());
        verify(paymentServiceFactory, never()).getStrategyForExistingPayment(any());
        assertEquals(com.billing.license.entity.RefundRequest.Status.PENDING_REVIEW,
            refundRequests.values().iterator().next().getStatus());
    }
}
