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

    /**
     * C1 反向验证：用真实 RSA 密钥对自签自验。
     * 证明删掉业务侧 params.remove("sign") 后，SDK 能正确从 params 取 sign 完成验签。
     */
    @Test
    void verifyWebhookSignature_shouldReturnTrue_whenPayloadSignedByValidRsaKey() throws Exception {
        var kp = java.security.KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        var keyPair = kp.generateKeyPair();

        // 通过反射注入私钥(签名用)与公钥(验签用)，绕过 @Value 配置
        setField(strategy, "privateKey", toPkcs8(keyPair.getPrivate()));
        setField(strategy, "alipayPublicKey", toPkcs8(keyPair.getPublic()));
        setField(strategy, "signType", "RSA2");

        String content = "app_id=2021000001&out_trade_no=ORD-TEST-001&total_amount=99.00&trade_status=TRADE_SUCCESS";
        String sign = signWithSha256WithRsa(content, keyPair.getPrivate(), "UTF-8");

        String payload = content + "&sign=" + java.net.URLEncoder.encode(sign, "UTF-8")
                + "&sign_type=RSA2";

        assertTrue(strategy.verifyWebhookSignature(payload, sign, Map.of()),
                "验签应成功：SDK 内部从 params 取 sign，业务侧不应提前 remove");
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSignTampered() throws Exception {
        var kp = java.security.KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        var keyPair = kp.generateKeyPair();

        setField(strategy, "privateKey", toPkcs8(keyPair.getPrivate()));
        setField(strategy, "alipayPublicKey", toPkcs8(keyPair.getPublic()));
        setField(strategy, "signType", "RSA2");

        String content = "app_id=2021000001&out_trade_no=ORD-TEST-001&total_amount=99.00&trade_status=TRADE_SUCCESS";
        String sign = signWithSha256WithRsa(content, keyPair.getPrivate(), "UTF-8");
        String tampered = content.replace("99.00", "199.00") + "&sign_type=RSA2";
        String tamperedPayload = tampered + "&sign=" + java.net.URLEncoder.encode(sign, "UTF-8");

        assertFalse(strategy.verifyWebhookSignature(tamperedPayload, sign, Map.of()),
                "内容被篡改后验签必须失败");
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

    private static String toPkcs8(java.security.Key key) {
        return java.util.Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static String signWithSha256WithRsa(String content, java.security.PrivateKey privateKey, String charset)
            throws Exception {
        var sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(content.getBytes(charset));
        return java.util.Base64.getEncoder().encodeToString(sig.sign());
    }
}
