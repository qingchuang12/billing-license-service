package com.billing.license.controller.webhook;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.CheckoutService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.AmountValidator;
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
 * WebhookController 单元测试 - 覆盖统一 Webhook 处理流程：
 * 验签、幂等、金额校验、发货、事件记录（外围接口实现）
 */
class WebhookControllerTest {

    private PaymentServiceFactory factory;
    private PaymentService paymentService;
    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private com.billing.license.repository.LicenseRepository licenseRepository;
    private LicenseService licenseService;
    private RedeemCodeService redeemCodeService;
    private EmailNotificationService emailService;
    private AmountValidator amountValidator;
    private PaymentEventRepository paymentEventRepository;
    private CheckoutService checkoutService;
    private SubscriptionService subscriptionService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        factory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        licenseRepository = mock(com.billing.license.repository.LicenseRepository.class);
        licenseService = mock(LicenseService.class);
        redeemCodeService = mock(RedeemCodeService.class);
        emailService = mock(EmailNotificationService.class);
        amountValidator = mock(AmountValidator.class);
        paymentEventRepository = mock(PaymentEventRepository.class);
        checkoutService = mock(CheckoutService.class);
        subscriptionService = mock(SubscriptionService.class);

        controller = new WebhookController();
        setField(controller, "paymentServiceFactory", factory);
        setField(controller, "paymentService", paymentService);
        setField(controller, "orderRepository", orderRepository);
        setField(controller, "paymentRepository", paymentRepository);
        setField(controller, "licenseRepository", licenseRepository);
        setField(controller, "licenseService", licenseService);
        setField(controller, "redeemCodeService", redeemCodeService);
        setField(controller, "emailNotificationService", emailService);
        setField(controller, "amountValidator", amountValidator);
        setField(controller, "paymentEventRepository", paymentEventRepository);
        setField(controller, "checkoutService", checkoutService);
        setField(controller, "subscriptionService", subscriptionService);
        // B11：生产环境通过 @Lazy 自注入代理使 fulfillOrder 的 @Transactional 生效；
        // 单元测试手动构造，将 self 指向自身以触发真实 fulfillOrder 逻辑
        setField(controller, "self", controller);
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

    private PaymentStrategy stubStrategy(boolean signatureValid) {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(signatureValid);
        WebhookPayload payload = new WebhookPayload();
        payload.setOrderId("ORD-1");
        payload.setPaymentId("pay_1");
        payload.setTransactionId("txn_1");
        payload.setEventType("payment_success");
        payload.setStatus(PaymentStatus.SUCCESS.name());
        payload.setAmount(new BigDecimal("99.00"));
        payload.setCurrency("CNY");
        when(strategy.parseWebhookPayload(anyString())).thenReturn(payload);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        return strategy;
    }

