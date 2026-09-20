package com.billing.license.controller.webhook;

import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.CheckoutService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.AmountValidator;
import com.billing.license.service.subscription.SubscriptionService;
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

import java.util.Map;

/**
 * 统一 Webhook 控制器 - 处理所有支付渠道的回调
 */
@Tag(name = "支付回调", description = "各渠道支付/订阅回调（公开端点，由各渠道策略自行验签、幂等、金额校验）")
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    
    @Autowired
    private PaymentServiceFactory paymentServiceFactory;
    
    @Autowired
    private com.billing.license.service.payment.PaymentService paymentService;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private PaymentRepository paymentRepository;

    // B3：渠道退款/取消回调吊销该订单下的 License（与管理端退款同口径）
    @Autowired
    private com.billing.license.repository.LicenseRepository licenseRepository;
    
    @Autowired
    private LicenseService licenseService;
    
    @Autowired
    private RedeemCodeService redeemCodeService;
    
    @Autowired
    private EmailNotificationService emailNotificationService;
    
    @Autowired
    private AmountValidator amountValidator;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private CheckoutService checkoutService;

    // B18：订阅生命周期处理（首充绑定 / 续费延长 / 取消作废）
    @Autowired
    private SubscriptionService subscriptionService;

    // B11：自注入代理，使同类内 processWebhook/fulfillOrder 的 @Transactional 经 AOP 代理生效
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
     * 统一处理 Webhook 回调
     */
    @Transactional
    public ResponseEntity<String> processWebhook(PaymentMethod method, String payload,
                                                     String signature, Map<String, String> headers) {
        // 1. 获取对应支付策略
        var strategy = paymentServiceFactory.getStrategy(method);

        // 2. 验证签名（幂等性第一道防线）
        boolean signatureValid = strategy.verifyWebhookSignature(payload, signature, headers);
        if (!signatureValid) {
            // B12：签名失败时不再写入 payment_events（避免 eventId="unknown-<ts>" 污染幂等表），仅记录日志
            logger.error("Webhook 签名验证失败：{}", method);
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 3. 解析回调数据
        WebhookPayload webhookData = strategy.parseWebhookPayload(payload);

        // 4. 基于 payment_events 的幂等性检查 - 防止重复处理（架构十二）
        // B18：幂等键优先用 Webhook 投递事件 ID（Paddle event_id / Stripe evt_xxx），
        // 对订阅生命周期事件（subscription.updated 重复投递）去重更可靠，缺失时回退到支付流水号
        String eventId = webhookData.getWebhookEventId() != null
            ? webhookData.getWebhookEventId()
            : (webhookData.getTransactionId() != null
                ? webhookData.getTransactionId() : webhookData.getPaymentId());
        if (eventId != null && paymentEventRepository.existsByProviderAndEventId(method.name(), eventId)) {
            logger.info("支付事件已处理，跳过幂等：provider={}, eventId={}", method.name(), eventId);
            return ResponseEntity.ok("Already processed");
        }

        // B18：订阅事件分支（首充 / 续费均由渠道托管，走订阅生命周期处理）
        // 订阅事件携带 subscriptionId；首充委托 fulfillOrder 签发 License + 标记订单 PAID，
        // 续费时订单已 PAID，fulfillOrder 幂等跳过；随后订阅服务绑定/续期/作废 License
        if (webhookData.getSubscriptionId() != null) {
            // R1：发放前先原子预留幂等记录，并发重复投递由唯一约束兜底
            if (!reserveEvent(method, eventId, webhookData, payload, true)) {
                return ResponseEntity.ok("Already processed");
            }
            if (webhookData.getOrderId() != null) {
                self.fulfillOrder(webhookData.getOrderId());
            }
            subscriptionService.processSubscriptionEvent(webhookData, method);
            logger.info("订阅事件处理成功：provider={}, subId={}, status={}",
                method.name(), webhookData.getSubscriptionId(), webhookData.getStatus());
            return ResponseEntity.ok("Success");
        }

        // 5. 金额校验（防篡改，架构十二 Webhook 必须做的检查）
        // R4：金额不符仅返回 400，不预留/不写入幂等记录，确保后续合法重试可重新校验与发货
        if ("SUCCESS".equals(webhookData.getStatus())) {
            Order order = orderRepository.findByOrderNumber(webhookData.getOrderId()).orElse(null);
            if (order != null && !amountValidator.validateAmount(order, webhookData)) {
                logger.error("Webhook 金额校验失败：orderId={}", webhookData.getOrderId());
                return ResponseEntity.status(400).body("Amount mismatch");
            }
        }

        // 6. 更新支付状态
        // B2：传入业务订单号，供 paymentId/transactionId 定位失败时回退（PayPal 存 Order ID、
        // 回调却是 capture ID 的错配场景）；updatePaymentStatus 查无一律降级返回 null，绝不抛异常阻断发货。
        Payment payment = paymentService.updatePaymentStatus(
            webhookData.getPaymentId(),
            PaymentStatus.valueOf(webhookData.getStatus()),
            webhookData.getTransactionId(),
            webhookData.getOrderId()
        );
        if (payment == null) {
            logger.warn("支付记录未定位到，仅按订单号继续发货/退款流程：paymentId={}, orderId={}",
                webhookData.getPaymentId(), webhookData.getOrderId());
        } else {
            payment.setMethod(method);
            paymentRepository.save(payment);
        }

        // 7. 按支付状态分流：成功发货 / 退款·取消吊销 / 其余仅审计
        String status = webhookData.getStatus();
        if ("SUCCESS".equals(status)) {
            // R1：并发重复投递时唯一约束保证仅一个请求能预留成功，其余返回 Already processed
            if (!reserveEvent(method, eventId, webhookData, payload, true)) {
                return ResponseEntity.ok("Already processed");
            }
            self.fulfillOrder(webhookData.getOrderId());
        } else if ("REFUNDED".equals(status) || "CANCELLED".equals(status)) {
            // B3（资损/欺诈修复）：渠道侧退款/取消回调必须吊销 License 并置订单为已退款，
            // 与管理端人工退款（AdminService.refundOrder）同口径——否则客户退款后 License 仍可用。
            // 先原子预留幂等记录，避免重复投递重复吊销。
            if (!reserveEvent(method, eventId, webhookData, payload, true)) {
                return ResponseEntity.ok("Already processed");
            }
            self.revokeOnChannelRefund(webhookData.getOrderId(), status);
        } else {
            // 非 SUCCESS（如 FAILED/PENDING）仍记录审计，但不发货
            reserveEvent(method, eventId, webhookData, payload, true);
        }

        logger.info("Webhook 处理成功：orderId={}, status={}", webhookData.getOrderId(), webhookData.getStatus());
        return ResponseEntity.ok("Success");
    }

    /**
     * R1：原子预留幂等记录。在发货/状态变更之前写入 (provider,event_id) 唯一约束行；
     * 并发重复投递时后者捕获 DataIntegrityViolationException → 返回 false（调用方转 Already processed）。
     * R2：providerPaymentId 存真实渠道支付号（transactionId 优先，回退 paymentId），而非事件 ID。
     */
    private boolean reserveEvent(PaymentMethod method, String eventId, WebhookPayload webhookData,
                                 String payload, boolean processed) {
        // 事件 ID 缺失时无法去重，用 UUID 占位避免与唯一约束误冲突（不会拦截后续真实重试）
        String dedupId = eventId != null ? eventId : "unknown-" + java.util.UUID.randomUUID();
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
                .processed(processed)
                .payload(payload)
                .build();
            paymentEventRepository.save(event);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            logger.info("支付事件已存在（并发去重）：provider={}, eventId={}", method.name(), dedupId);
            return false;
        } catch (Exception e) {
            logger.error("记录支付事件失败", e);
            // 写入失败不应阻断发货主流程
            return true;
        }
    }

    /**
     * 发货逻辑 - 生成兑换码或 License，并发送通知（架构十三）
     */
    @Transactional
    public void fulfillOrder(String orderId) {
        logger.info("开始发货：orderId={}", orderId);

        Order order = orderRepository.findByOrderNumber(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // H14 + B12：订单级前置校验——已支付/已退款/已取消/退款失败均不可再发货，
        // 防止渠道重试 / 客户端轮询 / 重复回调造成重复签发 License 或兑换码（资损）。
        if (!order.canFulfill()) {
            logger.info("订单当前状态不可发货，跳过重复处理：orderId={}, status={}, paymentStatus={}",
                orderId, order.getStatus(), order.getPaymentStatus());
            return;
        }

        // H15：通过状态机唯一出口置为已支付，保证 status 与 paymentStatus 一致
        order.markPaid();
        orderRepository.save(order);

        // 标记收银台会话为已支付
        checkoutService.markPaid(orderId);

        String machineCode = order.getMachineCode();
        String productName = order.getTitle() != null ? order.getTitle() : "License";
        String email = order.getEmail();

        if (machineCode != null && !machineCode.isEmpty()) {
            // 有机器码：直接签发绑定设备的 License
            logger.info("签发绑定设备的 License：orderId={}, machineCode={}", orderId, machineCode);
            licenseService.issueLicense(orderId, machineCode);
            if (email != null && !email.isEmpty()) {
                emailNotificationService.sendPaymentSuccessEmail(
                    email, orderId, productName,
                    order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : 0.0,
                    order.getCurrency());
            }
        } else {
            // 无机器码：生成兑换码
            logger.info("生成兑换码：orderId={}", orderId);
            String code = redeemCodeService.generateCode(orderId);
            if (email != null && !email.isEmpty()) {
                // 支付成功 + 兑换码邮件（统一通知）
                emailNotificationService.sendPaymentSuccessEmail(
                    email, orderId, productName,
                    order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : 0.0,
                    order.getCurrency());
                emailNotificationService.sendRedeemCodeEmail(email, code, productName,
                    order.getPaidAt() != null ? order.getPaidAt().toString() : "");
            }
        }

        logger.info("发货完成：orderId={}", orderId);
    }

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
            logger.warn("退款/取消回调缺订单号，无法吊销 License：status={}", status);
            return;
        }
        Order order = orderRepository.findByOrderNumber(orderId).orElse(null);
        if (order == null) {
            logger.warn("退款/取消回调对应订单不存在：orderId={}, status={}", orderId, status);
            return;
        }
        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED) {
            logger.info("订单已是退款态，跳过重复吊销：orderId={}", orderId);
            return;
        }

        // 吊销该订单下所有未吊销 License（与 AdminService.refundOrder 同口径）
        var licenses = licenseRepository.findByOrder(order);
        int revoked = 0;
        for (var license : licenses) {
            if (license.getStatus() != com.billing.license.entity.License.LicenseStatus.REVOKED) {
                license.setStatus(com.billing.license.entity.License.LicenseStatus.REVOKED);
                license.setRevokedAt(java.time.LocalDateTime.now());
                licenseRepository.save(license);
                revoked++;
            }
        }

        // 订单置退款态（状态机唯一出口，保证 status 与 paymentStatus 一致）
        order.markRefunded();
        orderRepository.save(order);

        logger.info("渠道退款/取消对账完成：orderId={}, status={}, 吊销 License {} 张", orderId, status, revoked);
    }
}
