package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Paddle 支付策略实现（国际，Merchant of Record，适合卖软件 License）
 * 使用 Paddle v2 REST API 创建 Transaction，Webhook 使用 HMAC-SHA256 本地验签
 */
@Service
public class PaddleStrategy implements PaymentStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PaddleStrategy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String PADDLE_TRANSACTIONS_URL = "/transactions";

    @Value("${payment.paddle.vendor-id:}")
    private String vendorId;

    @Value("${payment.paddle.api-key:}")
    private String apiKey;

    @Value("${payment.paddle.environment:https://sandbox-api.paddle.com}")
    private String environment;

    @Value("${payment.paddle.webhook-secret:}")
    private String webhookSecret;

    private String buildUrl(String path) {
        String base = environment.endsWith("/") ? environment.substring(0, environment.length() - 1) : environment;
        return base + path;
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建 Paddle 支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("paddle_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.PADDLE.name());

        try {
            Map<String, Object> items = new HashMap<>();
            items.put("quantity", 1);
            items.put("price", new HashMap<String, Object>() {{
                put("description", order.getTitle() != null ? order.getTitle() : "License");
                put("unitPrice", new HashMap<String, Object>() {{
                    put("amount", order.getAmount().setScale(2, RoundingMode.HALF_UP).toString());
                    put("currencyCode", order.getCurrency());
                }});
            }});
            Map<String, Object> customData = new HashMap<>();
            customData.put("order_id", order.getOrderNo());
            Map<String, Object> body = new HashMap<>();
            body.put("items", java.util.List.of(items));
            body.put("customData", customData);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(PADDLE_TRANSACTIONS_URL)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());

            if (result.has("data")) {
                JsonNode data = result.get("data");
                String transactionId = data.get("id").asText();
                response.setPaymentId(transactionId);

                // 获取结账链接（checkout url）
                String checkoutUrl = data.has("checkout") && data.get("checkout").has("url")
                        ? data.get("checkout").get("url").asText() : null;
                if (checkoutUrl != null) {
                    response.setPayUrl(checkoutUrl);
                }

                Map<String, Object> extraParams = new HashMap<>();
                extraParams.put("transactionId", transactionId);
                extraParams.put("vendorId", vendorId);
                response.setExtraParams(extraParams);

                logger.info("Paddle Transaction 创建成功：txnId={}, url={}", transactionId, checkoutUrl);
            } else {
                logger.error("Paddle 创建交易失败：{}", result.toString());
                response.setStatus(PaymentStatus.FAILED.name());
                response.setErrorMessage(result.toString());
            }
        } catch (Exception e) {
            logger.error("Paddle 创建支付失败", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage(e.getMessage());
        }

        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询 Paddle 支付状态：paymentId={}", paymentId);
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(PADDLE_TRANSACTIONS_URL + "/" + paymentId)))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            if (result.has("data")) {
                String status = result.get("data").get("status").asText();
                if ("completed".equals(status) || "billed".equals(status)) {
                    return PaymentStatus.SUCCESS;
                }
                if ("canceled".equals(status) || "past_due".equals(status)) {
                    return PaymentStatus.CANCELLED;
                }
            }
            return PaymentStatus.PENDING;
        } catch (Exception e) {
            logger.error("Paddle 查询失败", e);
            return PaymentStatus.UNKNOWN;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证 Paddle Webhook 签名");
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            logger.warn("Paddle webhook-secret 未配置，开发环境跳过验签");
            return true;
        }
        try {
            String timestamp = headers.get("Paddle-Timestamp");
            String paddleSignature = headers.get("Paddle-Signature"); // 格式：ts=...;h1=... 或 Base64(ts + ':' + hmac)
            if (timestamp == null || paddleSignature == null) {
                return false;
            }

            // Paddle v2 推荐做法：timestamp + ':' + payload -> HMAC-SHA256(webhookSecret) -> Base64
            String message = timestamp + ":" + payload;
            Mac mac = Mac.getInstance("HMACSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(hash);

            // 兼容两种传入格式：纯 Base64 或 "ts=...;h1=..."
            if (paddleSignature.contains("h1=")) {
                String h1 = paddleSignature.substring(paddleSignature.indexOf("h1=") + 3).trim();
                return h1.equals(expected);
            }
            return paddleSignature.equals(expected);
        } catch (Exception e) {
            logger.error("Paddle 签名验证异常", e);
            return false;
        }
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析 Paddle Webhook 回调");
        WebhookPayload webhookPayload = new WebhookPayload();

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.has("event_type") ? root.get("event_type").asText() : "";
            webhookPayload.setEventType(eventType);

            JsonNode data = root.has("data") ? root.get("data") : null;
            if (data != null) {
                String transactionId = data.has("id") ? data.get("id").asText() : null;
                webhookPayload.setPaymentId(transactionId);
                webhookPayload.setTransactionId(transactionId);

                if (data.has("customData") && data.get("customData").has("order_id")) {
                    webhookPayload.setOrderId(data.get("customData").get("order_id").asText());
                }

                if (data.has("details") && data.get("details").has("totals")) {
                    JsonNode totals = data.get("details").get("totals");
                    if (totals.has("total")) {
                        // Paddle 金额单位为最小货币单位（分），且为字符串
                        BigDecimal amount = new BigDecimal(totals.get("total").asText())
                                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                        webhookPayload.setAmount(amount);
                    }
                }
                if (data.has("currencyCode")) {
                    webhookPayload.setCurrency(data.get("currencyCode").asText());
                }
            }

            if ("transaction.completed".equals(eventType) || "transaction.billed".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
            } else if ("transaction.canceled".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
            }

            webhookPayload.setTimestamp(Instant.now().toEpochMilli());
            logger.info("Paddle 回调解析成功：orderId={}, status={}", webhookPayload.getOrderId(), webhookPayload.getStatus());
        } catch (Exception e) {
            logger.error("Paddle 回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }

        return webhookPayload;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.PADDLE;
    }
}
