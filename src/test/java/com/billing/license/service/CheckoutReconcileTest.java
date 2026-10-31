package com.billing.license.service;

import com.billing.license.entity.*;
import com.billing.license.repository.*;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.*;
import com.billing.license.service.payment.util.AmountValidator;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #50：后台/轮询共用的收银台对账与过期清理。
 */
class CheckoutReconcileTest {

    private ProductRepository productRepository;
    private OrderRepository orderRepository;
    private CheckoutSessionRepository checkoutSessionRepository;
    private PaymentService paymentService;
    private PaymentAttemptRepository attemptRepository;
    private CheckoutService checkoutService;

    private Order order;
    private PaymentAttempt attempt;
    private CheckoutSession session;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        orderRepository = mock(OrderRepository.class);
        checkoutSessionRepository = mock(CheckoutSessionRepository.class);
        paymentService = mock(PaymentService.class);
        attemptRepository = mock(PaymentAttemptRepository.class);
        CustomerIdentityService customerIdentityService = mock(CustomerIdentityService.class);
        when(customerIdentityService.resolveOrCreate(anyString())).thenReturn(UUID.randomUUID());

        checkoutService = new CheckoutService(
            productRepository, orderRepository, checkoutSessionRepository,
            mock(PaymentServiceFactory.class), paymentService,
            mock(com.billing.license.service.fulfillment.FulfillmentService.class),
            mock(LicenseRepository.class), mock(RedeemCodeRepository.class),
            attemptRepository, mock(RateLimitService.class),
            customerIdentityService, mock(AmountValidator.class));
        ReflectionTestUtils.setField(checkoutService, "self", checkoutService);

