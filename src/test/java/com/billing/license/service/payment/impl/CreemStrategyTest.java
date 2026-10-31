package com.billing.license.service.payment.impl;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.RefundResult;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Creem 支付策略单元测试 - 覆盖 HMAC-SHA256 验签（原始 body 直签）、
 * 四类事件解析（金额小计口径 / 事件名 camelCase）与建单必带 creem_product_id。
 */
class CreemStrategyTest {

    private final CreemStrategy strategy = new CreemStrategy(HttpClient.newHttpClient());

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sign(String secret, String payload) throws Exception {
        // Creem 契约：creem-signature = HMAC-SHA256(原始请求体, secret) 的 hex，无时间戳拼接
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
        return bytesToHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private void setField(String name, Object value) throws Exception {
        var field = CreemStrategy.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(strategy, value);
    }

    @Test
    void getPaymentMethod_shouldReturnCreem() {
        assertEquals(PaymentMethod.CREEM, strategy.getPaymentMethod());
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSecretNotConfigured() {
        // 与 Paddle 同口径：未配置 webhook-secret 拒绝回调（漏配等于零鉴权）
        assertFalse(strategy.verifyWebhookSignature("{}", "sig", Map.of()));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenHeaderMissing() throws Exception {
        setField("webhookSecret", "whsec_test");
        assertFalse(strategy.verifyWebhookSignature("{}", null, Map.of()));
    }

    @Test
    void verifyWebhookSignature_shouldValidateRawBodyHmac() throws Exception {
        setField("webhookSecret", "whsec_test");

        String payload = "{\"id\":\"evt_1\",\"eventType\":\"checkout.completed\",\"object\":{}}";
        String signature = sign("whsec_test", payload);

        assertTrue(strategy.verifyWebhookSignature(payload, signature, Map.of()));
        // 头名大小写不敏感地兜底：从 headers 里取 creem-signature 也能验过
        assertTrue(strategy.verifyWebhookSignature(payload, null, Map.of("creem-signature", signature)));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenPayloadTampered() throws Exception {
        setField("webhookSecret", "whsec_test");

        String payload = "{\"id\":\"evt_1\",\"eventType\":\"checkout.completed\",\"object\":{}}";
        String signature = sign("whsec_test", payload); // 对原始 payload 签名

        String tampered = "{\"id\":\"evt_1\",\"eventType\":\"checkout.completed\","
                + "\"object\":{\"metadata\":{\"order_id\":\"evil\"}}}";
        assertFalse(strategy.verifyWebhookSignature(tampered, signature, Map.of()));
    }

    // ===== 建单：Creem 无 ad-hoc 价格，product_id 必带 =====

    private static Order orderWithProduct(Product product) {
        Order order = new Order();
        order.setOrderNumber("ORD-CREEM-1");
        order.setTitle("License");
        order.setTotalAmount(new BigDecimal("9.99"));
        order.setCurrency(Currency.USD);
        order.setOrderItems(List.of(OrderItem.builder().product(product).quantity(1).build()));
        return order;
    }

    @Test
    void buildCheckoutBody_shouldCarryProductIdRequestIdAndMetadata() {
        Product buyout = Product.builder()
                .sku("ai-tools-pro-buyout")
                .billingCycle(Product.BillingCycle.ONE_TIME)
                .creemProductId("prod_buyout_1")
                .build();

        Map<String, Object> body = strategy.buildCheckoutBody(orderWithProduct(buyout));

        assertEquals("prod_buyout_1", body.get("product_id"));
        // request_id 是 checkout.completed 回传订单号的首选字段
        assertEquals("ORD-CREEM-1", body.get("request_id"));
        // metadata.order_id 会被订阅对象继承，是 subscription.* 续费事件回查订单的唯一依据
        assertEquals("ORD-CREEM-1", ((Map<?, ?>) body.get("metadata")).get("order_id"));
    }

    @Test
    void buildCheckoutBody_shouldReject_whenProductIdMissing() {
        // 缺 creem_product_id 必须拒单——Creem 无 ad-hoc 内联价格，静默降级不存在
        Product buyout = Product.builder()
                .sku("ai-tools-pro-buyout")
                .billingCycle(Product.BillingCycle.ONE_TIME)
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> strategy.buildCheckoutBody(orderWithProduct(buyout)));
        assertEquals("CREEM_PRODUCT_ID_MISSING", ex.getErrorCode());
    }

    @Test
    void buildCheckoutBody_shouldUseRecurringProduct_whenSubscriptionCycle() {
        Product monthly = Product.builder()
                .sku("ai-tools-pro-monthly")
                .billingCycle(Product.BillingCycle.MONTHLY)
                .chargeMode(com.billing.license.entity.ChargeMode.AUTO_RECURRING)
                .creemProductId("prod_monthly_1")
                .build();

        Map<String, Object> body = strategy.buildCheckoutBody(orderWithProduct(monthly));
        assertEquals("prod_monthly_1", body.get("product_id"));
    }

    // ===== 回调解析 =====

    @Test
    void parseWebhookPayload_shouldParseCheckoutCompleted_oneTime() {
        // 金额取 order.amount（小计，分），永不读含税 amount_paid（硬约束 23）
        String payload = "{"
            + "\"id\":\"evt_1\","
            + "\"eventType\":\"checkout.completed\","
            + "\"created_at\":1728734327412,"
            + "\"object\":{"
            + "\"id\":\"ch_1\","
            + "\"object\":\"checkout\","
            + "\"request_id\":\"ORD-CREEM-1\","
            + "\"status\":\"completed\","
            + "\"order\":{"
            + "\"id\":\"ord_1\",\"amount\":1000,\"amount_paid\":1210,"
            + "\"currency\":\"USD\",\"status\":\"paid\",\"transaction\":\"tran_1\""
            + "},"
            + "\"customer\":{\"id\":\"cust_1\",\"email\":\"a@b.c\"}"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("ORD-CREEM-1", result.getOrderId());
        assertEquals("ch_1", result.getPaymentId());
        assertEquals("tran_1", result.getTransactionId());
        assertEquals("evt_1", result.getWebhookEventId());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("10.00")), "小计口径 1000 分 = 10.00");
        assertEquals("USD", result.getCurrency());
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
        assertNull(result.getSubscriptionId(), "一次性买断不得携带 subscriptionId");
    }

    @Test
    void parseWebhookPayload_shouldParseCheckoutCompleted_subscription() {
        // 订阅首充：checkout.completed 携带 subscription 子对象 → 走订阅分支（fulfill + 建订阅记录）
        String payload = "{"
            + "\"id\":\"evt_2\","
            + "\"eventType\":\"checkout.completed\","
            + "\"object\":{"
            + "\"id\":\"ch_2\","
            + "\"request_id\":\"ORD-CREEM-SUB\","
            + "\"status\":\"completed\","
            + "\"order\":{\"amount\":999,\"currency\":\"USD\",\"transaction\":\"tran_2\",\"status\":\"paid\"},"
            + "\"subscription\":{\"id\":\"sub_1\",\"status\":\"active\"}"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("ORD-CREEM-SUB", result.getOrderId());
        assertEquals("sub_1", result.getSubscriptionId());
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
    }

    @Test
    void parseWebhookPayload_shouldParseSubscriptionPaid_withPeriodAndAmount() {
        // 续费：订单号在 metadata（订阅对象继承 checkout metadata）；金额/币种在 last_transaction
        String payload = "{"
            + "\"id\":\"evt_3\","
            + "\"eventType\":\"subscription.paid\","
            + "\"object\":{"
            + "\"id\":\"sub_1\","
            + "\"object\":\"subscription\","
            + "\"status\":\"active\","
            + "\"metadata\":{\"order_id\":\"ORD-CREEM-SUB\"},"
            + "\"last_transaction\":{"
            + "\"id\":\"tran_3\",\"amount\":1000,\"amount_paid\":1210,\"currency\":\"USD\",\"status\":\"paid\""
            + "},"
            + "\"current_period_start_date\":\"2024-10-12T11:58:38.000Z\","
            + "\"current_period_end_date\":\"2024-11-12T11:58:38.000Z\""
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("sub_1", result.getSubscriptionId());
        assertEquals("ORD-CREEM-SUB", result.getOrderId());
        assertEquals("tran_3", result.getTransactionId());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("10.00")), "续费金额同样取小计");
        assertEquals("USD", result.getCurrency());
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
        assertEquals(LocalDateTime.of(2024, 10, 12, 11, 58, 38), result.getCurrentPeriodStart());
        assertEquals(LocalDateTime.of(2024, 11, 12, 11, 58, 38), result.getCurrentPeriodEnd());
    }

    @Test
    void parseWebhookPayload_shouldParseSubscriptionCanceled() {
        String payload = "{"
            + "\"id\":\"evt_4\","
            + "\"eventType\":\"subscription.canceled\","
            + "\"object\":{"
            + "\"id\":\"sub_1\","
            + "\"status\":\"canceled\","
            + "\"metadata\":{},"
            + "\"canceled_at\":\"2024-10-12T11:58:57.813Z\""
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("sub_1", result.getSubscriptionId());
        assertEquals(PaymentStatus.CANCELLED.name(), result.getStatus());
    }

    @Test
    void parseWebhookPayload_shouldParsePastDue() {
        String payload = "{"
            + "\"id\":\"evt_5\","
            + "\"eventType\":\"subscription.past_due\","
            + "\"object\":{\"id\":\"sub_1\",\"status\":\"past_due\",\"metadata\":{}}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("PAST_DUE", result.getStatus());
    }

    @Test
    void parseWebhookPayload_shouldParseRefundCreated_withoutSubscriptionId() {
        // 退款走订单级吊销腿：刻意不携带 subscriptionId，避免与 subscription.canceled 双路径同改一条订阅
        String payload = "{"
            + "\"id\":\"evt_6\","
            + "\"eventType\":\"refund.created\","
            + "\"object\":{"
            + "\"id\":\"ref_1\","
            + "\"status\":\"succeeded\","
            + "\"refund_amount\":1210,\"refund_currency\":\"USD\","
            + "\"transaction\":{\"id\":\"tran_3\",\"amount\":1000,\"amount_paid\":1210,\"status\":\"refunded\"}"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("REFUNDED", result.getStatus());
        assertEquals("tran_3", result.getTransactionId());
        assertNull(result.getSubscriptionId(), "退款事件不得进订阅处理分支");
    }

    @Test
    void parseWebhookPayload_shouldKeepPending_forUnknownEvents() {
        // dispute / credits 等未处理事件仅入幂等表审计，不得触发发货
        String payload = "{"
            + "\"id\":\"evt_7\","
            + "\"eventType\":\"dispute.created\","
            + "\"object\":{\"id\":\"disp_1\"}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals(PaymentStatus.PENDING.name(), result.getStatus());
    }

    // ===== 渠道能力边界 =====

    /** 起一个只回固定响应的本地桩：验证退款请求与响应分支走真实 HTTP，不 mock HttpClient。 */
    private com.sun.net.httpserver.HttpServer stub(int status, String body) throws java.io.IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        return server;
    }

    private void pointTo(com.sun.net.httpserver.HttpServer server) throws Exception {
        setField("environment", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private Order orderWithTotal(String total) {
        Order order = new Order();
        order.setOrderNumber("ORD-CREEM-1");
        order.setTotalAmount(new BigDecimal(total));
        order.setCurrency(Currency.USD);
        return order;
    }

    @Test
    void refund_shouldSucceed_onFullAmount() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"id\":\"ref_1\",\"status\":\"succeeded\"}");
        pointTo(server);
        try {
            RefundResult result = strategy.refund(orderWithTotal("10.00"), "tran_1", new BigDecimal("10.00"), "RF-1");
            assertEquals(RefundResult.Outcome.SUCCESS, result.outcome());
            assertEquals("ref_1", result.channelRefundId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refund_shouldReject_whenAmountBelowTotal() throws Exception {
        setField("apiKey", "test-key");
        // 渠道无部分退款能力：必须 REJECTED——UNSUPPORTED 会被 AdminService 降级成全额退款
        RefundResult result = strategy.refund(orderWithTotal("10.00"), "tran_1", new BigDecimal("3.00"), "RF-1");
        assertEquals(RefundResult.Outcome.REJECTED, result.outcome());
        assertTrue(result.message().contains("全额"));
    }

    @Test
    void refund_shouldReturnUnknown_whenChannelAcceptsAsynchronously() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"id\":\"ref_2\",\"status\":\"pending\"}");
        pointTo(server);
        try {
            RefundResult result = strategy.refund(orderWithTotal("10.00"), "tran_1", new BigDecimal("10.00"), "RF-1");
            // pending 不得当成功（钱还没到账），交给 queryRefund 反查
            assertEquals(RefundResult.Outcome.UNKNOWN, result.outcome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refund_shouldReject_whenChannelRefuses() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(400, "{\"status\":400,\"message\":[\"Transaction not found\"]}");
        pointTo(server);
        try {
            RefundResult result = strategy.refund(orderWithTotal("10.00"), "tran_1", new BigDecimal("10.00"), "RF-1");
            assertEquals(RefundResult.Outcome.REJECTED, result.outcome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refund_shouldReturnUnknown_whenNetworkFails() throws Exception {
        setField("apiKey", "test-key");
        setField("environment", "http://127.0.0.1:1/v1");
        RefundResult result = strategy.refund(orderWithTotal("10.00"), "tran_1", new BigDecimal("10.00"), "RF-1");
        // 网络异常无法判定渠道是否受理：只能未知，绝不能当失败重发
        assertEquals(RefundResult.Outcome.UNKNOWN, result.outcome());
    }

    @Test
    void refund_shouldReject_whenCheckoutIdCannotResolveTransaction() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(404, "{\"message\":[\"Checkout not found\"]}");
        pointTo(server);
        try {
            // 建单落库的是 checkout id（ch_），退款必须换到 tran_，换不到就不能发起
            RefundResult result = strategy.refund(orderWithTotal("10.00"), "ch_1", new BigDecimal("10.00"), "RF-1");
            assertEquals(RefundResult.Outcome.REJECTED, result.outcome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void queryRefund_shouldConfirm_whenTransactionRefunded() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"id\":\"tran_1\",\"status\":\"refunded\",\"refunded_amount\":1210}");
        pointTo(server);
        try {
            RefundResult result = strategy.queryRefund(orderWithTotal("10.00"), "tran_1", "RF-1");
            assertEquals(RefundResult.Outcome.SUCCESS, result.outcome());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lookupSubscription_shouldReturnEmpty_whenApiKeyMissing() {
        assertTrue(strategy.lookupSubscription("sub_1").isEmpty());
    }

    @Test
    void missingConfig_shouldListBothKeys() {
        List<String> missing = strategy.missingConfig();
        assertTrue(missing.contains("payment.creem.api-key"));
        assertTrue(missing.contains("payment.creem.webhook-secret"));
    }

    // ===== 目录价预检（防止 Creem 后台价与本地订单不一致 → 收钱后回调 400 不发货） =====

    private Product product(String billingTypeCycle, com.billing.license.entity.ChargeMode mode) {
        return Product.builder()
            .sku("ai-tools-pro")
            .billingCycle(Product.BillingCycle.valueOf(billingTypeCycle))
            .chargeMode(mode)
            .creemProductId("prod_1")
            .build();
    }

    @Test
    void validateProductMapping_acceptsMatchingCatalog() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"price\":1999,\"currency\":\"USD\",\"billing_type\":\"onetime\",\"billing_period\":\"once\"}");
        pointTo(server);
        try {
            strategy.validateProductMapping(orderWithTotal("19.99"),
                product("ONE_TIME", com.billing.license.entity.ChargeMode.ONE_TIME), "prod_1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validateProductMapping_rejectsPriceMismatch() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"price\":1998,\"currency\":\"USD\",\"billing_type\":\"onetime\"}");
        pointTo(server);
        try {
            BusinessException ex = assertThrows(BusinessException.class,
                () -> strategy.validateProductMapping(orderWithTotal("19.99"),
                    product("ONE_TIME", com.billing.license.entity.ChargeMode.ONE_TIME), "prod_1"));
            assertEquals("CREEM_PRICE_MISMATCH", ex.getErrorCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validateProductMapping_rejectsBillingTypeMismatch() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"price\":1999,\"currency\":\"USD\",\"billing_type\":\"recurring\",\"billing_period\":\"every-month\"}");
        pointTo(server);
        try {
            BusinessException ex = assertThrows(BusinessException.class,
                () -> strategy.validateProductMapping(orderWithTotal("19.99"),
                    product("ONE_TIME", com.billing.license.entity.ChargeMode.ONE_TIME), "prod_1"));
            assertEquals("CREEM_BILLING_TYPE_MISMATCH", ex.getErrorCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validateProductMapping_rejectsBillingPeriodMismatch() throws Exception {
        setField("apiKey", "test-key");
        var server = stub(200, "{\"price\":1999,\"currency\":\"USD\",\"billing_type\":\"recurring\",\"billing_period\":\"every-year\"}");
        pointTo(server);
        try {
            BusinessException ex = assertThrows(BusinessException.class,
                () -> strategy.validateProductMapping(orderWithTotal("19.99"),
                    product("MONTHLY", com.billing.license.entity.ChargeMode.AUTO_RECURRING), "prod_1"));
            assertEquals("CREEM_BILLING_PERIOD_MISMATCH", ex.getErrorCode());
        } finally {
            server.stop(0);
        }
    }
}
