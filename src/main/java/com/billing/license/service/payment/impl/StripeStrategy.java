package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Stripe 支付策略实现（国际信用卡/Apple Pay/Google Pay）
 * 使用 stripe-java SDK 真实创建 Checkout Session 并校验 Webhook 签名
 */
@Service
public class StripeStrategy implements PaymentStrategy {

    private static final Logger logger = LoggerFactory.getLogger(StripeStrategy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${payment.stripe.api-key:}")
    private String apiKey;

    @Value("${payment.stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${payment.stripe.success-url:}")
    private String successUrl;

    @Value("${payment.stripe.cancel-url:}")
    private String cancelUrl;

    private void initStripe() {
        if (apiKey != null && !apiKey.isEmpty()) {
            Stripe.apiKey = apiKey;
        }
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建 Stripe 支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("stripe_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.STRIPE.name());

        try {
            initStripe();

            long unitAmount = order.getAmount()
                    .multiply(new BigDecimal("100"))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(cancelUrl)
                    .putMetadata("order_id", order.getOrderNo())
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(order.getCurrency().toLowerCase())
                                    .setUnitAmount(unitAmount)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(order.getTitle() != null ? order.getTitle() : "License")
                                            .build())
                                    .build())
                            .build())
                    .build();

            Session session = Session.create(params);
            response.setPayUrl(session.getUrl());
            response.setPaymentId(session.getId());

            Map<String, Object> extraParams = new HashMap<>();
            extraParams.put("sessionId", session.getId());
            response.setExtraParams(extraParams);

            logger.info("Stripe Checkout Session 创建成功：url={}", session.getUrl());
        } catch (Exception e) {
            logger.error("Stripe 创建支付失败", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage(e.getMessage());
        }

        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询 Stripe 支付状态：paymentId={}", paymentId);
        try {
            initStripe();
            Session session = Session.retrieve(paymentId);
            if ("complete".equals(session.getStatus()) && "paid".equals(session.getPaymentStatus())) {
                return PaymentStatus.SUCCESS;
            }
            return PaymentStatus.PENDING;
        } catch (Exception e) {
            logger.error("Stripe 查询失败", e);
            return PaymentStatus.UNKNOWN;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证 Stripe Webhook 签名");
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            logger.warn("Stripe webhook-secret 未配置，开发环境跳过验签");
            return true;
        }
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        try {
            Webhook.constructEvent(payload, signature, webhookSecret);
            return true;
        } catch (SignatureVerificationException e) {
            logger.error("Stripe 签名验证失败", e);
            return false;
        } catch (Exception e) {
            logger.error("Stripe 签名验证异常", e);
            return false;
        }
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析 Stripe Webhook 回调");
        WebhookPayload webhookPayload = new WebhookPayload();

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.has("type") ? root.get("type").asText() : "";
            JsonNode data = root.has("data") ? root.get("data") : null;
            JsonNode obj = (data != null && data.has("object")) ? data.get("object") : null;

            webhookPayload.setEventType(eventType);

            if (obj != null) {
                String orderId = obj.has("metadata") && obj.get("metadata").has("order_id")
                        ? obj.get("metadata").get("order_id").asText() : null;
                String sessionId = obj.has("id") ? obj.get("id").asText() : null;
                String paymentIntent = obj.has("payment_intent") ? obj.get("payment_intent").asText() : null;

                webhookPayload.setOrderId(orderId);
                webhookPayload.setPaymentId(sessionId != null ? sessionId : paymentIntent);
                webhookPayload.setTransactionId(paymentIntent);
                webhookPayload.setCurrency(obj.has("currency") ? obj.get("currency").asText().toUpperCase() : "USD");

                if (obj.has("amount_total")) {
                    BigDecimal amount = new BigDecimal(obj.get("amount_total").asLong())
                            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                    webhookPayload.setAmount(amount);
                }
            }

            if ("checkout.session.completed".equals(eventType)
                    || "payment_intent.succeeded".equals(eventType)
                    || "checkout.session.async_payment_succeeded".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
            } else if ("payment_intent.payment_failed".equals(eventType)) {
                webhookPayload.setStatus(PaymentStatus.FAILED.name());
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
            }

            webhookPayload.setTimestamp(System.currentTimeMillis());
            logger.info("Stripe 回调解析成功：orderId={}, status={}", webhookPayload.getOrderId(), webhookPayload.getStatus());
        } catch (Exception e) {
            logger.error("Stripe 回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }

        return webhookPayload;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.STRIPE;
    }
}
