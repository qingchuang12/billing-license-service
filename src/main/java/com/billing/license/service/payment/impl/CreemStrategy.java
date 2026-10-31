package com.billing.license.service.payment.impl;

import com.billing.license.config.PaymentHttpClientConfig;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creem 支付策略实现（国际，Merchant of Record，具备订阅能力）。
 *
 * <p>渠道契约（官方 docs.creem.io，2026-10-28 核实）：
 * <ul>
 *   <li>鉴权：{@code x-api-key} 请求头（非 Bearer）。</li>
 *   <li>下单：{@code POST /v1/checkouts} 必带目录产品 {@code product_id}（{@code prod_} 前缀），
 *       渠道无 ad-hoc 内联价格——未配置即拒单（与 Paddle 只认目录价目同口径）。</li>
 *   <li>验签：{@code creem-signature} 头 = HMAC-SHA256(原始请求体, webhookSecret) 的 hex，
 *       无时间戳拼接（与 Paddle 的 {@code ts;h1} 格式不同）。</li>
 *   <li>金额：回调里 {@code order.amount} / {@code last_transaction.amount} 为**小计**（分）；
 *       {@code amount_paid} 是 MoR 含税总额，与本地订单金额错配——解析层永不读取。</li>
 *   <li>订阅：{@code subscription.*} 事件驱动续期/取消；反查 {@code GET /v1/subscriptions?subscription_id=}；
 *       期末取消 {@code POST /v1/subscriptions/{id}/cancel} body {@code {"mode":"scheduled"}}，
 *       以响应 {@code status=scheduled_cancel} 为确认。</li>
 *   <li>退款：{@code POST /v1/refunds} 仅凭 {@code transaction_id} 发起**全额**退款
 *       （{@code amount}/{@code reason} 均为非法属性，剩余可退额自动结清），无部分退款接口、
 *       无退款查询端点；异步受理返回 {@code pending}，结果靠 {@code refund.created} 回调，
 *       反查只能读交易详情的 {@code status}/{@code refunded_amount}。</li>
 * </ul>
 */
@Service
public class CreemStrategy implements PaymentStrategy {

