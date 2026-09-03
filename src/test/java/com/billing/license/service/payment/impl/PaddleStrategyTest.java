package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paddle 支付策略单元测试 - 覆盖 HMAC-SHA256 验签与回调解析（外围接口实现）
 */
class PaddleStrategyTest {

    private final PaddleStrategy strategy = new PaddleStrategy();

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
        // 通过反射注入 webhookSecret，并构造合法签名（w8：时间戳须为当前 epoch 秒，±5min 内）
        String secret = "whsec_test";
        var field = PaddleStrategy.class.getDeclaredField("webhookSecret");
        field.setAccessible(true);
        field.set(strategy, secret);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String payload = "{\"event_type\":\"transaction.completed\"}";
        String message = timestamp + ":" + payload;
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> headers = Map.of(
            "Paddle-Timestamp", timestamp,
            "Paddle-Signature", "ts=" + timestamp + ";h1=" + expected
        );
        assertTrue(strategy.verifyWebhookSignature(payload, "ts=" + timestamp + ";h1=" + expected, headers));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenTimestampExpired() throws Exception {
        // w8：超 ±5min 的时间戳应被拒（重放防护）
        String secret = "whsec_test";
        var field = PaddleStrategy.class.getDeclaredField("webhookSecret");
        field.setAccessible(true);
        field.set(strategy, secret);

        long old = (System.currentTimeMillis() / 1000) - 600; // 10 分钟前
        String timestamp = String.valueOf(old);
        String payload = "{\"event_type\":\"transaction.completed\"}";
        String message = timestamp + ":" + payload;
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> headers = Map.of(
            "Paddle-Timestamp", timestamp,
            "Paddle-Signature", "ts=" + timestamp + ";h1=" + expected
        );
        assertFalse(strategy.verifyWebhookSignature(payload, "ts=" + timestamp + ";h1=" + expected, headers));
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
        assertEquals(0, result.getAmount().compareTo(new java.math.BigDecimal("14.99")));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
    }
}
