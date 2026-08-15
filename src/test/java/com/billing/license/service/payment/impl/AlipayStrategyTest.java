package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付宝支付策略单元测试 - 覆盖回调解析与验签分支（外围接口实现）
 */
class AlipayStrategyTest {

    private final AlipayStrategy strategy = new AlipayStrategy();

    @Test
    void getPaymentMethod_shouldReturnAlipay() {
        assertEquals(PaymentMethod.ALIPAY, strategy.getPaymentMethod());
    }

    @Test
    void parseWebhookPayload_shouldParseTradeSuccess() {
        String payload = "out_trade_no=ORD-TEST-001&trade_no=2023080812345678"
            + "&trade_status=TRADE_SUCCESS&total_amount=99.00&buyer_id=2088&gmt_payment=2023-08-08 12:00:00";

        WebhookPayload result = strategy.parseWebhookPayload(payload);

        assertEquals("ORD-TEST-001", result.getOrderId());
        assertEquals("alipay_ORD-TEST-001", result.getPaymentId());
        assertEquals("2023080812345678", result.getTransactionId());
        assertEquals("CNY", result.getCurrency());
        assertEquals(0, result.getAmount().compareTo(new java.math.BigDecimal("99.00")));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
    }

    @Test
    void parseWebhookPayload_shouldParseTradeClosed() {
        String payload = "out_trade_no=ORD-2&trade_no=t2&trade_status=TRADE_CLOSED&total_amount=10.00";
        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals(PaymentStatus.CANCELLED.name(), result.getStatus());
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSignatureEmpty() {
        assertFalse(strategy.verifyWebhookSignature("x=1", "", Map.of()));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenNoPublicKeyConfigured() {
        // 有签名但无公钥会抛异常，返回 false
        assertFalse(strategy.verifyWebhookSignature("x=1&sign=abc", "abc", Map.of()));
    }
}
