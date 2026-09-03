package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
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
            // w10：未配置不再「跳过验签返回 true」，否则生产漏配等于零鉴权
            logger.error("Stripe webhook-secret 未配置，拒绝回调（需配置后重启）");
            return false;
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

            // B18：Stripe 事件 ID（evt_xxx）作为幂等去重键
            if (root.has("id")) {
                webhookPayload.setWebhookEventId(root.get("id").asText());
            }

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

                // B18：订阅相关事件
                if (eventType.startsWith("customer.subscription.")) {
                    // 订阅对象本身：id / status / current_period / metadata.order_id
                    if (obj.has("id")) {
                        webhookPayload.setSubscriptionId(obj.get("id").asText());
                    }
                    if (obj.has("metadata") && obj.get("metadata").has("order_id")) {
                        webhookPayload.setOrderId(obj.get("metadata").get("order_id").asText());
                    }
                    if (obj.has("current_period_start")) {
                        webhookPayload.setCurrentPeriodStart(parseEpoch(obj.get("current_period_start").asLong()));
                    }
                    if (obj.has("current_period_end")) {
                        webhookPayload.setCurrentPeriodEnd(parseEpoch(obj.get("current_period_end").asLong()));
                    }
                    String subStatus = obj.has("status") ? obj.get("status").asText() : "";
                    if ("active".equals(subStatus) || "trialing".equals(subStatus)) {
                        webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                    } else if ("past_due".equals(subStatus)) {
                        webhookPayload.setStatus("PAST_DUE");
                    } else if ("canceled".equals(subStatus) || "unpaid".equals(subStatus)) {
                        webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
                    } else {
                        webhookPayload.setStatus(PaymentStatus.PENDING.name());
                    }
                } else if ("invoice.paid".equals(eventType)) {
                    // 续费成功：invoice 关联 subscription；驱动 License 续期
                    if (obj.has("subscription")) {
                        webhookPayload.setSubscriptionId(obj.get("subscription").asText());
                    }
                    webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                } else if ("checkout.session.completed".equals(eventType) && obj.has("subscription")) {
                    // 订阅模式结账：mode=subscription，走订阅分支
                    webhookPayload.setSubscriptionId(obj.get("subscription").asText());
                    webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                } else if ("payment_intent.succeeded".equals(eventType) && obj.has("invoice")) {
                    // 订阅的发票支付：由 invoice.paid 驱动，这里置空避免一次性分支重复发货
                    webhookPayload.setOrderId(null);
                    webhookPayload.setStatus(PaymentStatus.PENDING.name());
                }
            }

            if (webhookPayload.getStatus() == null) {
                if ("checkout.session.completed".equals(eventType)
                        || "payment_intent.succeeded".equals(eventType)
                        || "checkout.session.async_payment_succeeded".equals(eventType)
                        || "invoice.paid".equals(eventType)) {
                    webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                } else if ("payment_intent.payment_failed".equals(eventType)) {
                    webhookPayload.setStatus(PaymentStatus.FAILED.name());
                } else {
                    webhookPayload.setStatus(PaymentStatus.PENDING.name());
                }
            }

            webhookPayload.setTimestamp(System.currentTimeMillis());
            logger.info("Stripe 回调解析成功：orderId={}, subId={}, status={}",
                webhookPayload.getOrderId(), webhookPayload.getSubscriptionId(), webhookPayload.getStatus());
        } catch (Exception e) {
            logger.error("Stripe 回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }

        return webhookPayload;
    }

    /**
     * B18：将 Stripe epoch 秒解析为 LocalDateTime（UTC）
     */
    private LocalDateTime parseEpoch(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * H5：Stripe 退款。paymentId 为 Checkout Session ID，需先取出其 PaymentIntent 再发起退款。
     * 未配置或失败返回 false（绝不谎报成功）；渠道标记 succeeded 才返回 true。
     */
    @Override
    public boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Stripe 未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return false;
        }
        try {
            initStripe();
            // paymentId 存储的是 Checkout Session ID（cs_...）；退款需指向底层 PaymentIntent
            String paymentIntentId = paymentId;
            if (paymentId != null && paymentId.startsWith("cs_")) {
                Session session = Session.retrieve(paymentId);
                paymentIntentId = session.getPaymentIntent();
            }
            if (paymentIntentId == null || paymentIntentId.isEmpty()) {
                logger.error("Stripe 无法解析 PaymentIntent：sessionId={}", paymentId);
                return false;
            }
            long unitAmount = amount.multiply(new BigDecimal("100"))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(paymentIntentId)
                    .setAmount(unitAmount)
                    .build();
            Refund refund = Refund.create(params);
            if (refund != null && "succeeded".equals(refund.getStatus())) {
                logger.info("Stripe 退款成功：orderNo={}, refundId={}", order.getOrderNo(), refund.getId());
                return true;
            }
            logger.error("Stripe 退款未成功：orderNo={}, status={}",
                    order.getOrderNo(), refund != null ? refund.getStatus() : "null");
            return false;
        } catch (Exception e) {
            logger.error("Stripe 退款异常：orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.STRIPE;
    }
}
