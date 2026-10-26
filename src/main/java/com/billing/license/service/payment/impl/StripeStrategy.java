package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

            // #25：订阅类周期（月/季/半年/年）走 SUBSCRIPTION 模式，由 Stripe 托管自动续费；
            // 一次性（ONE_TIME/LIFETIME）维持原 PAYMENT 模式。解析放在外层 try 内，
            // 解析失败即下单失败（不静默降级为一次性收款——那等于用户以为订阅却不续费）。
            Product recurringProduct = resolveRecurringProduct(order);

            SessionCreateParams params = buildCheckoutParams(order, recurringProduct);
            Session session = Session.create(params);
            response.setPayUrl(session.getUrl());
            response.setPaymentId(session.getId());

            Map<String, Object> extraParams = new HashMap<>();
            extraParams.put("sessionId", session.getId());
            extraParams.put("mode", recurringProduct != null ? "subscription" : "payment");
            response.setExtraParams(extraParams);

            logger.info("Stripe Checkout Session 创建成功：url={}, mode={}",
                    session.getUrl(), extraParams.get("mode"));
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
                } else if ("charge.refunded".equals(eventType)) {
                    // 退款事件：charge.refunded 携带 payment_intent 与 metadata.order_id，
                    // 映射 REFUNDED 触发 License 吊销（与管理端退款同口径）；orderId/transactionId 已在上方从 charge 解析。
                    webhookPayload.setStatus(PaymentStatus.REFUNDED.name());
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
     * #25：构建 Checkout Session 参数（包级可测，锁定「订阅周期 → SUBSCRIPTION 模式」分支）。
     *
     * @param order            订单（取金额/币种/标题/订单号）
     * @param recurringProduct 订阅类周期套餐；null 表示一次性买断
     */
    SessionCreateParams buildCheckoutParams(Order order, Product recurringProduct) {
        long unitAmount = order.getAmount()
                .multiply(new BigDecimal("100"))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        String currency = order.getCurrency().name().toLowerCase();
        String productName = order.getTitle() != null ? order.getTitle() : "License";

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setSuccessUrl(successUrl + "?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .putMetadata("order_id", order.getOrderNo());

        if (recurringProduct != null) {
            // 订阅模式：优先用套餐配置的 catalog price；未配置则按周期内联 recurring price_data
            SessionCreateParams.LineItem.Builder lineItem =
                    SessionCreateParams.LineItem.builder().setQuantity(1L);
            String priceId = recurringProduct.getStripePriceId();
            if (priceId != null && !priceId.isBlank()) {
                lineItem.setPrice(priceId.trim());
            } else {
                lineItem.setPriceData(recurringPriceData(currency, unitAmount,
                        productName, recurringProduct.getBillingCycle()));
            }
            builder.setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .addLineItem(lineItem.build())
                    // 订阅对象自带 metadata.order_id：后续 customer.subscription.* / invoice.paid
                    // 事件靠它回查订单续期，缺失则续费无法匹配到订单（SubscriptionService）
                    .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                            .putMetadata("order_id", order.getOrderNo())
                            .build());
            logger.info("Stripe 订阅模式下单：orderNo={}, cycle={}, priceId={}",
                    order.getOrderNo(), recurringProduct.getBillingCycle(),
                    priceId == null || priceId.isBlank() ? "(内联)" : priceId);
        } else {
            builder.setMode(SessionCreateParams.Mode.PAYMENT)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(currency)
                                    .setUnitAmount(unitAmount)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(productName)
                                            .build())
                                    .build())
                            .build());
        }
        return builder.build();
    }

    /**
     * #25：从订单行反查「订阅类周期」的套餐。
     *
     * <p>取第一个 {@code billingCycle.isRecurring()} 的行项；一次性买断（ONE_TIME/LIFETIME）
     * 或取不到行项时返回 null，由调用方走原一次性收款分支。
     */
    Product resolveRecurringProduct(Order order) {
        List<OrderItem> items = order.getOrderItems();
        if (items == null) {
            return null;
        }
        for (OrderItem item : items) {
            Product product = item.getProduct();
            if (product != null && product.getBillingCycle() != null
                    && product.getBillingCycle().isRecurring()) {
                return product;
            }
        }
        return null;
    }

    /**
     * #25：按计费周期内联构造 Stripe recurring price_data（套餐未配 stripe_price_id 时使用）。
     * 周期映射：MONTHLY=1 月 / QUARTERLY=3 月 / HALF_YEAR=6 月 / YEARLY=1 年。
     */
    private SessionCreateParams.LineItem.PriceData recurringPriceData(
            String currency, long unitAmount, String productName, Product.BillingCycle cycle) {
        SessionCreateParams.LineItem.PriceData.Recurring.Builder recurring =
                SessionCreateParams.LineItem.PriceData.Recurring.builder();
        switch (cycle) {
            case YEARLY -> recurring.setInterval(
                            SessionCreateParams.LineItem.PriceData.Recurring.Interval.YEAR)
                    .setIntervalCount(1L);
            case QUARTERLY -> recurring.setInterval(
                            SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
                    .setIntervalCount(3L);
            case HALF_YEAR -> recurring.setInterval(
                            SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
                    .setIntervalCount(6L);
            default -> recurring.setInterval(
                            SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
                    .setIntervalCount(1L);
        }
        return SessionCreateParams.LineItem.PriceData.builder()
                .setCurrency(currency)
                .setUnitAmount(unitAmount)
                .setRecurring(recurring.build())
                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName(productName)
                        .build())
                .build();
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

    /**
     * #26：按订阅 ID 向 Stripe 反查订阅，取回建单时写入的 {@code metadata.order_id}。
     * 查询失败 / 未配置 / 取不到 order_id 一律返回 empty（不抛异常），由调用方转人工告警。
     */
    @Override
    public Optional<SubscriptionLookup> lookupSubscription(String subscriptionId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Stripe 未配置，跳过订阅查询：subId={}", subscriptionId);
            return Optional.empty();
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        try {
            initStripe();
            com.stripe.model.Subscription sub = com.stripe.model.Subscription.retrieve(subscriptionId);
            if (sub == null) {
                return Optional.empty();
            }
            Map<String, String> metadata = sub.getMetadata();
            String orderId = (metadata == null) ? null : metadata.get("order_id");
            if (orderId == null || orderId.isBlank()) {
                logger.warn("Stripe 订阅未携带 order_id 元数据，无法自动重修：subId={}", subscriptionId);
                return Optional.empty();
            }
            String status = sub.getStatus();
            return Optional.of(SubscriptionLookup.builder()
                    .subscriptionId(subscriptionId)
                    .orderId(orderId)
                    .active("active".equals(status) || "trialing".equals(status))
                    .currentPeriodStart(sub.getCurrentPeriodStart() != null
                            ? parseEpoch(sub.getCurrentPeriodStart()) : null)
                    .currentPeriodEnd(sub.getCurrentPeriodEnd() != null
                            ? parseEpoch(sub.getCurrentPeriodEnd()) : null)
                    .build());
        } catch (Exception e) {
            logger.error("Stripe 订阅查询失败，转人工补录：subId={}", subscriptionId, e);
            return Optional.empty();
        }
    }

    /**
     * 订阅自助取消（期末生效）：置 Stripe 订阅的 {@code cancel_at_period_end=true}，
     * 用户已付到期的权益保留到 {@code current_period_end}，到期后由渠道发
     * {@code customer.subscription.deleted} 走 {@code SubscriptionService} 的期末失效链路。
     *
     * <p>只认渠道明确确认：{@code cancelAtPeriodEnd} 回读为 true 才算成功。
     * 未配置 / 订阅不存在 / 渠道报错一律返回 false（不抛），由调用方转成明确业务错误——
     * 谎报成功会让用户以为停了续费却仍被扣下一期。
     */
    @Override
    public boolean cancelSubscriptionAtPeriodEnd(String subscriptionId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Stripe 未配置，无法取消订阅：subId={}", subscriptionId);
            return false;
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return false;
        }
        try {
            initStripe();
            com.stripe.model.Subscription sub = com.stripe.model.Subscription.retrieve(subscriptionId);
            if (sub == null) {
                return false;
            }
            com.stripe.model.Subscription updated = sub.update(
                com.stripe.param.SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build());
            boolean confirmed = updated != null && Boolean.TRUE.equals(updated.getCancelAtPeriodEnd());
            if (!confirmed) {
                logger.error("Stripe 订阅取消未被渠道确认，不落库：subId={}", subscriptionId);
            }
            return confirmed;
        } catch (Exception e) {
            logger.error("Stripe 订阅取消失败：subId={}", subscriptionId, e);
            return false;
        }
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.STRIPE;
    }

    /**
     * Stripe 必备配置：API 密钥、Webhook 签名密钥、结账成功/取消回跳地址。
     * 缺 webhook-secret 会导致所有回调验签失败（付款成功但不发货）。
     */
    @Override
    public List<String> missingConfig() {
        List<String> missing = new ArrayList<>();
        if (apiKey == null || apiKey.isEmpty()) {
            missing.add("payment.stripe.api-key");
        }
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            missing.add("payment.stripe.webhook-secret");
        }
        if (successUrl == null || successUrl.isEmpty()) {
            missing.add("payment.stripe.success-url");
        }
        if (cancelUrl == null || cancelUrl.isEmpty()) {
            missing.add("payment.stripe.cancel-url");
        }
        return missing;
    }
}
