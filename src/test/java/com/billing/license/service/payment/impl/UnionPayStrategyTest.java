package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 云闪付/银联支付策略单元测试（外层接口占位实现）
 */
class UnionPayStrategyTest {

    private final UnionPayStrategy strategy = new UnionPayStrategy();

    @Test
    void getPaymentMethod_shouldReturnUnionpay() {
        assertEquals(PaymentMethod.UNIONPAY, strategy.getPaymentMethod());
    }

    @Test
    void verifyWebhookSignature_shouldReturnTrue_inDevMode() {
        // 当前为占位实现，开发模式跳过验签
        assertTrue(strategy.verifyWebhookSignature("x=1", "sig", Map.of()));
    }

    @Test
    void parseWebhookPayload_shouldReturnParsedStub() {
        WebhookPayload result = strategy.parseWebhookPayload("some-form-payload");
        assertNotNull(result.getStatus());
    }
}
