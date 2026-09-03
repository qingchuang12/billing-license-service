package com.billing.license.service.payment.impl;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void queryPayment_shouldTreatApprovedAsPending_notSuccess() throws Exception {
        // w9：APPROVED 仅「用户批准、尚未捕获扣款」，不能视为 SUCCESS（否则未收款即发货）
        String body = "{\"status\":\"APPROVED\"}";
        HttpServer server = startServer(200, body);
        try {
            setEnv(strategy, "http://localhost:" + server.getAddress().getPort());
            setField(strategy, "clientId", "dummy");
            setField(strategy, "clientSecret", "dummy");
            assertEquals(PaymentStatus.PENDING, strategy.queryPayment("order-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void queryPayment_shouldTreatCompletedAsSuccess() throws Exception {
        String body = "{\"status\":\"COMPLETED\"}";
        HttpServer server = startServer(200, body);
        try {
            setEnv(strategy, "http://localhost:" + server.getAddress().getPort());
            setField(strategy, "clientId", "dummy");
            setField(strategy, "clientSecret", "dummy");
            assertEquals(PaymentStatus.SUCCESS, strategy.queryPayment("order-1"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        // queryPayment 先走 getAccessToken（/v1/oauth2/token），必须返回合法 token 否则链路中断
        server.createContext("/v1/oauth2/token", exchange -> {
            byte[] bytes = "{\"access_token\":\"test-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/v2/checkout/orders/order-1", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void setEnv(Object target, String value) throws Exception {
        Field f = target.getClass().getDeclaredField("environment");
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setField(Object target, String name, String value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenCredentialsNotConfigured() {
        // w10：未配置 client-id/webhook-id 不再「跳过验签返回 true」（漏配等于零鉴权）；缺失即拒绝
        assertFalse(strategy.verifyWebhookSignature("{}", "sig", Map.of()));
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
