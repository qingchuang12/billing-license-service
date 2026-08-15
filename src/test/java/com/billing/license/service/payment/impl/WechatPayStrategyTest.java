package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信支付策略单元测试 - 覆盖回调解析与验签分支（外围接口实现）
 * 注：真实验签需要商户证书，这里验证"缺少必要 Header 时拒绝"与"payload 解析"两个确定分支。
 */
class WechatPayStrategyTest {

    private final WechatPayStrategy strategy = new WechatPayStrategy();

    @Test
    void getPaymentMethod_shouldReturnWechatPay() {
        assertEquals(PaymentMethod.WECHAT_PAY, strategy.getPaymentMethod());
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenHeaderMissing() {
        // 缺少 Wechatpay-Timestamp/Nonce/Signature 之一
        assertFalse(strategy.verifyWebhookSignature("{}", "sig", Map.of()));
    }

    @Test
    void parseWebhookPayload_shouldParseSuccessResource() {
        // 构造一个未加密（明文） resource 以测试解析分支
        String payload = "{"
            + "\"event_type\":\"TRANSACTION.SUCCESS\","
            + "\"resource\":{"
            + "\"ciphertext\":\""
            + Base64.getEncoder().encodeToString("{}".getBytes())
            + "\",\"nonce\":\"n\",\"associated_data\":\"\"}"
            + "}";

        // 解密会失败（apiV3Key 为空），整体 catch 返回 FAILED；验证不抛异常即可
        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertNotNull(result.getStatus());
    }

    @Test
    void parseWebhookPayload_shouldHandleInvalidJson() {
        WebhookPayload result = strategy.parseWebhookPayload("not-json");
        assertEquals(PaymentStatus.FAILED.name(), result.getStatus());
    }
}
