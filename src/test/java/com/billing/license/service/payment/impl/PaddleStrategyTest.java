package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paddle 支付策略单元测试 - 覆盖 HMAC-SHA256 验签（hex）、回调解析与金额单位（H7）。
 */
class PaddleStrategyTest {

    private final PaddleStrategy strategy = new PaddleStrategy();

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
        order.setCurrency("USD");

        Map<String, Object> body = strategy.buildCreateTransactionBody(order);
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, Object> price = (Map<String, Object>) items.get(0).get("price");
        Map<String, Object> unitPrice = (Map<String, Object>) price.get("unitPrice");

        assertEquals("999", unitPrice.get("amount"));
        assertEquals("USD", unitPrice.get("currencyCode"));
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
            + "\"currencyCode\":\"USD\","
            + "\"customData\":{\"order_id\":\"ORD-PADDLE-1\"},"
            + "\"details\":{\"totals\":{\"total\":\"1499\"}}"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("ORD-PADDLE-1", result.getOrderId());
        assertEquals("txn_123", result.getTransactionId());
        assertEquals("USD", result.getCurrency());
        assertEquals(0, result.getAmount().compareTo(new BigDecimal("14.99")));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
    }
}
