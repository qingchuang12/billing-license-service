package com.billing.license.controller.webhook;

import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.repository.PaymentRepository;

import com.billing.license.service.CheckoutService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.subscription.SubscriptionService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.AmountValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 统一 Webhook 控制器 - 处理所有支付渠道的回调
 */
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
    @PostMapping("/alipay")
    public ResponseEntity<String> alipayWebhook(
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers) {
        
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
    @PostMapping("/wechat")
    public ResponseEntity<String> wechatWebhook(
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers) {

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
    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @RequestHeader Map<String, String> headers) {

        logger.info("收到 Stripe Webhook 回调");

        // w3：透传真实返回码，不恒 200
        return self.processWebhook(PaymentMethod.STRIPE, payload, signature, headers);
    }

    /**
     * Paddle 回调
     */
    @PostMapping("/paddle")
    public ResponseEntity<String> paddleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Paddle-Signature", required = false) String signature,
            @RequestHeader Map<String, String> headers) {

        logger.info("收到 Paddle Webhook 回调");

        // w3：透传真实返回码，不恒 200
        return self.processWebhook(PaymentMethod.PADDLE, payload, signature, headers);
    }

    /**
     * PayPal 回调
     */
    @PostMapping("/paypal")
    public ResponseEntity<String> paypalWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Paypal-Transmission-Id", required = false) String signature,
            @RequestHeader Map<String, String> headers) {

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
        Payment payment = paymentService.updatePaymentStatus(
            webhookData.getPaymentId(),
            PaymentStatus.valueOf(webhookData.getStatus()),
            webhookData.getTransactionId()
        );
        if (payment == null) {
            logger.warn("支付记录不存在，尝试按订单号创建：paymentId={}", webhookData.getPaymentId());
        } else {
            payment.setMethod(method.name());
            paymentRepository.save(payment);
        }

        // 7. 支付成功 → 原子预留幂等记录（前置到发货之前），再执行发货
        if ("SUCCESS".equals(webhookData.getStatus())) {
            // R1：并发重复投递时唯一约束保证仅一个请求能预留成功，其余返回 Already processed
            if (!reserveEvent(method, eventId, webhookData, payload, true)) {
                return ResponseEntity.ok("Already processed");
            }
            self.fulfillOrder(webhookData.getOrderId());
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
}
