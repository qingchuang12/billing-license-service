package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentEventType;
import com.billing.license.service.payment.strategy.PaymentLinkContext;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentProduct;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
        assertEquals(PaymentEventType.PAYMENT_CANCELLED, result.getEffectiveEventKind());
    }

    /**
     * 全额退款：trade_status=TRADE_CLOSED 且带 refund_fee/gmt_refund/gmt_close。
     * 必须落 REFUND_SUCCEEDED 撤权——若按 trade_status 走关单分支，License 永不吊销（资损）。
     */
    @Test
    void parseWebhookPayload_shouldTreatFullRefundNoticeAsRefund() {
        String payload = "notify_type=trade_status_sync&notify_id=n-1&out_trade_no=ORD-3&trade_no=t3"
            + "&trade_status=TRADE_CLOSED&total_amount=99.00&refund_fee=99.00"
            + "&gmt_refund=2026-10-28 10:00:00.123&gmt_close=2026-10-28 10:00:01";

        WebhookPayload result = strategy.parseWebhookPayload(payload);

        assertEquals(PaymentStatus.REFUNDED.name(), result.getStatus());
        assertEquals(PaymentEventType.REFUND_SUCCEEDED, result.getEffectiveEventKind());
        assertEquals(0, result.getRefundAmount().compareTo(new java.math.BigDecimal("99.00")));
        assertEquals("CNY", result.getRefundCurrency());
        assertTrue(result.isRefundAmountCumulative(), "refund_fee 是累计口径，必须标记");
        assertEquals("n-1", result.getWebhookEventId());
    }

    /**
     * 部分退款：trade_status 仍是 TRADE_SUCCESS，只有 refund_fee/gmt_refund 能识别。
     * 漏判会走「收款成功」分支 → 重复发货 + 重复记账（资损）。
     */
    @Test
    void parseWebhookPayload_shouldTreatPartialRefundNoticeAsRefundNotPayment() {
        String payload = "notify_type=trade_status_sync&notify_id=n-2&out_trade_no=ORD-4&trade_no=t4"
            + "&trade_status=TRADE_SUCCESS&total_amount=99.00&refund_fee=20.00"
            + "&gmt_refund=2026-10-28 10:00:00.123&out_biz_no=RF-REQ-1";

        WebhookPayload result = strategy.parseWebhookPayload(payload);

        assertEquals(PaymentStatus.REFUNDED.name(), result.getStatus());
        assertEquals(PaymentEventType.REFUND_SUCCEEDED, result.getEffectiveEventKind());
        assertEquals(0, result.getRefundAmount().compareTo(new java.math.BigDecimal("20.00")));
        assertTrue(result.isRefundAmountCumulative());
    }

    /** 部分退款缺 notify_id 时必须用 out_biz_no 做幂等键，否则多次退款的 trade_no 相同会互相去重吃掉。 */
    @Test
    void parseWebhookPayload_shouldFallbackToOutBizNo_whenRefundHasNoNotifyId() {
        String payload = "out_trade_no=ORD-5&trade_no=t5&trade_status=TRADE_SUCCESS&total_amount=99.00"
            + "&refund_fee=10.00&gmt_refund=2026-10-28 10:00:00.123&out_biz_no=RF-REQ-9";

        assertEquals("RF-REQ-9", strategy.parseWebhookPayload(payload).getWebhookEventId());
    }

    /** 退款失败通知不代表资金已退，只审计，不得触发撤权。 */
    @Test
    void parseWebhookPayload_shouldNotSettle_whenRefundFailed() {
        String payload = "notify_type=refund&notify_id=n-3&out_trade_no=ORD-6&trade_no=t6"
            + "&refund_status=REFUND_FAIL&refund_fee=20.00";

        WebhookPayload result = strategy.parseWebhookPayload(payload);

        assertEquals(PaymentEventType.OTHER, result.getEffectiveEventKind());
        assertNull(result.getRefundAmount());
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
        // 证书模式能验过其它应用的报文：app_id 必须与配置一致才放行
        setField(strategy, "appId", "2021000001");
        setField(strategy, "sellerId", "2088000000000001");

        String content = "app_id=2021000001&out_trade_no=ORD-TEST-001&seller_id=2088000000000001&total_amount=99.00&trade_status=TRADE_SUCCESS";
        String sign = signWithSha256WithRsa(content, keyPair.getPrivate(), "UTF-8");

        String payload = content + "&sign=" + java.net.URLEncoder.encode(sign, "UTF-8")
                + "&sign_type=RSA2";

        assertTrue(strategy.verifyWebhookSignature(payload, sign, Map.of()),
                "验签应成功：SDK 内部从 params 取 sign，业务侧不应提前 remove");
    }

    /**
     * 渠道超时口径：连接 5s、单次请求 10s（与其余四家共用一个常量），且客户端复用同一实例。
     * 不设超时时对端卡住会拖住请求线程与 DB 连接，这是五家渠道里唯一需要单独落的实现。
     */
    @Test
    void getAlipayClient_shouldApplySharedTimeoutAndReuseInstance() throws Exception {
        var kp = java.security.KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        var keyPair = kp.generateKeyPair();
        setField(strategy, "privateKey", toPkcs8(keyPair.getPrivate()));
        setField(strategy, "alipayPublicKey", toPkcs8(keyPair.getPublic()));
        setField(strategy, "appId", "2021000001");
        setField(strategy, "sellerId", "2088000000000001");
        setField(strategy, "signType", "RSA2");
        setField(strategy, "gatewayUrl", "https://openapi.alipay.com/gateway.do");

        com.alipay.api.AlipayClient first = strategy.getAlipayClient();
        com.alipay.api.AlipayClient second = strategy.getAlipayClient();

        assertSame(first, second, "客户端应复用同一实例，不再每次调用新建");
        java.lang.reflect.Field connectTimeout =
            com.alipay.api.AbstractAlipayClient.class.getDeclaredField("connectTimeout");
        connectTimeout.setAccessible(true);
        assertEquals(5000, connectTimeout.getInt(first), "连接超时应为 5s");
        java.lang.reflect.Field readTimeout =
            com.alipay.api.AbstractAlipayClient.class.getDeclaredField("readTimeout");
        readTimeout.setAccessible(true);
        assertEquals(10000, readTimeout.getInt(first), "单次请求超时应为 10s");
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSignTampered() throws Exception {
        var kp = java.security.KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        var keyPair = kp.generateKeyPair();

        setField(strategy, "privateKey", toPkcs8(keyPair.getPrivate()));
        setField(strategy, "alipayPublicKey", toPkcs8(keyPair.getPublic()));
        setField(strategy, "signType", "RSA2");
        // 证书模式能验过其它应用的报文：app_id 必须与配置一致才放行
        setField(strategy, "appId", "2021000001");
        setField(strategy, "sellerId", "2088000000000001");

        String content = "app_id=2021000001&out_trade_no=ORD-TEST-001&seller_id=2088000000000001&total_amount=99.00&trade_status=TRADE_SUCCESS";
        String sign = signWithSha256WithRsa(content, keyPair.getPrivate(), "UTF-8");
        String tampered = content.replace("99.00", "199.00") + "&sign_type=RSA2";
        String tamperedPayload = tampered + "&sign=" + java.net.URLEncoder.encode(sign, "UTF-8");

        assertFalse(strategy.verifyWebhookSignature(tamperedPayload, sign, Map.of()),
                "内容被篡改后验签必须失败");
    }

    /**
     * 证书自检：首张是国密 SM2 根证书时必须能解析。
     * JDK 默认 provider 在这里会抛 {@code Unknown named curve: 1.2.156.10197.1.301}，
     * 故本用例同时也是「自检改走 BouncyCastle」这条修复的回归闸门。
     */
    @Test
    void inspectCertificates_shouldParseSm2RootCertificate() {
        assertEquals(List.of(), strategy.inspectCertificates(testCert("certs/alipay-sm2-root.pem")),
            "SM2 根证书应能解析且未过期（有效期至 2042），不应产生任何问题项");
    }

    /**
     * 证书自检须遍历全部证书：链中第二张已过期（2024-08-01）也必须被检出。
     * 只取首张的旧写法会漏掉它——根证书文件有 4 张、支付宝公钥证书有 2 张。
     */
    @Test
    void inspectCertificates_shouldDetectExpiredCertBeyondTheFirstOne() {
        List<String> problems = strategy.inspectCertificates(testCert("certs/alipay-sm2-plus-expired.pem"));

        assertEquals(1, problems.size(), "SM2 那张未过期、第二张已过期，应恰有 1 条问题项");
        assertTrue(problems.get(0).contains("已过期"), problems.get(0));
        assertTrue(problems.get(0).contains("第 2 张"), "须指明是链中第几张，便于定位：" + problems.get(0));
    }

    // ===== 硬约束 31：网页支付必须返回可直接跳转的 URL，不得把 HTML 表单当跳转地址 =====

    private static com.billing.license.entity.Order cnyOrder() {
        return com.billing.license.entity.Order.builder()
            .orderNumber("ORD-ALI-1")
            .totalAmount(new java.math.BigDecimal("99.00"))
            .currency(com.billing.license.entity.Currency.CNY)
            .title("测试商品")
            .build();
    }

    /** 用子类替换网关客户端：只有替换掉真实 HTTP，才能验证「取 URL 而不是取表单」这条契约。 */
    private static AlipayStrategy strategyWith(com.alipay.api.AlipayClient client) {
        return new AlipayStrategy() {
            @Override
            protected com.alipay.api.AlipayClient getAlipayClient() {
                return client;
            }
        };
    }

    @Test
    void createPayment_webPage_shouldReturnJumpableUrlFromPageExecuteGet() throws Exception {
        com.alipay.api.AlipayClient client = mock(com.alipay.api.AlipayClient.class);
        com.alipay.api.response.AlipayTradePagePayResponse gatewayResponse =
            new com.alipay.api.response.AlipayTradePagePayResponse();
        gatewayResponse.setCode("10000");
        gatewayResponse.setBody("https://openapi.alipay.com/gateway.do?method=alipay.trade.page.pay&sign=x");
        doReturn(gatewayResponse).when(client).pageExecute(
            any(com.alipay.api.request.AlipayTradePagePayRequest.class), eq("GET"));

        PaymentResponse response = strategyWith(client).createPayment(cnyOrder(),
            new PaymentLinkContext("/account", null, PaymentProduct.REDIRECT));

        assertEquals(PaymentStatus.PENDING.name(), response.getStatus());
        assertEquals(gatewayResponse.getBody(), response.getRedirectUrl());
        // 必须走带 HTTP 方法的重载：无参 pageExecute 返回的是自动提交表单
        verify(client).pageExecute(any(com.alipay.api.request.AlipayTradePagePayRequest.class), eq("GET"));
    }

    @Test
    void createPayment_webPage_shouldFail_whenGatewayReturnsHtmlForm() throws Exception {
        com.alipay.api.AlipayClient client = mock(com.alipay.api.AlipayClient.class);
        com.alipay.api.response.AlipayTradePagePayResponse gatewayResponse =
            new com.alipay.api.response.AlipayTradePagePayResponse();
        gatewayResponse.setCode("10000");
        gatewayResponse.setBody("<form name=\"punchout_form\" action=\"https://openapi.alipay.com\"></form>");
        doReturn(gatewayResponse).when(client).pageExecute(
            any(com.alipay.api.request.AlipayTradePagePayRequest.class), eq("GET"));

        PaymentResponse response = strategyWith(client).createPayment(cnyOrder(),
            new PaymentLinkContext("/account", null, PaymentProduct.REDIRECT));

        assertEquals(PaymentStatus.FAILED.name(), response.getStatus());
        assertNull(response.getRedirectUrl(), "HTML 表单不能当跳转地址下发");
    }

    @Test
    void createPayment_wap_shouldReturnJumpableUrl() throws Exception {
        com.alipay.api.AlipayClient client = mock(com.alipay.api.AlipayClient.class);
        com.alipay.api.response.AlipayTradeWapPayResponse gatewayResponse =
            new com.alipay.api.response.AlipayTradeWapPayResponse();
        gatewayResponse.setCode("10000");
        gatewayResponse.setBody("https://openapi.alipay.com/gateway.do?method=alipay.trade.wap.pay&sign=x");
        doReturn(gatewayResponse).when(client).pageExecute(
            any(com.alipay.api.request.AlipayTradeWapPayRequest.class), eq("GET"));

        PaymentResponse response = strategyWith(client).createPayment(cnyOrder(),
            new PaymentLinkContext("/account", null, PaymentProduct.WAP));

        assertEquals(PaymentStatus.PENDING.name(), response.getStatus());
        assertEquals(gatewayResponse.getBody(), response.getRedirectUrl());
        verify(client).pageExecute(any(com.alipay.api.request.AlipayTradeWapPayRequest.class), eq("GET"));
    }

    @Test
    void createPayment_wapWithInstallment_shouldCarryHuabeiParams() throws Exception {
        com.alipay.api.AlipayClient client = mock(com.alipay.api.AlipayClient.class);
        com.alipay.api.response.AlipayTradeWapPayResponse gatewayResponse =
            new com.alipay.api.response.AlipayTradeWapPayResponse();
        gatewayResponse.setCode("10000");
        gatewayResponse.setBody("https://openapi.alipay.com/gateway.do?method=alipay.trade.wap.pay&sign=x");
        doReturn(gatewayResponse).when(client).pageExecute(
            any(com.alipay.api.request.AlipayTradeWapPayRequest.class), eq("GET"));

        PaymentResponse response = strategyWith(client).createPayment(cnyOrder(),
            new PaymentLinkContext("/account", null, PaymentProduct.WAP, 6));

        assertEquals(PaymentStatus.PENDING.name(), response.getStatus());
        org.mockito.ArgumentCaptor<com.alipay.api.request.AlipayTradeWapPayRequest> captor =
            org.mockito.ArgumentCaptor.forClass(com.alipay.api.request.AlipayTradeWapPayRequest.class);
        verify(client).pageExecute(captor.capture(), eq("GET"));
        assertTrue(captor.getValue().getBizContent().contains("\"hb_fq_num\":\"6\""));
    }

    private static File testCert(String classpath) {
        try {
            return new File(AlipayStrategyTest.class.getClassLoader().getResource(classpath).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("测试证书资源缺失：" + classpath, e);
        }
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
