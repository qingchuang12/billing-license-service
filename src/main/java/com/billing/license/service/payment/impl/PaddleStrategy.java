package com.billing.license.service.payment.impl;

import com.billing.license.config.PaymentHttpClientConfig;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** 默认**生产**网关；沙箱只在 `.env` 显式配 `PADDLE_ENVIRONMENT`（与 `application.yml` 的 payment.paddle.environment 默认一致）。 */
    @Value("${payment.paddle.environment:https://api.paddle.com}")
    private String environment;

    @Value("${payment.paddle.webhook-secret:}")
    private String webhookSecret;

    /** #5：全渠道共享的 HttpClient（带连接超时），由 {@code PaymentHttpClientConfig} 提供。 */
    private final HttpClient httpClient;

    public PaddleStrategy(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private String buildUrl(String path) {
        String base = environment.endsWith("/") ? environment.substring(0, environment.length() - 1) : environment;
        return base + path;
    }

    /**
     * H7 修复：Paddle Billing v2 的 unitPrice.amount 与退款 amount 均为「最小货币单位整数串」
     * （如 "999" = $9.99，官方文档与多个集成指南确认）。出站必须 ×100 转整数串，
     * 与入站 parseWebhookPayload 的 ÷100 解析保持一致。此前误用主单位小数 "9.99"，
     * 会导致 Paddle 按 9.99 分计费或拒单。
     */
    private static String toMinorUnitString(BigDecimal amt) {
        return amt.multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建 Paddle 支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("paddle_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.PADDLE.name());

        try {
            Map<String, Object> body = buildCreateTransactionBody(order);

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl(PADDLE_TRANSACTIONS_URL)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    /**
     * 构建 Paddle 创建交易请求体（提取为包级可测方法，锁定金额单位为最小货币单位整数串）。
     */
    Map<String, Object> buildCreateTransactionBody(Order order) {
        // C2：Paddle Billing v2 请求体字段为 snake_case（unit_price/currency_code/custom_data），
        // 与旧 camelCase 不符会被 422 拒单；ad-hoc price 需声明 tax_mode。
        Map<String, Object> items = new HashMap<>();
        items.put("quantity", 1);

        Product recurringProduct = resolveRecurringProduct(order);
        if (recurringProduct != null) {
            // #25：Paddle 只认 catalog price_id（不认内联价目），带 price_id 的交易由 Paddle 自动建订阅。
            // 缺失即拒单——静默回退 ad-hoc 价目会让用户付了钱却拿不到自动续费。
            String priceId = recurringProduct.getPaddlePriceId();
            if (priceId == null || priceId.isBlank()) {
                throw new BusinessException("PADDLE_PRICE_ID_MISSING",
                        "订阅套餐未配置 Paddle 价目 ID（paddle_price_id），无法开通自动续费");
            }
            items.put("price_id", priceId.trim());
            logger.info("Paddle 订阅模式下单：orderNo={}, cycle={}, priceId={}",
                    order.getOrderNo(), recurringProduct.getBillingCycle(), priceId);
        } else {
            Map<String, Object> unitPrice = new HashMap<>();
            unitPrice.put("amount", toMinorUnitString(order.getAmount()));
            unitPrice.put("currency_code", order.getCurrency().code());
            Map<String, Object> price = new HashMap<>();
            price.put("description", order.getTitle() != null ? order.getTitle() : "License");
            price.put("unit_price", unitPrice);
            items.put("price", price);
            // ad-hoc 价格必须声明 tax_mode（Paddle 代收代缴模式用 external；如需 Paddle 计税改为 automatic）
        }

        Map<String, Object> customData = new HashMap<>();
        customData.put("order_id", order.getOrderNo());
        Map<String, Object> body = new HashMap<>();
        body.put("items", java.util.List.of(items));
        // custom_data.order_id 会被 Paddle 带到自动创建的订阅上，是后续 subscription.* 事件
        // 回查订单的唯一依据（SubscriptionService 用它续期）
        body.put("custom_data", customData);
        if (recurringProduct == null) {
            body.put("tax_mode", "external");
        }
        return body;
    }

    /**
     * #25：从订单行反查「订阅类周期」的套餐；一次性买断或取不到行项时返回 null。
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

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        return queryPaymentDetail(paymentId).status();
    }

    @Override
    public PaymentQueryResult queryPaymentDetail(String paymentId) {
        logger.info("查询 Paddle 支付状态：paymentId={}", paymentId);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl(PADDLE_TRANSACTIONS_URL + "/" + paymentId)))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            if (result.has("data")) {
                JsonNode data = result.get("data");
                String status = data.has("status") ? data.get("status").asText() : "";
                PaymentStatus paymentStatus;
                if ("completed".equals(status) || "billed".equals(status)) {
                    paymentStatus = PaymentStatus.SUCCESS;
                } else if ("canceled".equals(status) || "past_due".equals(status)) {
                    paymentStatus = PaymentStatus.CANCELLED;
                } else {
                    paymentStatus = PaymentStatus.PENDING;
                }
                BigDecimal amount = null;
                if (data.has("details") && data.get("details").has("totals")
                        && data.get("details").get("totals").has("total")) {
                    amount = new BigDecimal(data.get("details").get("totals").get("total").asText())
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                }
                String currency = data.has("currency_code") ? data.get("currency_code").asText() : null;
                String orderRef = data.has("custom_data") && data.get("custom_data").has("order_id")
                    ? data.get("custom_data").get("order_id").asText() : null;
                boolean verified = amount != null && currency != null && orderRef != null;
                return new PaymentQueryResult(paymentStatus, amount, currency,
                    data.has("id") ? data.get("id").asText() : paymentId, orderRef, verified);
            }
            return PaymentQueryResult.statusOnly(PaymentStatus.PENDING);
        } catch (Exception e) {
            logger.error("Paddle 查询失败", e);
            return PaymentQueryResult.statusOnly(PaymentStatus.UNKNOWN);
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
        // Paddle v2 将 ts 与 h1 都放在 Paddle-Signature 请求头：ts=...;h1=...
        // WebhookController 已把该头作为 signature 参数传入；headers 仅作兜底。
        String sigHeader = (signature != null && !signature.isEmpty()) ? signature
                : (headers != null ? headers.get("paddle-signature") : null);
        if (sigHeader == null || sigHeader.isEmpty()) {
            logger.error("Paddle-Signature 头缺失，拒绝回调");
            return false;
        }
        try {
            // 解析 ts 与 h1（官方格式：ts=...;h1=<hex>）
            String ts = null;
            String h1 = null;
            for (String part : sigHeader.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if ("ts".equals(k)) ts = v;
                else if ("h1".equals(k)) h1 = v;
            }
            if (ts == null || h1 == null) {
                logger.error("Paddle-Signature 格式非法（缺 ts/h1）：{}", sigHeader);
                return false;
            }

            // w8：时间戳新鲜度校验（±5min 重放防护）
            try {
                long tsSec = Long.parseLong(ts);
                long nowSec = System.currentTimeMillis() / 1000;
                if (Math.abs(nowSec - tsSec) > 300) {
                    logger.error("Paddle 回调时间戳过期（重放风险）：ts={}, now={}", tsSec, nowSec);
                    return false;
                }
            } catch (NumberFormatException e) {
                logger.error("Paddle 回调时间戳格式非法：{}", ts);
                return false;
            }

            // Paddle v2：HMAC-SHA256(ts:payload, webhookSecret) 的十六进制字符串，与 h1 比较
            // 注意 h1 是 hex 而非 Base64（旧实现误用 Base64 导致所有合法回调被拒）。
            String message = ts + ":" + payload;
            Mac mac = Mac.getInstance("HMACSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            String expected = bytesToHex(hash);
            return h1.equalsIgnoreCase(expected);
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

                if (data.has("custom_data") && data.get("custom_data").has("order_id")) {
                    webhookPayload.setOrderId(data.get("custom_data").get("order_id").asText());
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
                if (data.has("currency_code")) {
                    webhookPayload.setCurrency(data.get("currency_code").asText());
                }

                // B18：订阅事件（subscription.*）解析订阅 ID 与周期
                if (eventType.startsWith("subscription.")) {
                    if (data.has("id")) {
                        webhookPayload.setSubscriptionId(data.get("id").asText());
                    }
                    if (data.has("custom_data") && data.get("custom_data").has("order_id")) {
                        webhookPayload.setOrderId(data.get("custom_data").get("order_id").asText());
                    }
                    parseBillingPeriod(data, webhookPayload);
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
                // A10 根治（2026-09-24）：交易续费事件也解析 current_billing_period 写入 currentPeriodEnd，
                // 使续期幂等基准对 transaction.billed/completed 同样可用，杜绝双 eventId 投递导致的累加延长。
                parseBillingPeriod(data, webhookPayload);
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
     * A10（2026-09-24）：从 data 节点解析 Paddle 订阅/交易周期（current_billing_period），
     * 写入 WebhookPayload 的 currentPeriodStart / currentPeriodEnd。周期缺失时静默跳过。
     * subscription.* 与 transaction.billed/completed 共用本方法，保证续期幂等基准对两类事件都可用。
     */
    private void parseBillingPeriod(JsonNode data, WebhookPayload payload) {
        if (data == null || !data.has("current_billing_period")) {
            return;
        }
        JsonNode period = data.get("current_billing_period");
        if (period.has("starts_at")) {
            payload.setCurrentPeriodStart(parsePaddleDateTime(period.get("starts_at").asText()));
        }
        if (period.has("ends_at")) {
            payload.setCurrentPeriodEnd(parsePaddleDateTime(period.get("ends_at").asText()));
        }
    }

    /**
     * H5：Paddle v2 退款（POST /transactions/{id}/refund）。
     * 未配置或失败返回 false（绝不谎报成功）；渠道返回 data 视为受理成功返回 true。
     * 注：Paddle 退款 amount 同样为最小货币单位整数串（与创建一致），故走 toMinorUnitString。
     */
    @Override
    public RefundResult refund(Order order, String paymentId, BigDecimal amount, String refundRequestId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Paddle 未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return RefundResult.rejected("Paddle 未配置");
        }
        try {
            String txnId = (paymentId != null && paymentId.startsWith("paddle_"))
                    ? paymentId.substring("paddle_".length()) : paymentId;
            Map<String, Object> refundBody = buildRefundBody(amount);

            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/transactions/" + txnId + "/refund")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", refundRequestId)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(refundBody)))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());

            if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300 && result.has("data")) {
                logger.info("Paddle 退款受理成功：orderNo={}, txnId={}", order.getOrderNo(), txnId);
                return RefundResult.success(txnId);
            }
            logger.error("Paddle 退款失败：orderNo={}, http={}, body={}",
                order.getOrderNo(), httpResponse.statusCode(), result.toString());
            if (httpResponse.statusCode() >= 500) {
                return RefundResult.unknown("Paddle 退款服务异常：" + httpResponse.statusCode());
            }
            return RefundResult.rejected("Paddle 退款被拒绝：" + httpResponse.statusCode());
        } catch (Exception e) {
            logger.error("Paddle 退款异常（结果未知）：orderNo={}", order.getOrderNo(), e);
            return RefundResult.unknown("Paddle 退款异常：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 构建 Paddle 退款请求体（提取为包级可测方法，锁定金额单位为最小货币单位整数串）。
     */
    Map<String, Object> buildRefundBody(BigDecimal amount) {
        Map<String, Object> refundBody = new HashMap<>();
        refundBody.put("amount", toMinorUnitString(amount));
        refundBody.put("reason", "管理员退款");
        return refundBody;
    }

    /**
     * #26：按订阅 ID 向 Paddle 反查订阅，取回建单时写入交易的 {@code custom_data.order_id}
     * （Paddle 会把交易的 custom_data 带到自动创建的订阅上）。
     * 查询失败 / 未配置 / 取不到 order_id 一律返回 empty（不抛异常），由调用方转人工告警。
     */
    @Override
    public Optional<SubscriptionLookup> lookupSubscription(String subscriptionId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Paddle 未配置，跳过订阅查询：subId={}", subscriptionId);
            return Optional.empty();
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        try {
            String encoded = URLEncoder.encode(subscriptionId.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/subscriptions/" + encoded)))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(httpResponse.body());
            if (!root.has("data")) {
                logger.error("Paddle 订阅查询返回无 data：subId={}, body={}", subscriptionId, httpResponse.body());
                return Optional.empty();
            }
            JsonNode data = root.get("data");
            String orderId = data.has("custom_data") && data.get("custom_data").has("order_id")
                    ? data.get("custom_data").get("order_id").asText() : null;
            if (orderId == null || orderId.isBlank()) {
                logger.warn("Paddle 订阅未携带 order_id，无法自动重修：subId={}", subscriptionId);
                return Optional.empty();
            }
            String status = data.has("status") ? data.get("status").asText() : "";
            LocalDateTime start = null;
            LocalDateTime end = null;
            if (data.has("current_billing_period")) {
                JsonNode period = data.get("current_billing_period");
                if (period.has("starts_at")) {
                    start = parsePaddleDateTime(period.get("starts_at").asText());
                }
                if (period.has("ends_at")) {
                    end = parsePaddleDateTime(period.get("ends_at").asText());
                }
            }
            return Optional.of(SubscriptionLookup.builder()
                    .subscriptionId(data.has("id") ? data.get("id").asText() : subscriptionId)
                    .orderId(orderId)
                    .active("active".equals(status) || "trialing".equals(status))
                    .currentPeriodStart(start)
                    .currentPeriodEnd(end)
                    .build());
        } catch (Exception e) {
            logger.error("Paddle 订阅查询失败，转人工补录：subId={}", subscriptionId, e);
            return Optional.empty();
        }
    }

    /**
     * 订阅自助取消（期末生效）：调 Paddle {@code POST /subscriptions/{id}/cancel} 并声明
     * {@code effective_from=next_billing_period}，用户已付到期的权益保留到本周期结束。
     *
     * <p>Paddle 的确认形态是响应体里出现 {@code scheduled_change.action=cancel}
     * （订阅本体仍是 active），故以此为据；确认不到即返回 false（不抛），
     * 由调用方转成明确业务错误，避免「界面显示已取消、渠道仍在扣款」。
     */
    @Override
    public boolean cancelSubscriptionAtPeriodEnd(String subscriptionId) {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("Paddle 未配置，无法取消订阅：subId={}", subscriptionId);
            return false;
        }
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return false;
        }
        try {
            String encoded = URLEncoder.encode(subscriptionId.trim(), StandardCharsets.UTF_8);
            Map<String, Object> body = new HashMap<>();
            body.put("effective_from", "next_billing_period");
            HttpRequest request = HttpRequest.newBuilder()
                    .timeout(PaymentHttpClientConfig.REQUEST_TIMEOUT)
                    .uri(URI.create(buildUrl("/subscriptions/" + encoded + "/cancel")))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                logger.error("Paddle 订阅取消被渠道拒绝：subId={}, status={}, body={}",
                        subscriptionId, httpResponse.statusCode(), httpResponse.body());
                return false;
            }
            JsonNode root = objectMapper.readTree(httpResponse.body());
            JsonNode data = root.has("data") ? root.get("data") : null;
            if (data == null || !data.has("scheduled_change")
                    || !"cancel".equals(data.get("scheduled_change").path("action").asText())) {
                logger.error("Paddle 订阅取消未见 scheduled_change=cancel，不落库：subId={}, body={}",
                        subscriptionId, httpResponse.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Paddle 订阅取消失败：subId={}", subscriptionId, e);
            return false;
        }
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.PADDLE;
    }

    /**
     * Paddle 必备配置：Vendor ID（或 Seller ID）、API Key、Webhook 签名密钥。
     * webhook-secret 缺失会导致所有 Paddle 回调验签失败（ts/h1 无法计算）。
     */
    @Override
    public List<String> missingConfig() {
        List<String> missing = new ArrayList<>();
        if (vendorId == null || vendorId.isEmpty()) {
            missing.add("payment.paddle.vendor-id");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            missing.add("payment.paddle.api-key");
        }
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            missing.add("payment.paddle.webhook-secret");
        }
        return missing;
    }
}
