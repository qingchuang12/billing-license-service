package com.billing.license.controller.webhook;

import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.common.web.WebhookReplayGuard;
import com.billing.license.config.BillingProperties;
import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentAttempt;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.service.RefundSettlementService;
import com.billing.license.service.risk.RateLimitService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentEventType;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.AmountValidator;
import com.billing.license.service.fulfillment.FulfillmentService;
import com.billing.license.service.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WebhookController 单元测试：验签、幂等、**事件语义分流**、金额/币种/归属校验、发货与退款结算。
 */
class WebhookControllerTest {

    private PaymentServiceFactory factory;
    private PaymentService paymentService;
    private OrderRepository orderRepository;
    private AmountValidator amountValidator;
    private PaymentEventRepository paymentEventRepository;
    private FulfillmentService fulfillmentService;
    private SubscriptionService subscriptionService;
    private RefundSettlementService refundSettlementService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        factory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        orderRepository = mock(OrderRepository.class);
        amountValidator = mock(AmountValidator.class);
        paymentEventRepository = mock(PaymentEventRepository.class);
        fulfillmentService = mock(FulfillmentService.class);
        subscriptionService = mock(SubscriptionService.class);
        refundSettlementService = mock(RefundSettlementService.class);

        controller = new WebhookController();
        setField(controller, "paymentServiceFactory", factory);
        setField(controller, "paymentService", paymentService);
        setField(controller, "orderRepository", orderRepository);
        setField(controller, "amountValidator", amountValidator);
        setField(controller, "paymentEventRepository", paymentEventRepository);
        setField(controller, "fulfillmentService", fulfillmentService);
        setField(controller, "subscriptionService", subscriptionService);
        setField(controller, "refundSettlementService", refundSettlementService);
        BillingProperties properties = new BillingProperties();
        setField(controller, "rateLimitService", new RateLimitService(properties));
        setField(controller, "clientIpResolver", new ClientIpResolver(false));
        setField(controller, "webhookReplayGuard", new WebhookReplayGuard(properties));
        setField(controller, "self", controller);

        // 默认：事件未处理过、订阅反查成功、金额校验通过
        when(paymentEventRepository.findByProviderAndEventId(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(subscriptionService.enrichFromProvider(any(), any())).thenReturn(true);
        when(amountValidator.validateAmount(any(), any())).thenReturn(true);
        when(paymentService.ensureAttemptForWebhook(any(), any(), any())).thenReturn(
            PaymentAttempt.builder().paymentId("pay_1").method(PaymentMethod.ALIPAY).build());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebhookPayload payload(String status, String orderId) {
        WebhookPayload p = new WebhookPayload();
        p.setOrderId(orderId);
        p.setPaymentId("pay_1");
        p.setTransactionId("txn_1");
        p.setWebhookEventId("evt_1");
        p.setEventType("payment_success");
        p.setStatus(status);
        p.setAmount(new BigDecimal("99.00"));
        p.setCurrency("CNY");
        return p;
    }

    private PaymentStrategy stubStrategy(boolean signatureValid, WebhookPayload payload) {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(signatureValid);
        when(strategy.parseWebhookPayload(anyString())).thenReturn(payload);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        when(factory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);
        return strategy;
    }

    private Order cnyOrder() {
        return Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).email("u@e.com").build();
    }

    private void stubFulfill(Order order) {
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(orderRepository.findByOrderNumberForUpdate("ORD-1")).thenReturn(Optional.of(order));
    }


    @Test
    void processWebhook_shouldReturn401_whenSignatureInvalid() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(factory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "bad-sig", Map.of());
        assertEquals(401, resp.getStatusCode().value());
        verify(paymentEventRepository, never()).saveAndFlush(any(PaymentEvent.class));
    }