        order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).build();
        attempt = PaymentAttempt.builder().paymentId("alipay_ORD-1")
            .orderIdStr(order.getId().toString()).method(PaymentMethod.ALIPAY)
            .status(PaymentStatus.PENDING).build();
        session = CheckoutSession.builder().checkoutId("chk_1").orderId(order.getId())
            .orderNumber("ORD-1").status(CheckoutSession.Status.PENDING)
            .expiresAt(LocalDateTime.now().minusMinutes(5)).machineId("M1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));
        when(attemptRepository.findAllByOrderIdStrOrderByCreatedAtDesc(order.getId().toString()))
            .thenReturn(List.of(attempt));
        when(attemptRepository.save(any(PaymentAttempt.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(orderRepository.findByOrderNumberForUpdate("ORD-1")).thenReturn(Optional.empty());
    }

    @Test
    void expiredSession_paidAtChannel_shouldCompensateAndDeliver() {
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(new PaymentQueryResult(PaymentStatus.SUCCESS, new BigDecimal("99.00"),
                "CNY", "trade_1", "ORD-1", true));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.PAID, result);
        assertEquals(CheckoutSession.Status.PAID, session.getStatus());
        verify(paymentService, never()).closeUnpaidPayment(anyString(), any());
    }

    @Test
    void expiredSession_unpaidAndChannelClosed_shouldExpireAndCancelAttempt() {
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));
        when(paymentService.closeUnpaidPayment("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentCloseResult.closed("关单成功"));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.CLOSED, result);
        assertEquals(CheckoutSession.Status.EXPIRED, session.getStatus());
        assertNotNull(session.getChannelClosedAt());
        assertEquals(PaymentStatus.CANCELLED, attempt.getStatus());
    }

    @Test
    void expiredSession_closeUnsupported_shouldKeepSessionForRetry() {
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.UNKNOWN));
        when(paymentService.closeUnpaidPayment("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentCloseResult.unsupported("渠道未实现关单"));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.CLOSE_UNSUPPORTED, result);
        assertEquals(CheckoutSession.Status.PENDING, session.getStatus());
        assertNull(session.getChannelClosedAt());
        assertNotNull(session.getLastCompensatedAt());
    }

    @Test
    void expiredSession_closeRejected_shouldNotTreatAsClosed() {
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));
        when(paymentService.closeUnpaidPayment("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentCloseResult.rejected("ACQ.TRADE_STATUS_ERROR"));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.CLOSE_UNKNOWN, result);
        assertEquals(CheckoutSession.Status.PENDING, session.getStatus());
        assertNull(session.getChannelClosedAt());
    }

    @Test
    void expiredSession_successButAmountMismatch_shouldStayUnverified() {
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(new PaymentQueryResult(PaymentStatus.SUCCESS, new BigDecimal("50.00"),
                "CNY", "trade_1", "ORD-1", true));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.UNVERIFIED, result);
        assertEquals(CheckoutSession.Status.PENDING, session.getStatus());
        verify(paymentService, never()).closeUnpaidPayment(anyString(), any());
    }

    @Test
    void activeSession_unpaid_shouldNotClose() {
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.UNPAID, result);
        verify(paymentService, never()).closeUnpaidPayment(anyString(), any());
    }

    @Test
    void pollPath_shouldNeverClosePayment() {
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));

        checkoutService.reconcileSession("chk_1", false);

        verify(paymentService, never()).closeUnpaidPayment(anyString(), any());
    }

    @Test
    void expiredSession_olderAttemptPaid_shouldCompensateEvenIfLatestUnpaid() {
        PaymentAttempt older = PaymentAttempt.builder().paymentId("alipay_ORD-1")
            .orderIdStr(order.getId().toString()).method(PaymentMethod.ALIPAY)
            .status(PaymentStatus.PENDING).build();
        PaymentAttempt newer = PaymentAttempt.builder().paymentId("cs_new")
            .orderIdStr(order.getId().toString()).method(PaymentMethod.STRIPE)
            .status(PaymentStatus.PENDING).build();
        when(attemptRepository.findAllByOrderIdStrOrderByCreatedAtDesc(order.getId().toString()))
            .thenReturn(List.of(newer, older));
        when(paymentService.queryPaymentDetail("cs_new", PaymentMethod.STRIPE))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(new PaymentQueryResult(PaymentStatus.SUCCESS, new BigDecimal("99.00"),
                "CNY", "trade_1", "ORD-1", true));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.PAID, result);
        assertEquals(CheckoutSession.Status.PAID, session.getStatus());
        verify(paymentService, never()).closeUnpaidPayment(anyString(), any());
    }

    @Test
    void expiredSession_multipleUnpaid_shouldCloseAllBeforeExpiring() {
        PaymentAttempt older = PaymentAttempt.builder().paymentId("alipay_ORD-1")
            .orderIdStr(order.getId().toString()).method(PaymentMethod.ALIPAY)
            .status(PaymentStatus.PENDING).build();
        PaymentAttempt newer = PaymentAttempt.builder().paymentId("cs_new")
            .orderIdStr(order.getId().toString()).method(PaymentMethod.STRIPE)
            .status(PaymentStatus.PENDING).build();
        when(attemptRepository.findAllByOrderIdStrOrderByCreatedAtDesc(order.getId().toString()))
            .thenReturn(List.of(newer, older));
        when(paymentService.queryPaymentDetail("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));
        when(paymentService.queryPaymentDetail("cs_new", PaymentMethod.STRIPE))
            .thenReturn(PaymentQueryResult.statusOnly(PaymentStatus.PENDING));
        when(paymentService.closeUnpaidPayment("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentCloseResult.closed("关单成功"));
        when(paymentService.closeUnpaidPayment("cs_new", PaymentMethod.STRIPE))
            .thenReturn(PaymentCloseResult.closed("关单成功"));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", true);

        assertEquals(CheckoutService.ReconcileResult.CLOSED, result);
        assertEquals(CheckoutSession.Status.EXPIRED, session.getStatus());
        assertEquals(PaymentStatus.CANCELLED, older.getStatus());
        assertEquals(PaymentStatus.CANCELLED, newer.getStatus());
    }

    @Test
    void repositoryFailure_shouldReturnErrorWithoutBreakingPolling() {
        when(attemptRepository.findAllByOrderIdStrOrderByCreatedAtDesc(order.getId().toString()))
            .thenThrow(new RuntimeException("db down"));

        CheckoutService.ReconcileResult result = checkoutService.reconcileSession("chk_1", false);

        assertEquals(CheckoutService.ReconcileResult.ERROR, result);
        assertEquals(CheckoutSession.Status.PENDING, session.getStatus());
    }

    @Test
    void cleanup_shouldDeleteOnlySessionsWithoutPaymentAttempts() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(checkoutSessionRepository.findExpiredUnlinkedIds(eq(CheckoutSession.Status.PAID),
            any(LocalDateTime.class), any())).thenReturn(List.of(a, b));
        when(checkoutSessionRepository.deleteByIds(List.of(a, b))).thenReturn(2);

        checkoutService.cleanupExpiredSessions();

        verify(checkoutSessionRepository).deleteByIds(List.of(a, b));
    }
}
