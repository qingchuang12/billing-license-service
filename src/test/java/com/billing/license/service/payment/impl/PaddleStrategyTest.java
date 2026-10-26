package com.billing.license.service.payment.impl;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
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
 * Paddle 支付策略单元测试 - 覆盖 HMAC-SHA256 验签（hex）、回调解析与金额单位（H7）。
 */
class PaddleStrategyTest {

    private final PaddleStrategy strategy = new PaddleStrategy(HttpClient.newHttpClient());

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String sign(String secret, String timestamp, String payload) throws Exception {
        String message = timestamp + ":" + payload;
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
        return bytesToHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void getPaymentMethod_shouldReturnPaddle() {
        assertEquals(PaymentMethod.PADDLE, strategy.getPaymentMethod());
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSecretNotConfigured() {
        // w10：未配置 webhook-secret 不再「跳过验签返回 true」（漏配等于零鉴权）；缺失即拒绝
        assertFalse(strategy.verifyWebhookSignature("{}", "sig", Map.of()));
    }

    @Test
    void verifyWebhookSignature_shouldValidateHmac() throws Exception {
        // Paddle v2 的 h1 为 HMAC-SHA256(ts:payload, secret) 的十六进制串（非 Base64）。
        // 旧实现误用 Base64 比较，导致所有合法回调被拒；此处用 hex 验证正确算法。
        String secret = "whsec_test";
        var field = PaddleStrategy.class.getDeclaredField("webhookSecret");
        field.setAccessible(true);
        field.set(strategy, secret);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String payload = "{\"event_type\":\"transaction.completed\"}";
        String h1 = sign(secret, timestamp, payload);

        Map<String, String> headers = Map.of("Paddle-Signature", "ts=" + timestamp + ";h1=" + h1);
        assertTrue(strategy.verifyWebhookSignature(payload, "ts=" + timestamp + ";h1=" + h1, headers));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenTimestampExpired() throws Exception {
        // w8：超 ±5min 的时间戳应被拒（重放防护）；h1 用正确 hex，确保走的是时间戳过期分支
        String secret = "whsec_test";
        var field = PaddleStrategy.class.getDeclaredField("webhookSecret");
        field.setAccessible(true);
        field.set(strategy, secret);

        long old = (System.currentTimeMillis() / 1000) - 600; // 10 分钟前
        String timestamp = String.valueOf(old);
        String payload = "{\"event_type\":\"transaction.completed\"}";
        String h1 = sign(secret, timestamp, payload);

        Map<String, String> headers = Map.of("Paddle-Signature", "ts=" + timestamp + ";h1=" + h1);
        assertFalse(strategy.verifyWebhookSignature(payload, "ts=" + timestamp + ";h1=" + h1, headers));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenH1Mismatch() throws Exception {
        // 篡改 payload 后 h1 不匹配应被拒（防篡改）
        String secret = "whsec_test";
        var field = PaddleStrategy.class.getDeclaredField("webhookSecret");
        field.setAccessible(true);
        field.set(strategy, secret);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String payload = "{\"event_type\":\"transaction.completed\"}";
        String h1 = sign(secret, timestamp, payload); // 对原始 payload 签名

        String tampered = "{\"event_type\":\"transaction.completed\",\"data\":{\"id\":\"evil\"}}";
        Map<String, String> headers = Map.of("Paddle-Signature", "ts=" + timestamp + ";h1=" + h1);
        assertFalse(strategy.verifyWebhookSignature(tampered, "ts=" + timestamp + ";h1=" + h1, headers));
    }

    @Test
    void createPayment_shouldSendAmountInMinorUnits() {
        // H7 回归：Paddle 出站金额必须是最小货币单位整数串（"999" = $9.99），而非 "9.99"
        Order order = new Order();
        order.setOrderNumber("ORD-PADDLE-1");
        order.setTitle("License");
        order.setTotalAmount(new BigDecimal("9.99"));
        order.setCurrency(Currency.USD);

        Map<String, Object> body = strategy.buildCreateTransactionBody(order);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, Object> price = (Map<String, Object>) items.get(0).get("price");
        // C2：出站字段为 snake_case（unit_price / currency_code）
        Map<String, Object> unitPrice = (Map<String, Object>) price.get("unit_price");

        assertEquals("999", unitPrice.get("amount"));
        assertEquals("USD", unitPrice.get("currency_code"));
        // 顶层 custom_data 与必填 tax_mode
        assertEquals("ORD-PADDLE-1", ((Map<?, ?>) body.get("custom_data")).get("order_id"));
        assertEquals("external", body.get("tax_mode"));
    }

    @Test
    void refundBody_shouldUseMinorUnits() {
        // H7 回归：退款金额同样走最小货币单位整数串
        Map<String, Object> refundBody = strategy.buildRefundBody(new BigDecimal("19.50"));
        assertEquals("1950", refundBody.get("amount"));
        assertEquals("管理员退款", refundBody.get("reason"));
    }

    @Test
    void parseWebhookPayload_shouldParseTransactionCompleted() {
        String payload = "{"
            + "\"event_type\":\"transaction.completed\","
            + "\"data\":{"
            + "\"id\":\"txn_123\","
            + "\"currency_code\":\"USD\","
            + "\"custom_data\":{\"order_id\":\"ORD-PADDLE-1\"},"
            + "\"details\":{\"totals\":{\"total\":\"1499\"}}"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("ORD-PADDLE-1", result.getOrderId());
        assertEquals("txn_123", result.getTransactionId());
        assertEquals("USD", result.getCurrency());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("14.99")));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
    }

    @Test
    void parseWebhookPayload_transactionBilled_withBillingPeriod_shouldSetCurrentPeriodEnd() {
        // A10（2026-09-24）：transaction.billed/completed 现也解析 current_billing_period，
        // 写入 currentPeriodEnd，使续期幂等基准对交易续费事件同样可用。
        String payload = "{"
            + "\"event_type\":\"transaction.billed\","
            + "\"data\":{"
            + "\"id\":\"txn_123\","
            + "\"subscription_id\":\"sub_abc\","
            + "\"custom_data\":{\"order_id\":\"ORD-PADDLE-1\"},"
            + "\"current_billing_period\":{"
            + "\"starts_at\":\"2023-01-01T00:00:00Z\","
            + "\"ends_at\":\"2023-02-01T00:00:00Z\""
            + "}"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
        assertEquals("sub_abc", result.getSubscriptionId());
        assertEquals(LocalDateTime.of(2023, 1, 1, 0, 0), result.getCurrentPeriodStart());
        assertEquals(LocalDateTime.of(2023, 2, 1, 0, 0), result.getCurrentPeriodEnd());
    }

    // ===== #25 收银台真订阅（Paddle 侧） =====

    private static Order orderWithProduct(Product product) {
        Order order = new Order();
        order.setOrderNumber("ORD-PADDLE-SUB");
        order.setTitle("Pro 月付");
        order.setTotalAmount(new BigDecimal("9.99"));
        order.setCurrency(Currency.USD);
        order.setOrderItems(List.of(OrderItem.builder().product(product).quantity(1).build()));
        return order;
    }

    @Test
    void buildCreateTransactionBody_shouldUseCatalogPriceId_whenRecurringCycle() {
        // #25：Paddle 只认 catalog price_id，带 price_id 的交易由 Paddle 自动建订阅；
        // 此时不得再带 ad-hoc price（两者互斥，同传会 422），也不再声明 tax_mode（订阅由 Paddle 计税）。
        Product monthly = Product.builder()
                .sku("ai-tools-pro-monthly")
                .billingCycle(Product.BillingCycle.MONTHLY)
                .paddlePriceId("pri_01h123")
                .build();

        Map<String, Object> body = strategy.buildCreateTransactionBody(orderWithProduct(monthly));
        Map<String, Object> item = ((List<Map<String, Object>>) body.get("items")).get(0);

        assertEquals("pri_01h123", item.get("price_id"));
        assertNull(item.get("price"), "订阅下单不得再带 ad-hoc price");
        assertNull(body.get("tax_mode"), "订阅由 Paddle 计税，不应声明 tax_mode");
        // custom_data.order_id 是后续 subscription.* 事件回查订单的唯一依据
        assertEquals("ORD-PADDLE-SUB", ((Map<?, ?>) body.get("custom_data")).get("order_id"));
    }

    @Test
    void buildCreateTransactionBody_shouldReject_whenRecurringCycleWithoutPriceId() {
        // #25：缺 catalog price id 必须拒单——静默回退 ad-hoc 会让用户付了钱却没有自动续费
        Product yearly = Product.builder()
                .sku("ai-tools-pro-yearly")
                .billingCycle(Product.BillingCycle.YEARLY)
                .build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> strategy.buildCreateTransactionBody(orderWithProduct(yearly)));
        assertEquals("PADDLE_PRICE_ID_MISSING", ex.getErrorCode());
    }

    @Test
    void buildCreateTransactionBody_shouldKeepAdHocPrice_whenOneTimeCycle() {
        // 一次性买断维持原行为：ad-hoc price + tax_mode=external
        Product buyout = Product.builder()
                .sku("ai-tools-pro-buyout")
                .billingCycle(Product.BillingCycle.ONE_TIME)
                .paddlePriceId("pri_unused")
                .build();

        Map<String, Object> body = strategy.buildCreateTransactionBody(orderWithProduct(buyout));
        Map<String, Object> item = ((List<Map<String, Object>>) body.get("items")).get(0);

        assertNull(item.get("price_id"), "一次性下单不得带 price_id");
        assertEquals("999", ((Map<?, ?>) ((Map<?, ?>) item.get("price")).get("unit_price")).get("amount"));
        assertEquals("external", body.get("tax_mode"));
    }

    @Test
    void resolveRecurringProduct_shouldIgnoreLifetimeAndNullItems() {
        // LIFETIME 属买断口径；无行项（老订单/直建订单）视为一次性，不进订阅分支
        assertNull(strategy.resolveRecurringProduct(orderWithProduct(
                Product.builder().billingCycle(Product.BillingCycle.LIFETIME).build())));

        Order noItems = new Order();
        noItems.setOrderNumber("ORD-NO-ITEM");
        noItems.setTotalAmount(new BigDecimal("1"));
        noItems.setCurrency(Currency.USD);
        assertNull(strategy.resolveRecurringProduct(noItems));
    }
}