    @Test
    void processWebhook_shouldSkip_whenEventAlreadyProcessed() {
        stubStrategy(true, payload(PaymentStatus.SUCCESS.name(), "ORD-1"));
        when(paymentEventRepository.findByProviderAndEventId(PaymentMethod.ALIPAY.name(), "evt_1"))
            .thenReturn(Optional.of(PaymentEvent.builder()
                .provider(PaymentMethod.ALIPAY.name()).eventId("evt_1").processed(true).build()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("success", resp.getBody());
        verify(paymentService, never()).recordCharge(any(), any(), any(), any(), any(), any(), any(), any());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void processWebhook_shouldFulfill_whenAmountValid() {
        stubStrategy(true, payload(PaymentStatus.SUCCESS.name(), "ORD-1"));
        Order order = cnyOrder();
        stubFulfill(order);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("success", resp.getBody());
        verify(fulfillmentService).enqueue(any(Order.class), eq("LICENSE"), isNull());
        verify(fulfillmentService).processPending("ORD-1", "LICENSE");
        // 幂等行先以未完成入库，业务成功后才标 processed=true
        verify(paymentEventRepository).saveAndFlush(argThat(e -> Boolean.FALSE.equals(e.getProcessed())));
    }

    @Test
    void processWebhook_shouldStillFulfill_whenPaymentRecordNotLocated() {
        stubStrategy(true, payload(PaymentStatus.SUCCESS.name(), "ORD-1"));
        Order order = cnyOrder();
        stubFulfill(order);
        when(paymentService.ensureAttemptForWebhook(any(), any(), any())).thenReturn(null);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(fulfillmentService).enqueue(any(Order.class), eq("LICENSE"), isNull());
    }

    @Test
    void processWebhook_shouldReject_whenAmountMismatch() {
        stubStrategy(true, payload(PaymentStatus.SUCCESS.name(), "ORD-1"));
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));
        when(amountValidator.validateAmount(any(Order.class), any())).thenReturn(false);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void processWebhook_shouldReject_whenAmountMissing() {
        WebhookPayload p = payload(PaymentStatus.SUCCESS.name(), "ORD-1");
        p.setAmount(null);
        stubStrategy(true, p);
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void processWebhook_shouldReject_whenCurrencyMismatch() {
        WebhookPayload p = payload(PaymentStatus.SUCCESS.name(), "ORD-1");
        p.setCurrency("USD");
        stubStrategy(true, p);
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void processWebhook_shouldReject_whenChannelDoesNotOwnPayment() {
        stubStrategy(true, payload(PaymentStatus.SUCCESS.name(), "ORD-1"));
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));
        when(paymentService.ensureAttemptForWebhook(any(), any(), any()))
            .thenThrow(new BusinessException("WEBHOOK_PAYMENT_MISMATCH", "channel mismatch"));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void processWebhook_shouldSettleRefund_withActualAmount() {
        WebhookPayload refund = new WebhookPayload();
        refund.setOrderId("ORD-9");
        refund.setPaymentId("txn_9");
        refund.setTransactionId("txn_9");
        refund.setWebhookEventId("evt_refund");
        refund.setEventType("refund.created");
        refund.setStatus(PaymentStatus.REFUNDED.name());
        refund.setRefundAmount(new BigDecimal("12.10"));
        refund.setRefundCurrency("EUR");
        stubStrategy(true, refund);
        when(orderRepository.findByOrderNumber("ORD-9"))
            .thenReturn(Optional.of(Order.builder().orderNumber("ORD-9").build()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(refundSettlementService).settle(any(Order.class), eq(PaymentMethod.ALIPAY),
            eq("evt_refund"), eq(new BigDecimal("12.10")), eq("EUR"), anyString(), eq("txn_9"),
            eq(false));
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    /**
     * 支付宝部分退款：报文里的 refund_fee 是累计口径，必须原样交给结算层按差额入账，
     * 且绝不能被当成收款成功去发货。
     */
    @Test
    void alipayPartialRefund_shouldPassCumulativeFlagAndNeverFulfill() {
        WebhookPayload refund = new WebhookPayload();
        refund.setOrderId("ORD-9");
        refund.setPaymentId("alipay_ORD-9");
        refund.setTransactionId("txn_9");
        refund.setWebhookEventId("notify_9");
        refund.setStatus(PaymentStatus.REFUNDED.name());
        refund.setEventKind(PaymentEventType.REFUND_SUCCEEDED);
        refund.setRefundAmount(new BigDecimal("20.00"));
        refund.setRefundCurrency("CNY");
        refund.setRefundAmountCumulative(true);
        stubStrategy(true, refund);
        when(orderRepository.findByOrderNumber("ORD-9"))
            .thenReturn(Optional.of(Order.builder().orderNumber("ORD-9").build()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(refundSettlementService).settle(any(Order.class), eq(PaymentMethod.ALIPAY),
            eq("notify_9"), eq(new BigDecimal("20.00")), eq("CNY"), anyString(), eq("txn_9"),
            eq(true));
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void processWebhook_shouldRejectRefundWithoutAmount() {
        WebhookPayload refund = new WebhookPayload();
        refund.setOrderId("ORD-9");
        refund.setTransactionId("txn_9");
        refund.setWebhookEventId("evt_refund");
        refund.setStatus(PaymentStatus.REFUNDED.name());
        stubStrategy(true, refund);
        when(orderRepository.findByOrderNumber("ORD-9"))
            .thenReturn(Optional.of(Order.builder().orderNumber("ORD-9").build()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(refundSettlementService, never()).settle(any(), any(), any(), any(), any(), any(), any(),
            anyBoolean());
    }

    /**
     * 迟到双渠道：订单已由 A 渠道收款成功后，B 渠道（或换事件号重投的同渠道）再回调成功，
     * 不得追加第二条 CHARGE（幂等键含事件号，不同事件号挡不住，账本会虚增收款），也不得重复发货。
     */
    @Test
    void lateSecondChannelPayment_shouldNotChargeAgainNorFulfill() {
        Order paid = Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).email("u@e.com")
            .status(Order.OrderStatus.PAID).paymentStatus(Order.PaymentStatus.PAID).build();
        stubFulfill(paid);
        WebhookPayload late = payload(PaymentStatus.SUCCESS.name(), "ORD-1");
        late.setEventKind(PaymentEventType.PAYMENT_SUCCEEDED);
        late.setTransactionId("txn_other_channel");
        late.setWebhookEventId("evt_other_channel");
        stubStrategy(true, late);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value(), "已收款订单的迟到通知应被确认收到，让渠道停止重投");
        verify(paymentService, never()).recordCharge(any(), any(), any(), any(), any(), any(), any(), any());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void pausedSubscription_shouldNotFulfillOrUpdatePayment() {
        WebhookPayload paused = payload(PaymentStatus.PENDING.name(), "ORD-1");
        paused.setSubscriptionId("sub_1");
        paused.setEventType("subscription.paused");
        paused.setEventKind(PaymentEventType.SUBSCRIPTION_PAUSED);
        stubStrategy(true, paused);
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(subscriptionService).processSubscriptionEvent(any(), eq(PaymentMethod.ALIPAY));
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
        verify(paymentService, never()).recordCharge(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredSubscription_shouldNotBeTreatedAsPaymentSuccess() {
        WebhookPayload expired = payload("EXPIRED", "ORD-1");
        expired.setSubscriptionId("sub_1");
        expired.setEventType("subscription.expired");
        expired.setEventKind(PaymentEventType.SUBSCRIPTION_EXPIRED);
        stubStrategy(true, expired);
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(subscriptionService).processSubscriptionEvent(any(), eq(PaymentMethod.ALIPAY));
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void subscriptionPayment_shouldFulfillOnlyOnceAndUpdateSubscription() {
        WebhookPayload paid = payload(PaymentStatus.SUCCESS.name(), "ORD-1");
        paid.setSubscriptionId("sub_1");
        paid.setEventType("subscription.paid");
        paid.setEventKind(PaymentEventType.SUBSCRIPTION_PAYMENT);
        stubStrategy(true, paid);
        stubFulfill(cnyOrder());

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(fulfillmentService).enqueue(any(Order.class), eq("LICENSE"), isNull());
        verify(subscriptionService).processSubscriptionEvent(any(), eq(PaymentMethod.ALIPAY));
    }

    @Test
    void paymentCancelled_shouldNotRevokeBenefits() {
        WebhookPayload cancelled = payload(PaymentStatus.CANCELLED.name(), "ORD-1");
        cancelled.setEventKind(PaymentEventType.PAYMENT_CANCELLED);
        stubStrategy(true, cancelled);
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(cnyOrder()));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        verify(refundSettlementService, never()).revokeBenefits(any(), any());
        verify(fulfillmentService, never()).enqueue(any(), any(), any());
    }

    @Test
    void alipayWebhook_shouldReadSignFromBodyParams_notFromHeader() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any()))
            .thenAnswer(inv -> "real-sign".equals(inv.getArgument(1)));
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        WebhookPayload parsed = payload(PaymentStatus.PENDING.name(), "ORD-1");
        parsed.setEventKind(PaymentEventType.OTHER);
        when(strategy.parseWebhookPayload(anyString())).thenReturn(parsed);
        when(factory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);

        String body = "sign=real-sign&out_trade_no=ORD-1&trade_status=TRADE_SUCCESS";
        controller.alipayWebhook(body, Map.of("sign", "header-sign"));

        verify(strategy).verifyWebhookSignature(anyString(), eq("real-sign"), any());
    }

    @Test
    void alipayWebhook_shouldPassNullSign_whenBodyHasNoSign() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        when(factory.getStrategyForExistingPayment(PaymentMethod.ALIPAY)).thenReturn(strategy);

        controller.alipayWebhook("out_trade_no=ORD-1&trade_status=TRADE_SUCCESS", Map.of());

        verify(strategy).verifyWebhookSignature(anyString(), isNull(), any());
    }

    @Test
    void wechatWebhook_shouldPropagate401_whenSignatureInvalid() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.WECHAT_PAY);
        when(factory.getStrategyForExistingPayment(PaymentMethod.WECHAT_PAY)).thenReturn(strategy);

        ResponseEntity<String> resp = controller.wechatWebhook("sign=abc&out_trade_no=ORD-1",
            Map.of("Wechatpay-Signature", "abc"));
        assertEquals(401, resp.getStatusCode().value());
        assertTrue(resp.getBody() == null || !resp.getBody().contains("SUCCESS"));
    }

    @Test
    void wechatWebhook_shouldReturnSuccessBody_whenSignatureValid() {
        WebhookPayload parsed = payload(PaymentStatus.PENDING.name(), "ORD-1");
        parsed.setEventKind(PaymentEventType.OTHER);
        PaymentStrategy wechat = mock(PaymentStrategy.class);
        when(wechat.verifyWebhookSignature(anyString(), any(), any())).thenReturn(true);
        when(wechat.parseWebhookPayload(anyString())).thenReturn(parsed);
        when(factory.getStrategyForExistingPayment(PaymentMethod.WECHAT_PAY)).thenReturn(wechat);

        ResponseEntity<String> resp = controller.wechatWebhook("sign=abc&out_trade_no=ORD-1",
            Map.of("Wechatpay-Signature", "abc"));
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("SUCCESS"));
    }
}