    private static final Logger logger = LoggerFactory.getLogger(CreemStrategy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final BigDecimal MINOR_UNIT = new BigDecimal("100");

    @Value("${payment.creem.api-key:}")
    private String apiKey;

    @Value("${payment.creem.webhook-secret:}")
    private String webhookSecret;

    /** 默认**生产**网关（硬约束 8：仓库内不留测试网关默认值，测试只在 `.env` 显式配）。 */
    @Value("${payment.creem.environment:https://api.creem.io/v1}")
    private String environment;

    /** 支付完成回跳地址；留空则不传（用 Creem 产品后台配置的 default_success_url）。 */
    @Value("${payment.creem.success-url:}")
    private String successUrl;

    /**
     * 是否在下单前校验 Creem 目录价/币种/周期。默认 true；
     * 若启用 Creem 折扣、试用或按比例计费，目录价与实际扣款会不同，需关闭或改为自定义校验。
     */
    @Value("${payment.creem.validate-price:true}")
    private boolean validatePrice;

    /** #5：全渠道共享的 HttpClient（带连接超时），由 {@code PaymentHttpClientConfig} 提供。 */
    private final HttpClient httpClient;

    public CreemStrategy(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private String buildUrl(String path) {
        String base = environment.endsWith("/") ? environment.substring(0, environment.length() - 1) : environment;
        return base + path;
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        return createPayment(order, PaymentLinkContext.empty());
    }

    @Override
    public PaymentResponse createPayment(Order order, PaymentLinkContext context) {
        logger.info("创建 Creem 支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("creem_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.CREEM.name());

        try {
            // 目录价是渠道侧权威值：下单前先核对价格/币种/一次性或订阅类型，避免
            // 「用户按 Creem 后台价付款、本地订单金额不一致 → 回调金额校验 400 → 收钱不发货」。
            com.billing.license.entity.Product checkoutProduct = resolveCheckoutProduct(order);
            if (validatePrice && checkoutProduct != null && checkoutProduct.getCreemProductId() != null) {
                validateProductMapping(order, checkoutProduct, checkoutProduct.getCreemProductId());
            }
            Map<String, Object> body = buildCheckoutBody(order, context);

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/checkouts")))
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());

            if (result.hasNonNull("id") && result.hasNonNull("checkout_url")) {
                String checkoutId = result.get("id").asText();
                response.setPaymentId(checkoutId);
                response.setPayUrl(result.get("checkout_url").asText());
                Map<String, Object> extraParams = new HashMap<>();
                extraParams.put("checkoutId", checkoutId);
                response.setExtraParams(extraParams);
                logger.info("Creem Checkout 创建成功：checkoutId={}, url={}", checkoutId, response.getPayUrl());
            } else {
                logger.error("Creem 创建支付失败：{}", result.toString());
                response.setStatus(PaymentStatus.FAILED.name());
                response.setErrorMessage(result.toString());
            }
        } catch (Exception e) {
            logger.error("Creem 创建支付失败", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage(e.getMessage());
        }

        return response;
    }

    /**
     * 构建 Creem 下单请求体（提取为包级可测方法）。
     *
     * <p>订单号双写：{@code request_id} 是渠道建单追踪字段（会随 {@code checkout.completed} 原样带回）；
     * {@code metadata.order_id} 会被订阅对象继承，是后续 {@code subscription.*} 续费事件回查订单的
     * 唯一依据（与 Paddle {@code custom_data.order_id} 同理）。缺一不可。
     */
    Map<String, Object> buildCheckoutBody(Order order) {
        return buildCheckoutBody(order, PaymentLinkContext.empty());
    }

    Map<String, Object> buildCheckoutBody(Order order, PaymentLinkContext context) {
        com.billing.license.entity.Product recurringProduct = resolveRecurringProduct(order);
        String productId = recurringProduct != null
                ? recurringProduct.getCreemProductId() : resolveOneTimeProductId(order);
        if (productId == null || productId.isBlank()) {
            throw new BusinessException("CREEM_PRODUCT_ID_MISSING",
                    "套餐未配置 Creem 产品 ID（creem_product_id），无法走 Creem 渠道收款");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("product_id", productId.trim());
        body.put("request_id", order.getOrderNo());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("order_id", order.getOrderNo());
        body.put("metadata", metadata);
        // 产品级回跳优先（已由 CheckoutService 做同源/相对路径校验）；未提供才回落到部署级配置。
        String effectiveSuccessUrl = (context != null && context.successUrl() != null && !context.successUrl().isBlank())
                ? context.successUrl() : successUrl;
        if (effectiveSuccessUrl != null && !effectiveSuccessUrl.isBlank()) {
            body.put("success_url", effectiveSuccessUrl);
        }
        if (recurringProduct != null) {
            logger.info("Creem 订阅模式下单：orderNo={}, cycle={}, productId={}",
                    order.getOrderNo(), recurringProduct.getBillingCycle(), productId);
        }
        return body;
    }

    /** 买断套餐的 Creem 产品 ID：取首个订单行套餐的 creem_product_id。 */
    private String resolveOneTimeProductId(Order order) {
        com.billing.license.entity.Product product = resolveOneTimeProduct(order);
        return product != null ? product.getCreemProductId() : null;
    }

    private com.billing.license.entity.Product resolveOneTimeProduct(Order order) {
        List<com.billing.license.entity.OrderItem> items = order.getOrderItems();
        if (items == null) {
            return null;
        }
        for (com.billing.license.entity.OrderItem item : items) {
            com.billing.license.entity.Product product = item.getProduct();
            if (product != null && product.getCreemProductId() != null && !product.getCreemProductId().isBlank()) {
                return product;
            }
        }
        return null;
    }

    private com.billing.license.entity.Product resolveCheckoutProduct(Order order) {
        com.billing.license.entity.Product recurring = resolveRecurringProduct(order);
        return recurring != null ? recurring : resolveOneTimeProduct(order);
    }

    /**
     * 从订单行反查「订阅类周期」的套餐；一次性买断或取不到行项时返回 null。
     *
     * <p>必须同时看 {@code chargeMode}：历史配置允许 {@code ONE_TIME + MONTHLY}（一次性买 31 天），
     * 若只看 billingCycle 会把一次买断当订阅传到 Creem，造成持续扣款。
     */
    private com.billing.license.entity.Product resolveRecurringProduct(Order order) {
        List<com.billing.license.entity.OrderItem> items = order.getOrderItems();
        if (items == null) {
            return null;
        }
        for (com.billing.license.entity.OrderItem item : items) {
            com.billing.license.entity.Product product = item.getProduct();
            if (product != null
                    && product.getChargeMode() == com.billing.license.entity.ChargeMode.AUTO_RECURRING
                    && product.getBillingCycle() != null
                    && product.getBillingCycle().isRecurring()) {
                return product;
            }
        }
        return null;
    }

    void validateProductMapping(Order order, com.billing.license.entity.Product product, String productId) {
        JsonNode catalog = fetchProduct(productId);
        if (catalog == null) {
            throw new BusinessException("CREEM_PRODUCT_VERIFY_FAILED",
                "无法读取 Creem 产品目录，已拒单以防价格错配：productId=" + productId);
        }

        long expectedMinor = order.getAmount().movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long actualMinor = catalog.hasNonNull("price") ? catalog.get("price").asLong(-1) : -1;
        if (actualMinor != expectedMinor) {
            throw new BusinessException("CREEM_PRICE_MISMATCH",
                "Creem 产品价格与本地订单不一致：productId=" + productId
                    + ", creem=" + actualMinor + " 分, local=" + expectedMinor + " 分");
        }

        String currency = textOrNull(catalog.get("currency"));
        if (currency == null || order.getCurrency() == null
                || !currency.equalsIgnoreCase(order.getCurrency().code())) {
            throw new BusinessException("CREEM_CURRENCY_MISMATCH",
                "Creem 产品币种与本地订单不一致：productId=" + productId
                    + ", creem=" + currency + ", local="
                    + (order.getCurrency() != null ? order.getCurrency().code() : null));
        }

        boolean recurring = product.getChargeMode() == com.billing.license.entity.ChargeMode.AUTO_RECURRING;
        String billingType = textOrNull(catalog.get("billing_type"));
        if (recurring && !"recurring".equalsIgnoreCase(billingType)) {
            throw new BusinessException("CREEM_BILLING_TYPE_MISMATCH",
                "本地是自动续费套餐，但 Creem 产品不是订阅型：productId=" + productId
                    + ", billing_type=" + billingType);
        }
        if (!recurring && !"onetime".equalsIgnoreCase(billingType)) {
            throw new BusinessException("CREEM_BILLING_TYPE_MISMATCH",
                "本地是一次性套餐，但 Creem 产品是订阅型：productId=" + productId
                    + ", billing_type=" + billingType);
        }

        if (recurring) {
            String expectedPeriod = creemBillingPeriod(product.getBillingCycle());
            String actualPeriod = textOrNull(catalog.get("billing_period"));
            if (expectedPeriod != null && actualPeriod != null
                    && !expectedPeriod.equalsIgnoreCase(actualPeriod)) {
                throw new BusinessException("CREEM_BILLING_PERIOD_MISMATCH",
                    "Creem 产品周期与本地套餐不一致：productId=" + productId
                        + ", creem=" + actualPeriod + ", local=" + expectedPeriod);
            }
        }
    }

    private String creemBillingPeriod(com.billing.license.entity.Product.BillingCycle cycle) {
        if (cycle == null) {
            return null;
        }
        return switch (cycle) {
            case MONTHLY -> "every-month";
            case QUARTERLY -> "every-three-months";
            case HALF_YEAR -> "every-six-months";
            case YEARLY -> "every-year";
            case ONE_TIME, LIFETIME -> "once";
        };
    }

    private JsonNode fetchProduct(String productId) {
        try {
            String encoded = URLEncoder.encode(productId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                .uri(URI.create(buildUrl("/products/" + encoded)))
                .header("x-api-key", apiKey)
                .GET()
                .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                logger.error("Creem 产品目录读取失败：productId={}, status={}, body={}",
                    productId, httpResponse.statusCode(), httpResponse.body());
                return null;
            }
            return objectMapper.readTree(httpResponse.body());
        } catch (Exception e) {
            logger.error("Creem 产品目录读取异常：productId={}", productId, e);
            return null;
        }
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        return queryPaymentDetail(paymentId).status();
    }

    @Override
    public PaymentQueryResult queryPaymentDetail(String paymentId) {
        logger.info("查询 Creem 支付状态：paymentId={}", paymentId);
        if (paymentId == null || paymentId.isBlank() || paymentId.startsWith("creem_")) {
            // 建单失败时 Payment 行持有的是本地占位号（creem_<orderNo>），渠道侧无从查询
            return PaymentQueryResult.statusOnly(PaymentStatus.UNKNOWN);
        }
        try {
            String encoded = URLEncoder.encode(paymentId.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/checkouts?checkout_id=" + encoded)))
                    .header("x-api-key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            String status = result.hasNonNull("status") ? result.get("status").asText() : "";
            PaymentStatus paymentStatus;
            if ("completed".equals(status)) {
                paymentStatus = PaymentStatus.SUCCESS;
            } else if ("canceled".equals(status)) {
                paymentStatus = PaymentStatus.CANCELLED;
            } else {
                paymentStatus = PaymentStatus.PENDING;
            }
            BigDecimal amount = null;
            String currency = null;
            String transactionId = null;
            String orderReference = textOrNull(result.get("request_id"));
            JsonNode orderNode = result.has("order") && result.get("order").isObject()
                ? result.get("order") : null;
            if (orderNode != null) {
                amount = minorToMajor(orderNode.get("amount"));
                currency = textOrNull(orderNode.get("currency"));
                transactionId = textOrNull(orderNode.get("transaction"));
            }
            if (orderReference == null && result.has("metadata")
                    && result.get("metadata").hasNonNull("order_id")) {
                orderReference = result.get("metadata").get("order_id").asText();
            }
            if (orderReference == null && result.has("checkout")
                    && result.get("checkout").hasNonNull("request_id")) {
                orderReference = result.get("checkout").get("request_id").asText();
            }
            boolean verified = amount != null && currency != null && orderReference != null;
            return new PaymentQueryResult(paymentStatus, amount, currency, transactionId,
                orderReference, verified);
        } catch (Exception e) {
            logger.error("Creem 查询失败", e);
            return PaymentQueryResult.statusOnly(PaymentStatus.UNKNOWN);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            // 与 Paddle 同口径：未配置拒绝回调，由 ChannelConfigValidator 启动期提前暴露部署错误
            logger.error("Creem webhook-secret 未配置，拒绝回调（需配置后重启）");
            return false;
        }
        String sigHeader = (signature != null && !signature.isEmpty()) ? signature
                : (headers != null ? headers.get("creem-signature") : null);
        if (sigHeader == null || sigHeader.isEmpty()) {
            logger.error("creem-signature 头缺失，拒绝回调");
            return false;
        }
        try {
            // creem-signature = HMAC-SHA256(原始 body, secret) 的 hex；MessageDigest.isEqual 为常量时间比较
            Mac mac = Mac.getInstance("HMACSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(computed, hexToBytes(sigHeader.trim()));
        } catch (Exception e) {
            logger.error("Creem 签名验证异常", e);
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析 Creem Webhook 回调");
        WebhookPayload webhookPayload = new WebhookPayload();

        try {
            JsonNode root = objectMapper.readTree(payload);
            // Creem 事件名为 camelCase（eventType），与 Paddle 的 event_type 不同
            String eventType = root.hasNonNull("eventType") ? root.get("eventType").asText() : "";
            webhookPayload.setEventType(eventType);
            webhookPayload.setWebhookEventId(root.hasNonNull("id") ? root.get("id").asText() : null);
            webhookPayload.setPaymentId(webhookPayload.getWebhookEventId());

            JsonNode data = root.has("object") ? root.get("object") : null;
            if (data == null) {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
                webhookPayload.setTimestamp(Instant.now().toEpochMilli());
                return webhookPayload;
            }

            switch (eventType) {
                case "checkout.completed" -> parseCheckoutCompleted(data, webhookPayload);
                case "refund.created" -> parseRefundCreated(data, webhookPayload);
                default -> {
                    if (eventType.startsWith("subscription.")) {
                        parseSubscriptionEvent(data, webhookPayload);
                    } else {
                        // 未知事件（dispute.* / credits.* 等）仅入幂等表审计，不发货
                        logger.info("Creem 未处理的事件类型：{}", eventType);
                        webhookPayload.setStatus(PaymentStatus.PENDING.name());
                    }
                }
            }

            webhookPayload.setTimestamp(Instant.now().toEpochMilli());
            logger.info("Creem 回调解析完成：orderId={}, subId={}, status={}",
                webhookPayload.getOrderId(), webhookPayload.getSubscriptionId(), webhookPayload.getStatus());
        } catch (Exception e) {
            logger.error("Creem 回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }

        return webhookPayload;
    }

    /**
     * {@code checkout.completed}：object 为 checkout 对象。
     * 金额取 {@code order.amount}（小计）；{@code amount_paid} 为 MoR 含税总额，永不参与校验。
     */
    private void parseCheckoutCompleted(JsonNode data, WebhookPayload payload) {
        payload.setPaymentId(data.hasNonNull("id") ? data.get("id").asText() : null);

        JsonNode order = data.has("order") ? data.get("order") : null;
        if (order != null) {
            payload.setTransactionId(order.hasNonNull("transaction") ? order.get("transaction").asText() : null);
            payload.setAmount(minorToMajor(order.get("amount")));
            payload.setCurrency(textOrNull(order.get("currency")));
        }

        // 订单号：request_id 首选（建单时写入），metadata.order_id 兜底（订阅对象继承）
        String orderId = textOrNull(data.get("request_id"));
        if (orderId == null && data.has("metadata") && data.get("metadata").hasNonNull("order_id")) {
            orderId = data.get("metadata").get("order_id").asText();
        }
        payload.setOrderId(orderId);

        if (data.has("subscription") && data.get("subscription").hasNonNull("id")) {
            payload.setSubscriptionId(data.get("subscription").get("id").asText());
            // 首充周期：订阅对象自带 current_period_*，首绑必须按它校准，不能只按套餐天数。
            JsonNode subscription = data.get("subscription");
            payload.setCurrentPeriodStart(parseCreemDateTime(textOrNull(subscription.get("current_period_start_date"))));
            payload.setCurrentPeriodEnd(parseCreemDateTime(textOrNull(subscription.get("current_period_end_date"))));
        }

        boolean completed = "completed".equals(textOrNull(data.get("status")));
        payload.setStatus(completed ? PaymentStatus.SUCCESS.name() : PaymentStatus.PENDING.name());
        payload.setEventKind(completed
                ? (payload.getSubscriptionId() != null
                    ? PaymentEventType.SUBSCRIPTION_PAYMENT : PaymentEventType.PAYMENT_SUCCEEDED)
                : PaymentEventType.OTHER);
    }

    /** {@code subscription.*} 事件：object 为 subscription 对象（续费金额在 last_transaction）。 */
    private void parseSubscriptionEvent(JsonNode data, WebhookPayload payload) {
        payload.setSubscriptionId(textOrNull(data.get("id")));

        if (data.has("metadata") && data.get("metadata").hasNonNull("order_id")) {
            payload.setOrderId(data.get("metadata").get("order_id").asText());
        }

        JsonNode lastTxn = data.has("last_transaction") && data.get("last_transaction").isObject()
                ? data.get("last_transaction") : null;
        if (lastTxn != null) {
            payload.setTransactionId(textOrNull(lastTxn.get("id")));
            payload.setAmount(minorToMajor(lastTxn.get("amount")));
            payload.setCurrency(textOrNull(lastTxn.get("currency")));
        }
        if (payload.getTransactionId() == null && data.hasNonNull("last_transaction_id")) {
            payload.setTransactionId(data.get("last_transaction_id").asText());
        }

        payload.setCurrentPeriodStart(parseCreemDateTime(textOrNull(data.get("current_period_start_date"))));
        payload.setCurrentPeriodEnd(parseCreemDateTime(textOrNull(data.get("current_period_end_date"))));

        // 取消安排：Creem 用 canceled_at 表达（无 cancel_at_period_end 字段）。
        // 只在「取消安排」与「状态同步」两类事件上作结论：付费事件不管这个标记，
        // 取消/到期事件已各自有终态分支，不需要再判。
        Boolean cancelScheduled = textOrNull(data.get("canceled_at")) != null;

        // 事件语义优先于 object.status：expired 事件的 status 仍可能写着 active，
        // scheduled_cancel 也不是不可恢复的终态。
        String eventType = payload.getEventType() != null ? payload.getEventType() : "";
        String status = textOrNull(data.get("status"));
        switch (eventType) {
            case "subscription.paid" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_PAYMENT);
                payload.setStatus(PaymentStatus.SUCCESS.name());
            }
            case "subscription.trialing" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_TRIALING);
                payload.setStatus(PaymentStatus.PENDING.name());
            }
            case "subscription.past_due" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_PAST_DUE);
                payload.setStatus("PAST_DUE");
            }
            case "subscription.paused" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_PAUSED);
                payload.setStatus(PaymentStatus.PENDING.name());
            }
            case "subscription.scheduled_cancel" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_SCHEDULED_CANCEL);
                payload.setStatus(PaymentStatus.PENDING.name());
                payload.setCancelScheduled(true);
            }
            case "subscription.canceled" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_CANCELED);
                payload.setStatus(PaymentStatus.CANCELLED.name());
            }
            case "subscription.expired" -> {
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_EXPIRED);
                payload.setStatus("EXPIRED");
            }
            default -> {
                // subscription.active / subscription.update(d) 等只做同步，绝不据此续期。
                // 但取消安排是可逆的：用户调 resume 撤销期末取消后，渠道只发这类同步事件，
                // 故把 canceled_at 的现状一并带出，交给订阅服务决定是否复位本地标记。
                payload.setEventKind(PaymentEventType.SUBSCRIPTION_ACTIVE);
                payload.setStatus(PaymentStatus.PENDING.name());
                payload.setCancelScheduled(cancelScheduled);
            }
        }
        if (payload.getEventKind() == PaymentEventType.SUBSCRIPTION_ACTIVE && "canceled".equals(status)) {
            payload.setEventKind(PaymentEventType.SUBSCRIPTION_CANCELED);
            payload.setStatus(PaymentStatus.CANCELLED.name());
        }
    }

    /**
     * {@code refund.created}：object 为 refund 对象。
     *
     * <p>官方示例包含 {@code refund_amount}/{@code refund_currency}、{@code transaction}、
     * {@code checkout.request_id}/{@code metadata} 与 {@code subscription.metadata}：
     * 订单关联并非不可得，之前「渠道载荷无关联字段」的注释是错的。
     * 退款金额必须取 {@code refund_amount}（本次退款额），不是原交易的 {@code amount_paid}。
     *
     * <p>刻意不携带 subscriptionId：退款走订单级权益撤销，订阅终止由取消事件另行处理。
     */
    private void parseRefundCreated(JsonNode data, WebhookPayload payload) {
        // 订单号：checkout.request_id → metadata.order_id → 订阅 metadata.order_id
        String orderId = null;
        if (data.has("checkout") && data.get("checkout").isObject()) {
            JsonNode checkout = data.get("checkout");
            orderId = textOrNull(checkout.get("request_id"));
            if (orderId == null && checkout.has("metadata") && checkout.get("metadata").hasNonNull("order_id")) {
                orderId = checkout.get("metadata").get("order_id").asText();
            }
        }
        if (orderId == null && data.has("metadata") && data.get("metadata").hasNonNull("order_id")) {
            orderId = data.get("metadata").get("order_id").asText();
        }
        if (orderId == null && data.has("subscription") && data.get("subscription").isObject()) {
            JsonNode subscription = data.get("subscription");
            if (subscription.has("metadata") && subscription.get("metadata").hasNonNull("order_id")) {
                orderId = subscription.get("metadata").get("order_id").asText();
            }
        }
        payload.setOrderId(orderId);

        JsonNode txn = data.has("transaction") && data.get("transaction").isObject()
                ? data.get("transaction") : null;
        if (txn != null) {
            payload.setTransactionId(textOrNull(txn.get("id")));
            payload.setPaymentId(payload.getTransactionId());
        }
        // 本次退款额/币种（缺失时留 null，公共层先核实，不拿原交易实付额顶替）
        payload.setRefundAmount(minorToMajor(data.get("refund_amount")));
        payload.setRefundCurrency(textOrNull(data.get("refund_currency")));

        boolean succeeded = "succeeded".equals(textOrNull(data.get("status")));
        payload.setStatus(succeeded ? PaymentStatus.REFUNDED.name() : PaymentStatus.PENDING.name());
        payload.setEventKind(succeeded ? PaymentEventType.REFUND_SUCCEEDED : PaymentEventType.OTHER);
    }

    private static String textOrNull(JsonNode node) {
        return (node != null && !node.isNull() && !node.asText().isEmpty()) ? node.asText() : null;
    }

    /** 渠道金额为最小货币单位整数（分），转主单位两位小数。 */
    private static BigDecimal minorToMajor(JsonNode amountNode) {
        if (amountNode == null || amountNode.isNull()) {
            return null;
        }
        return new BigDecimal(amountNode.asText()).divide(MINOR_UNIT, 2, RoundingMode.HALF_UP);
    }

    /** 渠道 ISO 时间（如 2024-10-12T11:58:38.000Z）转 LocalDateTime（UTC）。 */
    private LocalDateTime parseCreemDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            logger.warn("Creem 时间解析失败：{}", value);
            return null;
        }
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.CREEM;
    }

    /**
     * 全额退款：{@code POST /v1/refunds}，body 只有 {@code transaction_id}
     * （官方接口不接受 amount/reason，剩余可退额由渠道自动结清）。
     *
     * <p><b>部分退款一律 {@code REJECTED}</b>：渠道无部分退款能力，若返回 UNSUPPORTED，
     * {@code AdminService} 的降级策略会改发一笔全额退款——用户申请部分退款却被全额退。
     *
     * <p>渠道异步受理（{@code status=pending}）时返回 {@code UNKNOWN}，由调用方经
     * {@link #queryRefund} 反查，绝不直接发起第二笔退款。
     */
    @Override
    public RefundResult refund(Order order, String paymentId, BigDecimal amount, String refundRequestId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Creem 未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return RefundResult.rejected("Creem 未配置");
        }
        BigDecimal total = order.getTotalAmount();
        if (amount != null && total != null && amount.compareTo(total) < 0) {
            logger.warn("Creem 不支持部分退款，已拒绝：orderNo={}, amount={}, total={}",
                    order.getOrderNo(), amount, total);
            return RefundResult.rejected("Creem 仅支持全额退款，部分退款请走其他处理方式");
        }
        String transactionId = resolveTransactionId(paymentId);
        if (transactionId == null) {
            logger.error("Creem 无法解析交易号，退款中止：orderNo={}, paymentId={}", order.getOrderNo(), paymentId);
            return RefundResult.rejected("无法解析 Creem 交易号（需 tran_ 前缀，checkout 单号无法退款）");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("transaction_id", transactionId);
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/refunds")))
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = httpResponse.statusCode();
            if (code >= 500) {
                // 渠道内部错误：无法判定是否已受理，按未知处理，交给反查确认
                logger.error("Creem 退款返回 {}，结果未知：orderNo={}, body={}", code, order.getOrderNo(), httpResponse.body());
                return RefundResult.unknown("Creem 退款返回 " + code + "，结果待确认");
            }
            if (code < 200 || code >= 300) {
                logger.error("Creem 退款被渠道拒绝：orderNo={}, status={}, body={}",
                        order.getOrderNo(), code, httpResponse.body());
                return RefundResult.rejected("Creem 退款被拒绝：" + httpResponse.body());
            }
            JsonNode result = objectMapper.readTree(httpResponse.body());
            String status = textOrNull(result.get("status"));
            String refundId = textOrNull(result.get("id"));
            if ("succeeded".equals(status)) {
                logger.info("Creem 退款成功：orderNo={}, refundId={}, transactionId={}",
                        order.getOrderNo(), refundId, transactionId);
                return RefundResult.success(refundId);
            }
            if ("pending".equals(status)) {
                logger.info("Creem 退款已受理待结算：orderNo={}, refundId={}", order.getOrderNo(), refundId);
                return RefundResult.unknown("Creem 退款待结算（pending）：" + refundId);
            }
            logger.error("Creem 退款未成功：orderNo={}, status={}, body={}",
                    order.getOrderNo(), status, httpResponse.body());
            return RefundResult.rejected("Creem 退款未成功：" + status);
        } catch (Exception e) {
            logger.error("Creem 退款异常（结果未知）：orderNo={}", order.getOrderNo(), e);
            return RefundResult.unknown("Creem 退款异常：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 退款结果反查：官方无退款查询端点，改用 {@code GET /v1/transactions?transaction_id=}
     * 读交易状态——{@code status=refunded} 视为已全额退回，其余一律视为未确认。
     *
     * <p>只认全额已退：部分退款（后台操作遗留）不给 success，避免把半笔退款当全额。
     */
    @Override
    public boolean supportsRefundQuery() {
        return true;
    }

    @Override
    public RefundResult queryRefund(Order order, String paymentId, String refundRequestId) {
        String transactionId = resolveTransactionId(paymentId);
        if (transactionId == null) {
            return RefundResult.rejected("无法解析 Creem 交易号");
        }
        try {
            String encoded = URLEncoder.encode(transactionId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/transactions?transaction_id=" + encoded)))
                    .header("x-api-key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                logger.error("Creem 退款反查失败：transactionId={}, status={}, body={}",
                        transactionId, httpResponse.statusCode(), httpResponse.body());
                return RefundResult.unknown("Creem 退款反查失败：" + httpResponse.statusCode());
            }
            JsonNode data = objectMapper.readTree(httpResponse.body());
            String status = textOrNull(data.get("status"));
            if ("refunded".equals(status)) {
                logger.info("Creem 退款反查确认已全额退回：transactionId={}", transactionId);
                return RefundResult.success(transactionId);
            }
            logger.info("Creem 退款反查未确认：transactionId={}, status={}", transactionId, status);
            return RefundResult.unknown("Creem 退款尚未确认：" + status);
        } catch (Exception e) {
            logger.error("Creem 退款反查异常：transactionId={}", transactionId, e);
            return RefundResult.unknown("Creem 退款反查异常：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 解析退款所需的 {@code transaction_id}：{@code tran_} 直接可用；
     * {@code ch_} 是建单时落库的 checkout id，必须回查 checkout 取 {@code order.transaction}。
     * 其余形态（含建单失败时的占位号 {@code creem_<orderNo>}）无法退款。
     */
    private String resolveTransactionId(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return null;
        }
        String id = paymentId.trim();
        if (id.startsWith("tran_")) {
            return id;
        }
        if (!id.startsWith("ch_")) {
            return null;
        }
        try {
            String encoded = URLEncoder.encode(id, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/checkouts?checkout_id=" + encoded)))
                    .header("x-api-key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                logger.error("Creem checkout 回查失败，无法取得交易号：checkoutId={}, status={}, body={}",
                        id, httpResponse.statusCode(), httpResponse.body());
                return null;
            }
            JsonNode data = objectMapper.readTree(httpResponse.body());
            JsonNode orderNode = data.has("order") && data.get("order").isObject() ? data.get("order") : null;
            return orderNode != null ? textOrNull(orderNode.get("transaction")) : null;
        } catch (Exception e) {
            logger.error("Creem checkout 回查异常：checkoutId={}", id, e);
            return null;
        }
    }

    /**
     * #26：按订阅 ID 向 Creem 反查订阅（{@code GET /v1/subscriptions?subscription_id=}），
     * 取回建单时写入 metadata 的 {@code order_id}（订阅对象继承 checkout 的 metadata）。
     * 查询失败 / 未配置 / 取不到 order_id 一律返回 empty（不抛异常），由调用方转人工告警。
     */
    @Override
    public Optional<SubscriptionLookup> lookupSubscription(String subscriptionId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Creem 未配置，跳过订阅查询：subId={}", subscriptionId);
            return Optional.empty();
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        try {
            String encoded = URLEncoder.encode(subscriptionId.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/subscriptions?subscription_id=" + encoded)))
                    .header("x-api-key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode data = objectMapper.readTree(httpResponse.body());
            if (!data.isObject() || !data.hasNonNull("id")) {
                logger.error("Creem 订阅查询返回异常：subId={}, body={}", subscriptionId, httpResponse.body());
                return Optional.empty();
            }
            String orderId = data.has("metadata") && data.get("metadata").hasNonNull("order_id")
                    ? data.get("metadata").get("order_id").asText() : null;
            if (orderId == null || orderId.isBlank()) {
                logger.warn("Creem 订阅未携带 metadata.order_id，无法自动重修：subId={}", subscriptionId);
                return Optional.empty();
            }
            String status = textOrNull(data.get("status"));
            return Optional.of(SubscriptionLookup.builder()
                    .subscriptionId(data.get("id").asText())
                    .orderId(orderId)
                    .active("active".equals(status) || "trialing".equals(status))
                    .currentPeriodStart(parseCreemDateTime(textOrNull(data.get("current_period_start_date"))))
                    .currentPeriodEnd(parseCreemDateTime(textOrNull(data.get("current_period_end_date"))))
                    .build());
        } catch (Exception e) {
            logger.error("Creem 订阅查询失败，转人工补录：subId={}", subscriptionId, e);
            return Optional.empty();
        }
    }

    /**
     * 订阅自助取消（期末生效）：{@code POST /v1/subscriptions/{id}/cancel}，
     * body {@code {"mode":"scheduled","onExecute":"cancel"}}——mode 必须显式传 scheduled
     * （渠道默认取消时点由店铺计费设置决定，不传可能变成立即取消）。
     * 以响应 {@code status=scheduled_cancel} 为确认（官方状态枚举确认值）；确认不到返回 false。
     */
    @Override
    public boolean cancelSubscriptionAtPeriodEnd(String subscriptionId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Creem 未配置，无法取消订阅：subId={}", subscriptionId);
            return false;
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return false;
        }
        try {
            String encoded = URLEncoder.encode(subscriptionId.trim(), StandardCharsets.UTF_8);
            Map<String, Object> body = new HashMap<>();
            body.put("mode", "scheduled");
            body.put("onExecute", "cancel");
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/subscriptions/" + encoded + "/cancel")))
                    .header("x-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                logger.error("Creem 订阅取消被渠道拒绝：subId={}, status={}, body={}",
                        subscriptionId, httpResponse.statusCode(), httpResponse.body());
                return false;
            }
            JsonNode data = objectMapper.readTree(httpResponse.body());
            if (!"scheduled_cancel".equals(textOrNull(data.get("status")))) {
                logger.error("Creem 订阅取消未见 status=scheduled_cancel，不落库：subId={}, body={}",
                        subscriptionId, httpResponse.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Creem 订阅取消失败：subId={}", subscriptionId, e);
            return false;
        }
    }

    /** Creem 必备配置：API Key 与 Webhook 签名密钥（缺失即所有回调验签失败）。 */
    @Override
    public List<String> missingConfig() {
        List<String> missing = new ArrayList<>();
        if (apiKey == null || apiKey.isEmpty()) {
            missing.add("payment.creem.api-key");
        }
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            missing.add("payment.creem.webhook-secret");
        }
        return missing;
    }
}
