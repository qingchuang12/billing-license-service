package com.billing.license.controller.webhook;

import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.common.web.WebhookReplayGuard;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentAttempt;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.risk.RateLimitService;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.service.RefundSettlementService;
import com.billing.license.service.fulfillment.FulfillmentService;
import com.billing.license.service.fulfillment.LicenseFulfillmentHandler;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentEventType;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.AmountValidator;
import com.billing.license.service.subscription.SubscriptionService;
import com.billing.license.exception.BusinessException;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 统一 Webhook 控制器 - 处理所有支付渠道的回调
 */
@Tag(name = "支付回调", description = "各渠道支付/订阅回调（公开端点，由各渠道策略自行验签、幂等、金额校验）")
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    /**
     * 回调成功响应体。
     *
     * <p>必须是小写 {@code success}：支付宝以「响应体是否为 success」判定投递成功，返回
     * {@code Success} / {@code Already processed} 会被判为失败并在 25 小时内重发最多 8 次
     * （重复投递虽被幂等挡住，但重试风暴会持续占用连接与日志）。微信分支只看状态码，
     * 另按其要求包成 {@code {"code":"SUCCESS"}}，不受此处影响。
     */
    private static final String SUCCESS_BODY = "success";
    
    @Autowired
    private PaymentServiceFactory paymentServiceFactory;
    
    @Autowired
    private com.billing.license.service.payment.PaymentService paymentService;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private PaymentLedgerRepository paymentLedgerRepository;

    @Autowired
    private AmountValidator amountValidator;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private FulfillmentService fulfillmentService;

    // B18：订阅生命周期处理（首充绑定 / 续费延长 / 取消作废）
    @Autowired
    private SubscriptionService subscriptionService;

    // 退款权益撤销（License + 未用兑换码）与退款流水统一入口
    @Autowired
    private RefundSettlementService refundSettlementService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private ClientIpResolver clientIpResolver;

    @Autowired
    private WebhookReplayGuard webhookReplayGuard;

    /** 幂等预占结果。 */
    private enum Reservation {
        /** 首次处理（已插入 processed=false 的事件行） */
        NEW,
        /** 此前已成功处理 */
        ALREADY_DONE,
        /** 此前处理未完成，本次允许重试（沿用既有事件行） */
        RETRY
    }

    // B11：自注入代理，使同类内 processWebhook 调用 processVerifiedWebhook 的 @Transactional 经 AOP 代理生效
    @Autowired
    @Lazy
    private WebhookController self;

    /**
     * 支付宝回调
     */
    @Operation(summary = "支付宝回调", description = "支付宝异步通知（sign 在表单参数中）；验签/金额校验失败返回对应 4xx")
    @PostMapping("/alipay")
    public ResponseEntity<String> alipayWebhook(
            @Parameter(description = "支付宝原始回调报文（form-urlencoded）") @RequestBody String payload,
            @Parameter(hidden = true) @RequestHeader Map<String, String> headers) {
        
        logger.info("收到支付宝 Webhook 回调");

        // 支付宝异步通知以 application/x-www-form-urlencoded POST 到 notify_url，
        // sign 位于请求体参数中（不在 HTTP Header）。从 body 解析取 sign，
        // 并将完整参数 Map 传入验签（与 SDK 取参方式一致）。
        String signature = com.billing.license.service.payment.util.FormParamParser.parse(payload)
                .get("sign");

        return self.processWebhook(PaymentMethod.ALIPAY, payload, signature, headers);
    }

    /**
     * 微信支付回调
     */
    @Operation(summary = "微信支付回调", description = "微信支付异步通知；成功返回 {\"code\":\"SUCCESS\"}，失败透传 4xx")
    @PostMapping("/wechat")
    public ResponseEntity<String> wechatWebhook(
            @Parameter(description = "微信支付原始回调报文") @RequestBody String payload,
            @Parameter(hidden = true) @RequestHeader Map<String, String> headers) {

        logger.info("收到微信 Webhook 回调");

        String signature = headers.get("Wechatpay-Signature");

        // w3：透传 processWebhook 的真实返回码（验签失败→401 / 金额不符→400 / 成功→200），
        // 不再恒返回 SUCCESS，避免验签失败被微信视为投递成功而停止重试、事件静默丢失。
        ResponseEntity<String> result = self.processWebhook(PaymentMethod.WECHAT_PAY, payload, signature, headers);

        if (result.getStatusCode().is2xxSuccessful()) {
            // 微信要求 body 含 code=SUCCESS 才认定投递成功；仅成功时返回该格式
            return ResponseEntity.ok("{\"code\":\"SUCCESS\",\"message\":\"OK\"}");
        }
        // 失败直接透传状态码与 body（如 401 Invalid signature）
        return result;
    }

    /**
     * Stripe 回调
     */
    @Operation(summary = "Stripe 回调", description = "Stripe 事件通知（Stripe-Signature 验签）；幂等、金额校验")
    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(
            @Parameter(description = "Stripe 原始事件 JSON") @RequestBody String payload,
            @Parameter(description = "Stripe-Signature 签名头") @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @Parameter(hidden = true) @RequestHeader Map<String, String> headers) {

        logger.info("收到 Stripe Webhook 回调");

        // w3：透传真实返回码，不恒 200
        return self.processWebhook(PaymentMethod.STRIPE, payload, signature, headers);
    }

    /**
     * Paddle 回调
     */
    @Operation(summary = "Paddle 回调", description = "Paddle 事件通知（Paddle-Signature 验签）；含订阅生命周期事件")
    @PostMapping("/paddle")
    public ResponseEntity<String> paddleWebhook(
            @Parameter(description = "Paddle 原始事件 JSON") @RequestBody String payload,
            @Parameter(description = "Paddle-Signature 签名头") @RequestHeader(value = "Paddle-Signature", required = false) String signature,
            @Parameter(hidden = true) @RequestHeader Map<String, String> headers) {

        logger.info("收到 Paddle Webhook 回调");

        // w3：透传真实返回码，不恒 200
        return self.processWebhook(PaymentMethod.PADDLE, payload, signature, headers);
    }

    /**
     * Creem 回调
     */
    @Operation(summary = "Creem 回调", description = "Creem 事件通知（creem-signature 验签）；含订阅生命周期与退款事件")
    @PostMapping("/creem")
    public ResponseEntity<String> creemWebhook(
            @Parameter(description = "Creem 原始事件 JSON") @RequestBody String payload,
            @Parameter(description = "creem-signature 签名头（HMAC-SHA256 hex）") @RequestHeader(value = "creem-signature", required = false) String signature,
            @Parameter(hidden = true) @RequestHeader Map<String, String> headers) {

        logger.info("收到 Creem Webhook 回调");

        // w3：透传真实返回码，不恒 200
        return self.processWebhook(PaymentMethod.CREEM, payload, signature, headers);
    }

    /**
     * PayPal 回调
     */
    @Operation(summary = "PayPal 回调", description = "PayPal 事件通知（Paypal-Transmission-Id 验签）")
    @PostMapping("/paypal")
    public ResponseEntity<String> paypalWebhook(
            @Parameter(description = "PayPal 原始事件 JSON") @RequestBody String payload,
            @Parameter(description = "Paypal-Transmission-Id 传输 ID") @RequestHeader(value = "Paypal-Transmission-Id", required = false) String signature,
            @Parameter(hidden = true) @RequestHeader Map<String, String> headers) {

        logger.info("收到 PayPal Webhook 回调");

        // w3：透传真实返回码，不恒 200
        return self.processWebhook(PaymentMethod.PAYPAL, payload, signature, headers);
    }

    /**
     * 统一处理 Webhook 回调。
     *
     * <p>#5（外部调用治理）：<b>验签在事务之外</b>——PayPal 的 {@code verifyWebhookSignature} 会真发一次
     * 外部 HTTP，留在 {@code @Transactional} 内会让这条回调全程占用一根 DB 连接（Hikari 默认池 10）；
     * 渠道抖动时 webhook 与收银台轮询两条腿可同时打满连接池 = 全站不可用。
     * 验签通过后的入库 / 发货腿统一进 {@link #processVerifiedWebhook}，事务边界与原子性和改造前一致。
     */
    public ResponseEntity<String> processWebhook(PaymentMethod method, String payload,
                                                     String signature, Map<String, String> headers) {
        // 0. 入口限流 + 防重放：先于验签/解析，避免匿名端点被刷爆
        try {
            rateLimitService.checkWebhook(method.name(), resolveClientIp());
        } catch (RateLimitService.RateLimitExceededException e) {
            logger.warn("Webhook 入口限流触发：provider={}, {}", method.name(), e.getMessage());
            return ResponseEntity.status(429).header("Retry-After", "60").body("Too Many Requests");
        }
        String replayError = webhookReplayGuard.check(headers);
        if (replayError != null) {
            logger.warn("Webhook 防重放拒绝：provider={}, reason={}", method.name(), replayError);
            return ResponseEntity.status(400).body(replayError);
        }

        // 1. 获取对应支付策略
        var strategy = paymentServiceFactory.getStrategyForExistingPayment(method);

        // 2. 验证签名（幂等性第一道防线）
        boolean signatureValid = strategy.verifyWebhookSignature(payload, signature, headers);
        if (!signatureValid) {
            // B12：签名失败时不再写入 payment_events（避免 eventId="unknown-<ts>" 污染幂等表），仅记录日志
            logger.error("Webhook 签名验证失败：{}", method);
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 3. 解析 + 订阅反查必须在事务外（订阅反查是渠道 HTTP；硬约束 16/18）
        WebhookPayload webhookData = strategy.parseWebhookPayload(payload);
        if (!subscriptionService.enrichFromProvider(webhookData, method)) {
            // 反查失败：事件缺订单号且本地无订阅记录。此时既不能发货也不能假装成功，
            // 返回 503 让渠道重投，事件行保持 processed=false 供人工核对。
            logger.error("订阅事件无法解析订单且渠道反查失败，等待重试：provider={}, subId={}, event={}",
                method.name(), webhookData.getSubscriptionId(), webhookData.getEventType());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Subscription lookup unavailable");
        }
        ResponseEntity<String> response = self.processVerifiedWebhook(method, webhookData, payload);
        // 支付事务提交后再交付（事件已随支付事务落库）；失败由 FulfillmentService 后台重试
        if (response.getStatusCode().is2xxSuccessful()
                && webhookData.getEffectiveEventKind().isConfirmedPayment()
                && webhookData.getOrderId() != null) {
            fulfillmentService.processPending(webhookData.getOrderId(), LicenseFulfillmentHandler.TYPE);
        }
        return response;
    }

    private String resolveClientIp() {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return clientIpResolver.resolve(attributes != null ? attributes.getRequest() : null);
    }

    /**
     * 兼容入口（测试/内部调用）：先解析再进入事务。
     */
    @Transactional
    public ResponseEntity<String> processVerifiedWebhook(PaymentMethod method, String payload) {
        WebhookPayload webhookData = paymentServiceFactory.getStrategyForExistingPayment(method).parseWebhookPayload(payload);
        return processVerifiedWebhook(method, webhookData, payload);
    }

    /**
     * 验签通过后的回调处理：幂等 → 校验 → 按**事件语义**分流。
     *
     * <p>核心不变量：**只有确认收款的事件才允许发货/续期**。订阅的同步/试用/暂停/欠费/取消、
     * 未付款关单都不代表收到钱；退款单独走结算，不得被当作「取消」误吊销或当作「成功」再发货。
     */
    @Transactional
    public ResponseEntity<String> processVerifiedWebhook(PaymentMethod method, WebhookPayload webhookData,
                                                         String payload) {
        PaymentEventType kind = webhookData.getEffectiveEventKind();

        // 4. 幂等：已成功处理直接 200；处理未完成的旧事件允许重试
        String eventId = webhookData.getWebhookEventId() != null
            ? webhookData.getWebhookEventId()
            : (webhookData.getTransactionId() != null
                ? webhookData.getTransactionId() : webhookData.getPaymentId());
        Reserved reserved = reserveEvent(method, eventId, webhookData, payload);
        if (reserved.state() == Reservation.ALREADY_DONE) {
            logger.info("支付事件已处理，跳过幂等：provider={}, eventId={}", method.name(), reserved.dedupId());
            return ResponseEntity.ok(SUCCESS_BODY);
        }

        try {
            ResponseEntity<String> response = dispatch(method, webhookData, kind);
            if (response.getStatusCode().is2xxSuccessful()) {
                markProcessed(method, reserved.dedupId());
            }
            return response;
        } catch (BusinessException e) {
            logger.error("Webhook 校验拒绝：provider={}, code={}, msg={}",
                method.name(), e.getErrorCode(), e.getMessage());
            return ResponseEntity.status(400).body(e.getErrorCode());
        }
    }

    /** 按事件语义分流。任何分支都不得把「非收款事件」当成功收款。 */
    private ResponseEntity<String> dispatch(PaymentMethod method, WebhookPayload data, PaymentEventType kind) {
        switch (kind) {
            case PAYMENT_SUCCEEDED -> {
                return handleConfirmedPayment(method, data, false);
            }
            case SUBSCRIPTION_PAYMENT -> {
                return handleConfirmedPayment(method, data, true);
            }
            case REFUND_SUCCEEDED -> {
                return handleRefund(method, data);
            }
            case PAYMENT_CANCELLED -> {
                cancelAttemptIfPresent(method, data);
                logger.info("未付款交易关闭，仅更新尝试状态（不吊销权益）：orderId={}", data.getOrderId());
                return ResponseEntity.ok(SUCCESS_BODY);
            }
            case OTHER -> {
                logger.info("未处理的回调事件，仅审计：provider={}, event={}, status={}",
                    method.name(), data.getEventType(), data.getStatus());
                return ResponseEntity.ok(SUCCESS_BODY);
            }
            default -> {
                // 订阅同步/试用/暂停/欠费/计划取消/取消/到期：只联动订阅状态，绝不发货
                if (data.getSubscriptionId() == null) {
                    logger.warn("订阅类事件缺少 subscriptionId，仅审计：event={}", data.getEventType());
                    return ResponseEntity.ok(SUCCESS_BODY);
                }
                subscriptionService.processSubscriptionEvent(data, method);
                logger.info("订阅事件处理成功：provider={}, subId={}, kind={}",
                    method.name(), data.getSubscriptionId(), kind);
                return ResponseEntity.ok(SUCCESS_BODY);
            }
        }
    }

    /**
     * 确认收款的统一处理：订单必须存在、金额/币种必须可核、渠道归属必须一致，之后才记账并发货。
     *
     * <p>订阅续期时订单已 PAID：金额可能与首单不同（税费/按比例），故只要求正金额且币种一致；
     * 首充与一次性付款仍按订单金额严格校验。
     */
    private ResponseEntity<String> handleConfirmedPayment(PaymentMethod method, WebhookPayload data,
                                                          boolean subscription) {
        if (data.getOrderId() == null || data.getOrderId().isBlank()) {
            logger.error("收款事件缺少订单号，拒绝发货：provider={}, event={}", method.name(), data.getEventType());
            return ResponseEntity.status(400).body("Order reference missing");
        }
        Order order = orderRepository.findByOrderNumber(data.getOrderId()).orElse(null);
        if (order == null) {
            logger.error("收款事件对应订单不存在，拒绝发货：orderId={}", data.getOrderId());
            return ResponseEntity.status(400).body("Order not found");
        }
        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                || order.getStatus() == Order.OrderStatus.REFUNDED) {
            logger.warn("订单已退款，忽略迟到的收款事件：orderId={}", data.getOrderId());
            return ResponseEntity.ok(SUCCESS_BODY);
        }
        boolean alreadyPaid = order.getPaymentStatus() == Order.PaymentStatus.PAID
            || order.getStatus() == Order.OrderStatus.PAID;
        // 一次性订单只有一笔有效收款：已 PAID 后另一渠道的迟到通知、或同渠道换了事件号的重投，
        // 都不能再追加一条 CHARGE（幂等键含事件号，不同事件号挡不住，账本会虚增收款）。
        // 货已交付，直接确认收到即可；订阅续期走 subscription 分支，不受此处影响。
        if (alreadyPaid && !subscription) {
            logger.warn("订单已收款，忽略迟到的收款事件：orderId={}, provider={}, txn={}",
                data.getOrderId(), method.name(), data.getTransactionId());
            return ResponseEntity.ok(SUCCESS_BODY);
        }
        if (data.getAmount() == null) {
            logger.error("收款事件缺少金额，拒绝处理：orderId={}", data.getOrderId());
            return ResponseEntity.status(400).body("Amount missing");
        }
        if (data.getCurrency() == null
                || order.getCurrency() == null
                || !data.getCurrency().equalsIgnoreCase(order.getCurrency().code())) {
            logger.error("收款事件币种缺失或不符：orderId={}, order={}, webhook={}",
                data.getOrderId(), order.getCurrency(), data.getCurrency());
            return ResponseEntity.status(400).body("Currency mismatch");
        }
        if (!alreadyPaid && !amountValidator.validateAmount(order, data)) {
            logger.error("收款金额校验失败：orderId={}", data.getOrderId());
            return ResponseEntity.status(400).body("Amount mismatch");
        }
        if (alreadyPaid && data.getAmount().signum() <= 0) {
            logger.error("续期金额非法：orderId={}, amount={}", data.getOrderId(), data.getAmount());
            return ResponseEntity.status(400).body("Amount mismatch");
        }

        String chargeKey = subscription
            ? "subcharge:" + data.getSubscriptionId() + ":"
                + (data.getTransactionId() != null ? data.getTransactionId() : data.getWebhookEventId())
            : "charge:" + (data.getWebhookEventId() != null ? data.getWebhookEventId()
                : (data.getTransactionId() != null ? data.getTransactionId() : data.getPaymentId()));
        if (subscription && alreadyPaid) {
            // 续期逐期入账：不新建/覆盖首单尝试，只追加一条不可变收款流水
            paymentService.recordCharge(order, null, method, data.getAmount(), order.getCurrency(),
                data.getTransactionId(), chargeKey, "subscription-renewal");
        } else {
            PaymentAttempt attempt = paymentService.ensureAttemptForWebhook(order, data, method);
            paymentService.recordCharge(order, attempt, method, data.getAmount(), order.getCurrency(),
                data.getTransactionId(), chargeKey, "webhook");
            fulfillmentService.enqueue(order, LicenseFulfillmentHandler.TYPE, null);
        }
        if (subscription) {
            subscriptionService.processSubscriptionEvent(data, method);
        }
        logger.info("收款事件处理成功：orderId={}, subscription={}", data.getOrderId(), subscription);
        return ResponseEntity.ok(SUCCESS_BODY);
    }

    /**
     * 渠道退款：解析订单与**实际退款金额**后统一结算（撤销 License + 未用兑换码、记账）。
     *
     * <p>缺金额或缺订单关联时返回 503/400 并保持 processed=false，绝不按全额猜测入账。
     */
    private ResponseEntity<String> handleRefund(PaymentMethod method, WebhookPayload data) {
        Order order = resolveRefundOrder(data);
        if (order == null) {
            logger.error("退款回调无法关联订单，等待重试/人工核对：provider={}, txn={}, paymentId={}",
                method.name(), data.getTransactionId(), data.getPaymentId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Refund order unresolved");
        }
        if (data.getRefundAmount() == null) {
            logger.error("退款回调缺少退款金额，拒绝按全额猜测：orderId={}", order.getOrderNumber());
            return ResponseEntity.status(400).body("Refund amount missing");
        }
        // 幂等键用事件 ID（同一退款重投不变、不同部分退款不撞）；原交易号只作追溯
        String refundId = data.getWebhookEventId() != null
            ? data.getWebhookEventId() : data.getTransactionId();
        // 累计口径（支付宝 refund_fee）由结算层按差额入账，其余渠道给的是单笔退款额
        refundSettlementService.settle(order, method, refundId,
            data.getRefundAmount(), data.getRefundCurrency(), "Channel refund",
            data.getTransactionId(), data.isRefundAmountCumulative());
        logger.info("渠道退款结算完成：orderId={}, amount={}, cumulative={}", order.getOrderNumber(),
            data.getRefundAmount(), data.isRefundAmountCumulative());
        return ResponseEntity.ok(SUCCESS_BODY);
    }

    /** 退款订单解析：payload 订单号优先，其次按原交易号反查原收款流水所属订单。 */
    private Order resolveRefundOrder(WebhookPayload data) {
        if (data.getOrderId() != null && !data.getOrderId().isBlank()) {
            Order order = orderRepository.findByOrderNumber(data.getOrderId()).orElse(null);
            if (order != null) {
                return order;
            }
        }
        if (data.getTransactionId() != null) {
            for (PaymentLedgerEntry entry : paymentLedgerRepository
                    .findByChannelTxnIdOrderByOccurredAtDesc(data.getTransactionId())) {
                if (entry.getOrderIdStr() == null) {
                    continue;
                }
                try {
                    Order order = orderRepository.findById(java.util.UUID.fromString(entry.getOrderIdStr()))
                        .orElse(null);
                    if (order != null) {
                        return order;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 历史脏数据（非 UUID 文本）跳过
                }
            }
        }
        return null;
    }

    /** 关闭未付款交易：只更新尝试状态，绝不吊销权益。 */
    private void cancelAttemptIfPresent(PaymentMethod method, WebhookPayload data) {
        try {
            PaymentAttempt attempt = paymentService.findAttemptForCharge(
                data.getPaymentId(), data.getTransactionId(), data.getOrderId(), method);
            if (attempt != null) {
                paymentService.updateAttemptStatus(attempt, PaymentStatus.CANCELLED, data.getTransactionId());
            }
        } catch (BusinessException e) {
            logger.error("关闭通知的支付尝试归属不符：{}", e.getMessage());
        }
    }

    /**
     * 原子预占幂等记录。先以 processed=false 插入；只有业务处理成功才 {@link #markProcessed}。
     * 并发重复投递由唯一约束兜底；写库异常一律上抛（绝不假装成功）。
     */
    private Reserved reserveEvent(PaymentMethod method, String eventId, WebhookPayload webhookData,
                                  String payload) {
        String dedupId = eventId != null ? eventId : "unknown-" + java.util.UUID.randomUUID();
        var existing = paymentEventRepository.findByProviderAndEventId(method.name(), dedupId);
        if (existing.isPresent()) {
            return new Reserved(Boolean.TRUE.equals(existing.get().getProcessed())
                ? Reservation.ALREADY_DONE : Reservation.RETRY, dedupId);
        }
        String providerPaymentId = webhookData.getTransactionId() != null
            ? webhookData.getTransactionId() : webhookData.getPaymentId();
        try {
            PaymentEvent event = PaymentEvent.builder()
                .provider(method.name())
                .eventId(dedupId)
                .eventType(webhookData.getEventType())
                .orderId(webhookData.getOrderId())
                .providerPaymentId(providerPaymentId)
                .signatureValid(true)
                .processed(false)
                .payload(payload)
                .build();
            // saveAndFlush 让唯一约束冲突在 try 内立即抛出，而不是拖到事务提交时变成 500
            paymentEventRepository.saveAndFlush(event);
            return new Reserved(Reservation.NEW, dedupId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            logger.info("支付事件已存在（并发去重）：provider={}, eventId={}", method.name(), dedupId);
            return new Reserved(Reservation.ALREADY_DONE, dedupId);
        }
    }

    private void markProcessed(PaymentMethod method, String dedupId) {
        paymentEventRepository.findByProviderAndEventId(method.name(), dedupId).ifPresent(event -> {
            if (!Boolean.TRUE.equals(event.getProcessed())) {
                event.setProcessed(true);
                paymentEventRepository.save(event);
            }
        });
    }

    private record Reserved(Reservation state, String dedupId) {}

    /**
     * B3（资损/欺诈修复）：渠道侧退款/取消回调触发的 License 吊销 + 订单置退款。
     *
     * <p>与管理端人工退款（{@link com.billing.license.service.AdminService#refundOrder}）同口径吊销，
     * 但**不再回调渠道退款**——本方法由渠道退款/取消事件驱动，资金已在渠道侧退回，此处只做本地对账联动。
     * 订阅类退款由 {@code subscriptionService} 分支单独处理（携带 subscriptionId），不进入本路径。
     *
     * <p>幂等：订单已 REFUNDED 直接跳过；License 已 REVOKED 不重复吊销。
     */
    @Transactional
    public void revokeOnChannelRefund(String orderId, String status) {
        if (orderId == null || orderId.isEmpty()) {
            logger.warn("退款回调缺订单号，无法结算：status={}", status);
            return;
        }
        // #4：与 fulfillOrder 同一订单行锁口径——渠道重投或并发时后到者拿到锁后重读即见已退款态并跳过。
        Order order = orderRepository.findByOrderNumberForUpdate(orderId).orElse(null);
        if (order == null) {
            logger.warn("退款回调对应订单不存在：orderId={}, status={}", orderId, status);
            return;
        }
        // 未提供退款金额时按全额；销证与撤未用兑换码统一走 RefundSettlementService
        refundSettlementService.settle(order, order.getPaymentProvider(),
            "channel-" + orderId, null, null, "Channel " + status);
        logger.info("渠道退款结算完成：orderId={}, status={}", orderId, status);
    }
}