    @Test
    void processWebhook_shouldReturn401_whenSignatureInvalid() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "bad-sig", Map.of());
        assertEquals(401, resp.getStatusCode().value());
        // B12：签名失败时不再写入 payment_events（避免 eventId="unknown-<ts>" 污染幂等表）
        verify(paymentEventRepository, never()).save(any(PaymentEvent.class));
    }

    @Test
    void processWebhook_shouldSkip_whenEventAlreadyProcessed() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(PaymentMethod.ALIPAY.name(), "txn_1"))
            .thenReturn(true);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("Already processed", resp.getBody());
        verify(paymentService, never()).updatePaymentStatus(any(), any(), any());
    }

    @Test
    void processWebhook_shouldFulfill_whenAmountValid() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(anyString(), anyString())).thenReturn(false);

        Order order = Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).email("u@e.com").build();
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(amountValidator.validateAmount(any(Order.class), any())).thenReturn(true);
        Payment payment = new Payment();
        // B2：updatePaymentStatus 现为 4 参（末位传业务订单号供回退定位）
        when(paymentService.updatePaymentStatus(any(), any(), any(), any())).thenReturn(payment);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("Success", resp.getBody());
        // 已绑定机器码 → 签发 License
        verify(licenseService).issueLicense("ORD-1", "M1");
        verify(emailService).sendPaymentSuccessEmail(eq("u@e.com"), anyString(), anyString(), anyDouble(), any());
        // 记录支付事件（已处理）
        verify(paymentEventRepository).save(argThat(e -> Boolean.TRUE.equals(e.getProcessed())));
    }

    /**
     * B2（资损修复）：支付记录定位失败（如 PayPal 存 Order ID、回调却是 capture ID）时，
     * updatePaymentStatus 返回 null 不再抛异常，发货必须照常按订单号进行。
     */
    @Test
    void processWebhook_shouldStillFulfill_whenPaymentRecordNotLocated() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(anyString(), anyString())).thenReturn(false);

        Order order = Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).email("u@e.com").build();
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(amountValidator.validateAmount(any(Order.class), any())).thenReturn(true);
        // 关键：定位失败返回 null（模拟 id 错配）
        when(paymentService.updatePaymentStatus(any(), any(), any(), any())).thenReturn(null);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        // 尽管 Payment 未定位，仍按订单号发货签发 License（不被 500 中断）
        verify(licenseService).issueLicense("ORD-1", "M1");
    }

    /**
     * B3（资损/欺诈修复）：渠道退款回调必须吊销该订单下 License 并置订单为已退款，
     * 且不再走发货路径。
     */
    @Test
    void processWebhook_shouldRevokeLicense_whenChannelRefunded() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(true);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.PAYPAL);
        WebhookPayload refund = new WebhookPayload();
        refund.setOrderId("ORD-9");
        refund.setPaymentId("cap_9");
        refund.setTransactionId("cap_9");
        refund.setEventType("PAYMENT.CAPTURE.REFUNDED");
        refund.setStatus(PaymentStatus.REFUNDED.name());
        when(strategy.parseWebhookPayload(anyString())).thenReturn(refund);
        when(factory.getStrategy(PaymentMethod.PAYPAL)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(anyString(), anyString())).thenReturn(false);
        when(paymentService.updatePaymentStatus(any(), any(), any(), any())).thenReturn(null);

        Order order = Order.builder().orderNumber("ORD-9").email("u@e.com").build();
        when(orderRepository.findByOrderNumber("ORD-9")).thenReturn(Optional.of(order));
        com.billing.license.entity.License lic = com.billing.license.entity.License.builder()
            .licenseKey("K-9").status(com.billing.license.entity.License.LicenseStatus.ACTIVE).build();
        when(licenseRepository.findByOrder(order)).thenReturn(java.util.List.of(lic));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.PAYPAL, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        // License 被吊销
        assertEquals(com.billing.license.entity.License.LicenseStatus.REVOKED, lic.getStatus());
        verify(licenseRepository).save(lic);
        // 未走发货
        verify(licenseService, never()).issueLicense(anyString(), anyString());
    }

    @Test
    void processWebhook_shouldReject_whenAmountMismatch() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(anyString(), anyString())).thenReturn(false);

        Order order = Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).build();
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(amountValidator.validateAmount(any(Order.class), any())).thenReturn(false);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(licenseService, never()).issueLicense(anyString(), anyString());
    }

    /**
     * C1 端点层覆盖：支付宝 sign 位于请求体参数中（不在 HTTP Header）。
     * 构造一个 header 取不到、body 参数取得到的场景，证明 fix 生效。
     */
    @Test
    void alipayWebhook_shouldReadSignFromBodyParams_notFromHeader() {
        // 真实可验签的串难以离线构造，这里验证「sign 来源」：
        // body 含 sign、header 放一个不同值；Controller 必须取 body 的，而非 header 的。
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        // 让 verifyWebhookSignature 把传进来的 signature 透传出来，供断言
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenAnswer(
            inv -> {
                String sig = inv.getArgument(1);
                // 仅当传入的是 body 里的 "real-sign"（而非 header 的 "header-sign"）才认为验签通过
                return "real-sign".equals(sig);
            });
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        // 避免 processWebhook 走到发货/幂等（需 ORDER/PAYMENT 等 stub），给一个非 SUCCESS 的最小 payload
        WebhookPayload parsed = new WebhookPayload();
        parsed.setOrderId("ORD-1");
        parsed.setPaymentId("pay_1");
        parsed.setEventType("payment_notify");
        parsed.setStatus(PaymentStatus.PENDING.name());
        parsed.setAmount(new BigDecimal("99.00"));
        parsed.setCurrency("CNY");
        when(strategy.parseWebhookPayload(anyString())).thenReturn(parsed);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);

        String body = "sign=real-sign&out_trade_no=ORD-1&trade_status=TRADE_SUCCESS";
        Map<String, String> headers = Map.of("sign", "header-sign"); // header 故意放错值

        ResponseEntity<String> resp = controller.alipayWebhook(body, headers);

        // 若 fix 生效：signature 来自 body="real-sign" → 验签通过 → 进后续逻辑（200/不 401）
        // 若 fix 失效（header.get("sign")）：传入 "header-sign" → 验签失败 → 401
        // 这里只关心「sign 来源」这一命题，断言不返回 401 即证明端点层取的是 body 的 sign
        assertNotEquals(401, resp.getStatusCode().value(),
                "端点层必须从 body 参数取 sign；若仍 401 说明仍在从 header 取");
        // 直接断言：验证时确实收到了 body 的 sign（证明端点层已从 body 取参，而非 header）
        verify(strategy).verifyWebhookSignature(anyString(), eq("real-sign"), any());
    }

    @Test
    void alipayWebhook_shouldPassNullSign_whenBodyHasNoSign() {
        // body 无 sign、header 也无 sign → 应透传 null 给验签（sign 恒为 null → 401）
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);

        String body = "out_trade_no=ORD-1&trade_status=TRADE_SUCCESS";
        Map<String, String> headers = Map.of();

        controller.alipayWebhook(body, headers);

        verify(strategy).verifyWebhookSignature(anyString(), isNull(), any());
    }

    /**
     * w3：微信端点必须透传 processWebhook 的真实返回码，不再恒返回 SUCCESS。
     * 验签失败时微信端应收到 401（而非 SUCCESS 让微信停止重试、事件静默丢失）。
     */
    @Test
    void wechatWebhook_shouldPropagate401_whenSignatureInvalid() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.WECHAT_PAY);
        when(factory.getStrategy(PaymentMethod.WECHAT_PAY)).thenReturn(strategy);

        ResponseEntity<String> resp = controller.wechatWebhook("sign=abc&out_trade_no=ORD-1", Map.of("Wechatpay-Signature", "abc"));
        assertEquals(401, resp.getStatusCode().value());
        assertTrue(resp.getBody() == null || !resp.getBody().contains("SUCCESS"));
    }

    @Test
    void wechatWebhook_shouldReturnSuccessBody_whenSignatureValid() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(true);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.WECHAT_PAY);
        WebhookPayload parsed = new WebhookPayload();
        parsed.setOrderId("ORD-1");
        parsed.setPaymentId("pay_1");
        parsed.setEventType("payment_success");
        parsed.setStatus(PaymentStatus.PENDING.name());
        parsed.setAmount(new BigDecimal("99.00"));
        parsed.setCurrency("CNY");
        when(strategy.parseWebhookPayload(anyString())).thenReturn(parsed);
        when(factory.getStrategy(PaymentMethod.WECHAT_PAY)).thenReturn(strategy);

        ResponseEntity<String> resp = controller.wechatWebhook("sign=abc&out_trade_no=ORD-1", Map.of("Wechatpay-Signature", "abc"));
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().contains("SUCCESS"));
    }
}
