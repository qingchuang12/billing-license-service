package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stripe 支付策略单元测试 - 覆盖 Webhook 签名验证与回调解析（外围接口实现）
 */
class StripeStrategyTest {

    private final StripeStrategy strategy = new StripeStrategy();

    private Order buildOrder() {
        return Order.builder()
            .orderNumber("ORD-TEST-001")
            .totalAmount(java.math.BigDecimal.valueOf(14.99))
            .currency("USD")
            .title("Pro License")
            .build();
    }

    @Test
    void getPaymentMethod_shouldReturnStripe() {
        assertEquals(PaymentMethod.STRIPE, strategy.getPaymentMethod());
    }

    @Test
    void parseWebhookPayload_shouldParseCheckoutSessionCompleted() {
        String payload = "{"
            + "\"type\":\"checkout.session.completed\","
            + "\"data\":{\"object\":{"
            + "\"id\":\"cs_123\","
            + "\"payment_intent\":\"pi_123\","
            + "\"currency\":\"usd\","
            + "\"amount_total\":1499,"
            + "\"metadata\":{\"order_id\":\"ORD-TEST-001\"}"
            + "}}"
            + "}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);

        assertEquals("ORD-TEST-001", result.getOrderId());
        assertEquals("cs_123", result.getPaymentId());
        assertEquals("pi_123", result.getTransactionId());
        assertEquals("USD", result.getCurrency());
        assertEquals(0, result.getAmount().compareTo(java.math.BigDecimal.valueOf(14.99)));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
        assertEquals("checkout.session.completed", result.getEventType());
    }

    @Test
    void parseWebhookPayload_shouldParsePaymentIntentFailed() {
        String payload = "{"
            + "\"type\":\"payment_intent.payment_failed\","
            + "\"data\":{\"object\":{"
            + "\"id\":\"pi_xyz\",\"payment_intent\":\"pi_xyz\""
            + "}}"
            + "}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals(PaymentStatus.FAILED.name(), result.getStatus());
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSecretNotConfigured() {
        // w10：未配置 webhook-secret 不再「跳过验签返回 true」（否则漏配等于零鉴权）；
        // 缺失即拒绝回调，由启动期 ChannelConfigValidator fail-fast 提前暴露部署错误。
        Map<String, String> headers = Map.of();
        assertFalse(strategy.verifyWebhookSignature("{}", "sig", headers));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSignatureMissing() {
        // 注入 secret 后缺签名应失败；通过反射设置 webhookSecret
        try {
            var field = StripeStrategy.class.getDeclaredField("webhookSecret");
            field.setAccessible(true);
            field.set(strategy, "whsec_test_xxx");
        } catch (Exception ignored) {
            // 若无法设置则跳过
            return;
        }
        assertFalse(strategy.verifyWebhookSignature("{}", null, Map.of()));
    }
}
