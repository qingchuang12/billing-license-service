package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PayPal 支付策略实现（国际钱包用户）
 * 使用 PayPal REST API 真实创建 Order，并调用 verify-webhook-signature 接口验签
 */
@Service
public class PayPalStrategy implements PaymentStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PayPalStrategy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payment.paypal.client-id:}")
    private String clientId;

    @Value("${payment.paypal.client-secret:}")
    private String clientSecret;

    @Value("${payment.paypal.environment:https://api-m.sandbox.paypal.com}")
    private String environment;

    @Value("${payment.paypal.webhook-id:}")
    private String webhookId;

    @Value("${payment.paypal.return-url:}")
    private String returnUrl;

    @Value("${payment.paypal.cancel-url:}")
    private String cancelUrl;

    private String getAccessToken() throws Exception {
        String auth = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(environment + "/v1/oauth2/token"))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = objectMapper.readTree(response.body());
        return json.get("access_token").asText();
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建 PayPal 支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("paypal_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.PAYPAL.name());

        try {
            String value = order.getAmount().setScale(2, RoundingMode.HALF_UP).toString();
            String accessToken = getAccessToken();

            Map<String, Object> amount = new HashMap<>();
            amount.put("currency_code", order.getCurrency());
            amount.put("value", value);

            Map<String, Object> purchaseUnit = new HashMap<>();
            purchaseUnit.put("reference_id", order.getOrderNo());
            purchaseUnit.put("description", order.getTitle() != null ? order.getTitle() : "License");
            purchaseUnit.put("amount", amount);

            Map<String, Object> appContext = new HashMap<>();
            appContext.put("return_url", returnUrl);
            appContext.put("cancel_url", cancelUrl);

            Map<String, Object> body = new HashMap<>();
            body.put("intent", "CAPTURE");
            body.put("purchase_units", List.of(purchaseUnit));
            body.put("application_context", appContext);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(environment + "/v2/checkout/orders"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=representation")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());

            if (result.has("id")) {
                String paypalOrderId = result.get("id").asText();
                response.setPaymentId(paypalOrderId);

                if (result.has("links")) {
                    for (JsonNode link : result.get("links")) {
                        if ("approve".equals(link.get("rel").asText())) {
                            response.setRedirectUrl(link.get("href").asText());
                        }
                    }
                }

                Map<String, Object> extraParams = new HashMap<>();
                extraParams.put("paypalOrderId", paypalOrderId);
                response.setExtraParams(extraParams);

                logger.info("PayPal Order 创建成功：orderId={}", paypalOrderId);
            } else {
                logger.error("PayPal 创建订单失败：{}", result.toString());
                response.setStatus(PaymentStatus.FAILED.name());
                response.setErrorMessage(result.toString());
            }
        } catch (Exception e) {
            logger.error("PayPal 创建支付失败", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage(e.getMessage());
        }

        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询 PayPal 支付状态：paymentId={}", paymentId);
        try {
            String accessToken = getAccessToken();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(environment + "/v2/checkout/orders/" + paymentId))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            if (result.has("status")) {
                String status = result.get("status").asText();
                // w9：仅 COMPLETED 视为成功；APPROVED 仅「用户批准、尚未捕获扣款」，回落 PENDING 避免未收款即发货
                if ("COMPLETED".equals(status)) {
                    return PaymentStatus.SUCCESS;
                }
            }
            return PaymentStatus.PENDING;
        } catch (Exception e) {
            logger.error("PayPal 查询失败", e);
            return PaymentStatus.UNKNOWN;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证 PayPal Webhook 签名");
        if (clientId == null || clientId.isEmpty() || webhookId == null || webhookId.isEmpty()) {
            // w10：未配置不再「跳过验签返回 true」，否则生产漏配等于零鉴权
            logger.error("PayPal 凭证/webhook-id 未配置，拒绝回调（需配置后重启）");
            return false;
        }
        try {
            String accessToken = getAccessToken();
            Map<String, Object> verifyBody = new HashMap<>();
            verifyBody.put("webhook_id", webhookId);
            verifyBody.put("transmission_id", headers.get("Paypal-Transmission-Id"));
            verifyBody.put("transmission_time", headers.get("Paypal-Transmission-Time"));
            verifyBody.put("cert_url", headers.get("Paypal-Cert-Url"));
            verifyBody.put("auth_algo", headers.get("Paypal-Auth-Algo"));
            verifyBody.put("transmission_sig", headers.get("Paypal-Transmission-Sig"));
            verifyBody.put("webhook_event", objectMapper.readValue(payload, Map.class));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(environment + "/v1/notifications/verify-webhook-signature"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(verifyBody)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());
            return "SUCCESS".equals(json.get("verification_status").asText());
        } catch (Exception e) {
            logger.error("PayPal 签名验证异常", e);
            return false;
        }
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析 PayPal Webhook 回调");
        WebhookPayload webhookPayload = new WebhookPayload();

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.has("event_type") ? root.get("event_type").asText() : "";
            webhookPayload.setEventType(eventType);

            JsonNode resource = root.has("resource") ? root.get("resource") : null;
            if (resource != null) {
                String paypalOrderId = resource.has("id") ? resource.get("id").asText() : null;
                String orderId = resource.has("reference_id") ? resource.get("reference_id").asText() : null;
                String captureId = null;
                if (resource.has("purchase_units")) {
                    JsonNode pu = resource.get("purchase_units").get(0);
                    if (pu.has("payments") && pu.get("payments").has("captures")) {
                        captureId = pu.get("payments").get("captures").get(0).get("id").asText();
                    }
                }

                webhookPayload.setOrderId(orderId);
                webhookPayload.setPaymentId(paypalOrderId);
                webhookPayload.setTransactionId(captureId != null ? captureId : paypalOrderId);

                if (resource.has("amount")) {
                    JsonNode amt = resource.get("amount");
                    webhookPayload.setAmount(new BigDecimal(amt.get("value").asText()));
                    webhookPayload.setCurrency(amt.has("currency_code") ? amt.get("currency_code").asText() : "USD");
                }
            }

            if ("PAYMENT.CAPTURE.COMPLETED".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
            } else if ("PAYMENT.CAPTURE.REFUNDED".equals(eventType) || "PAYMENT.CAPTURE.DENIED".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.REFUNDED.name());
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
            }

            webhookPayload.setTimestamp(System.currentTimeMillis());
            logger.info("PayPal 回调解析成功：orderId={}, status={}", webhookPayload.getOrderId(), webhookPayload.getStatus());
        } catch (Exception e) {
            logger.error("PayPal 回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }

        return webhookPayload;
    }

    /**
     * H5：PayPal 退款。paymentId 为 PayPal Order ID，需先查出 capture ID 再发起退款。
     * 未配置或失败返回 false（绝不谎报成功）；渠道标记 COMPLETED 才返回 true。
     */
    @Override
    public boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
            logger.warn("PayPal 未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return false;
        }
        try {
            String accessToken = getAccessToken();
            String captureId = findCaptureId(accessToken, paymentId);
            if (captureId == null) {
                logger.error("PayPal 无法解析 captureId：orderId={}", paymentId);
                return false;
            }
            String value = amount.setScale(2, RoundingMode.HALF_UP).toString();
            Map<String, Object> amt = new HashMap<>();
            amt.put("currency_code", order.getCurrency());
            amt.put("value", value);
            Map<String, Object> refundBody = new HashMap<>();
            refundBody.put("amount", amt);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(environment + "/v2/payments/captures/" + captureId + "/refund"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(refundBody)))
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            String status = result.has("status") ? result.get("status").asText() : "";
            if (result.has("id") && "COMPLETED".equals(status)) {
                logger.info("PayPal 退款成功：orderNo={}, refundId={}", order.getOrderNo(), result.get("id").asText());
                return true;
            }
            logger.error("PayPal 退款失败：orderNo={}, status={}, body={}", order.getOrderNo(), status, result.toString());
            return false;
        } catch (Exception e) {
            logger.error("PayPal 退款异常：orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }

    /**
     * 从 PayPal Order 中解析 capture ID（退款目标）
     */
    private String findCaptureId(String accessToken, String paypalOrderId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(environment + "/v2/checkout/orders/" + paypalOrderId))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode result = objectMapper.readTree(httpResponse.body());
        if (result.has("purchase_units")) {
            JsonNode pu = result.get("purchase_units").get(0);
            if (pu.has("payments") && pu.get("payments").has("captures")) {
                return pu.get("payments").get("captures").get(0).get("id").asText();
            }
        }
        return null;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.PAYPAL;
    }
}
