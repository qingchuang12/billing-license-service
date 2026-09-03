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
import java.time.LocalDateTime;
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
            // w10：未配置不再「跳过验签返回 true」，否则生产漏配等于零鉴权。
            // 由各渠道启动期 fail-fast（ChannelConfigValidator）提前暴露部署错误。
            logger.error("Paddle webhook-secret 未配置，拒绝回调（需配置后重启）");
            return false;
        }
        try {
            String timestamp = headers.get("Paddle-Timestamp");
            String paddleSignature = headers.get("Paddle-Signature"); // 格式：ts=...;h1=... 或 Base64(ts + ':' + hmac)
            if (timestamp == null || paddleSignature == null) {
                return false;
            }

            // w8：时间戳新鲜度校验（±5min 重放防护）
            try {
                long ts = Long.parseLong(timestamp);
                long nowSec = System.currentTimeMillis() / 1000;
                if (Math.abs(nowSec - ts) > 300) {
                    logger.error("Paddle 回调时间戳过期（重放风险）：ts={}, now={}", ts, nowSec);
                    return false;
                }
            } catch (NumberFormatException e) {
                logger.error("Paddle 回调时间戳格式非法：{}", timestamp);
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

            // B18：Webhook 投递事件 ID（Paddle event_id），用于幂等去重
            if (root.has("event_id")) {
                webhookPayload.setWebhookEventId(root.get("event_id").asText());
            }

            JsonNode data = root.has("data") ? root.get("data") : null;
            if (data != null) {
                String transactionId = data.has("id") ? data.get("id").asText() : null;
                webhookPayload.setPaymentId(transactionId);
                webhookPayload.setTransactionId(transactionId);

                // B18：交易若属于订阅，记录 subscription_id 以走订阅分支
                if (data.has("subscription_id")) {
                    webhookPayload.setSubscriptionId(data.get("subscription_id").asText());
                }

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

                // B18：订阅事件（subscription.*）解析订阅 ID 与周期
                if (eventType.startsWith("subscription.")) {
                    if (data.has("id")) {
                        webhookPayload.setSubscriptionId(data.get("id").asText());
                    }
                    if (data.has("customData") && data.get("customData").has("order_id")) {
                        webhookPayload.setOrderId(data.get("customData").get("order_id").asText());
                    }
                    if (data.has("current_period")) {
                        JsonNode period = data.get("current_period");
                        if (period.has("starts_at")) {
                            webhookPayload.setCurrentPeriodStart(parsePaddleDateTime(period.get("starts_at").asText()));
                        }
                        if (period.has("ends_at")) {
                            webhookPayload.setCurrentPeriodEnd(parsePaddleDateTime(period.get("ends_at").asText()));
                        }
                    }
                }
            }

            // 状态映射：交易事件与订阅事件分别处理
            if (eventType.startsWith("subscription.")) {
                // 订阅事件：依据 data.status 映射（active/trialing→SUCCESS，past_due→PAST_DUE，canceled→CANCELLED）
                String subStatus = (data != null && data.has("status")) ? data.get("status").asText() : "";
                if ("active".equals(subStatus) || "trialing".equals(subStatus)) {
                    webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                } else if ("past_due".equals(subStatus)) {
                    webhookPayload.setStatus("PAST_DUE");
                } else if ("canceled".equals(subStatus) || "subscription.canceled".equals(eventType)) {
                    webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
                } else {
                    webhookPayload.setStatus(PaymentStatus.PENDING.name());
                }
            } else if ("transaction.completed".equals(eventType) || "transaction.billed".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
            } else if ("transaction.canceled".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
            }

            webhookPayload.setTimestamp(Instant.now().toEpochMilli());
            logger.info("Paddle 回调解析成功：orderId={}, subId={}, status={}",
                webhookPayload.getOrderId(), webhookPayload.getSubscriptionId(), webhookPayload.getStatus());
        } catch (Exception e) {
            logger.error("Paddle 回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }

        return webhookPayload;
    }

    /**
     * B18：解析 Paddle ISO 时间（如 2023-01-01T00:00:00Z）为 LocalDateTime
     */
    private LocalDateTime parsePaddleDateTime(String value) {
        try {
            return Instant.parse(value).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            logger.warn("Paddle 时间解析失败：{}", value);
            return null;
        }
    }

    /**
     * H5：Paddle v2 退款（POST /transactions/{id}/refund）。
     * 未配置或失败返回 false（绝不谎报成功）；渠道返回 data 视为受理成功返回 true。
     * 注：Paddle 金额在 REST 接口以「主单位十进制字符串」表示（如 "10.00"）；
     * H7 要求用沙箱实测退款金额格式与结果映射，本实现为最佳努力、待联调确认。
     */
    @Override
    public boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Paddle 未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return false;
        }
        try {
            String txnId = (paymentId != null && paymentId.startsWith("paddle_"))
                    ? paymentId.substring("paddle_".length()) : paymentId;
            Map<String, Object> refundBody = new HashMap<>();
            refundBody.put("amount", amount.setScale(2, RoundingMode.HALF_UP).toString());
            refundBody.put("reason", "管理员退款");

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl("/transactions/" + txnId + "/refund")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(refundBody)))
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());

            if (result.has("data")) {
                logger.info("Paddle 退款受理成功：orderNo={}, txnId={}", order.getOrderNo(), txnId);
                return true;
            }
            logger.error("Paddle 退款失败：orderNo={}, body={}", order.getOrderNo(), result.toString());
            return false;
        } catch (Exception e) {
            logger.error("Paddle 退款异常：orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.PADDLE;
    }
}
