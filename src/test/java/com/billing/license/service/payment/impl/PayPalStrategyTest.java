package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PayPal 支付策略单元测试 - 覆盖回调解析与验签分支（外围接口实现）
 */
class PayPalStrategyTest {

    private final PayPalStrategy strategy = new PayPalStrategy();

    @Test
    void getPaymentMethod_shouldReturnPaypal() {
        assertEquals(PaymentMethod.PAYPAL, strategy.getPaymentMethod());
    }

    @Test
    void verifyWebhookSignature_shouldReturnTrue_whenCredentialsNotConfigured() {
        // 未配置 client-id/webhook-id 时开发模式跳过验签
        assertTrue(strategy.verifyWebhookSignature("{}", "sig", Map.of()));
    }

    @Test
    void parseWebhookPayload_shouldParseCaptureCompleted() {
        String payload = "{"
            + "\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\","
            + "\"resource\":{"
            + "\"id\":\"pay_123\","
            + "\"reference_id\":\"ORD-PP-1\","
            + "\"amount\":{\"value\":\"19.99\",\"currency_code\":\"USD\"},"
            + "\"purchase_units\":[{\"payments\":{\"captures\":[{\"id\":\"cap_1\"}]}}]"
            + "}}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals("ORD-PP-1", result.getOrderId());
        assertEquals("pay_123", result.getPaymentId());
        assertEquals("cap_1", result.getTransactionId());
        assertEquals("USD", result.getCurrency());
        assertEquals(0, result.getAmount().compareTo(new java.math.BigDecimal("19.99")));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
    }

    @Test
    void parseWebhookPayload_shouldParseCaptureRefunded() {
        String payload = "{"
            + "\"event_type\":\"PAYMENT.CAPTURE.REFUNDED\","
            + "\"resource\":{\"id\":\"pay_2\",\"reference_id\":\"ORD-PP-2\"}"
            + "}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals(PaymentStatus.REFUNDED.name(), result.getStatus());
    }
}
