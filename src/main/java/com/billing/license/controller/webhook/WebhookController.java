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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * 支付宝回调
     */
    @PostMapping("/alipay")
    public ResponseEntity<String> alipayWebhook(
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers) {
        
        logger.info("收到支付宝 Webhook 回调");
        
        // 获取签名（支付宝在参数中）
        String signature = headers.get("sign");
        
        return processWebhook(PaymentMethod.ALIPAY, payload, signature, headers);
    }

    /**
     * 微信支付回调
     */
    @PostMapping("/wechat")
    public ResponseEntity<Map<String, String>> wechatWebhook(
            @RequestBody String payload,
            @RequestHeader Map<String, String> headers) {
        
        logger.info("收到微信 Webhook 回调");
        
        String signature = headers.get("Wechatpay-Signature");
        
        ResponseEntity<String> result = processWebhook(PaymentMethod.WECHAT_PAY, payload, signature, headers);
        
        // 微信需要返回特定格式
        Map<String, String> response = new HashMap<>();
        response.put("code", "SUCCESS");
        response.put("message", "OK");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Stripe 回调
     */
    @PostMapping("/stripe")
    public ResponseEntity<Void> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @RequestHeader Map<String, String> headers) {
        
        logger.info("收到 Stripe Webhook 回调");
        
        ResponseEntity<String> result = processWebhook(PaymentMethod.STRIPE, payload, signature, headers);
        
        return ResponseEntity.ok().build();
    }

    /**
     * Paddle 回调
     */
    @PostMapping("/paddle")
    public ResponseEntity<Void> paddleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Paddle-Signature", required = false) String signature,
            @RequestHeader Map<String, String> headers) {
        
        logger.info("收到 Paddle Webhook 回调");
        
        ResponseEntity<String> result = processWebhook(PaymentMethod.PADDLE, payload, signature, headers);
        
        return ResponseEntity.ok().build();
    }

    /**
     * PayPal 回调
     */
    @PostMapping("/paypal")
    public ResponseEntity<Void> paypalWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Paypal-Transmission-Id", required = false) String signature,
            @RequestHeader Map<String, String> headers) {
        
        logger.info("收到 PayPal Webhook 回调");
        
        ResponseEntity<String> result = processWebhook(PaymentMethod.PAYPAL, payload, signature, headers);
        
        return ResponseEntity.ok().build();
    }

    /**
     * 统一处理 Webhook 回调
     */
    @Transactional
    protected ResponseEntity<String> processWebhook(PaymentMethod method, String payload,
                                                     String signature, Map<String, String> headers) {
        // 1. 获取对应支付策略
        var strategy = paymentServiceFactory.getStrategy(method);

        // 2. 验证签名（幂等性第一道防线）
        boolean signatureValid = strategy.verifyWebhookSignature(payload, signature, headers);
        if (!signatureValid) {
            logger.error("Webhook 签名验证失败：{}", method);
            recordPaymentEvent(method, null, null, payload, false, false, signature);
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 3. 解析回调数据
        WebhookPayload webhookData = strategy.parseWebhookPayload(payload);

        // 4. 基于 payment_events 的幂等性检查 - 防止重复处理（架构十二）
        String eventId = webhookData.getTransactionId() != null
            ? webhookData.getTransactionId() : webhookData.getPaymentId();
        if (eventId != null && paymentEventRepository.existsByProviderAndEventId(method.name(), eventId)) {
            logger.info("支付事件已处理，跳过幂等：provider={}, eventId={}", method.name(), eventId);
            return ResponseEntity.ok("Already processed");
        }

        // 5. 金额校验（防篡改，架构十二 Webhook 必须做的检查）
        if ("SUCCESS".equals(webhookData.getStatus())) {
            Order order = orderRepository.findByOrderNumber(webhookData.getOrderId()).orElse(null);
            if (order != null && !amountValidator.validateAmount(order, webhookData)) {
                logger.error("Webhook 金额校验失败：orderId={}", webhookData.getOrderId());
                recordPaymentEvent(method, eventId, webhookData.getEventType(), payload, true, false, signature);
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

        // 7. 如果支付成功，执行发货逻辑
        if ("SUCCESS".equals(webhookData.getStatus())) {
            fulfillOrder(webhookData.getOrderId());
        }

        // 8. 记录支付事件（审计/对账）
        recordPaymentEvent(method, eventId, webhookData.getEventType(), payload, true, true, signature);

        logger.info("Webhook 处理成功：orderId={}, status={}", webhookData.getOrderId(), webhookData.getStatus());
        return ResponseEntity.ok("Success");
    }

    /**
     * 记录支付事件
     */
    private void recordPaymentEvent(PaymentMethod method, String eventId, String eventType,
                                    String payload, boolean signatureValid, boolean processed, String signature) {
        try {
            PaymentEvent event = PaymentEvent.builder()
                .provider(method.name())
                .eventId(eventId != null ? eventId : "unknown-" + System.currentTimeMillis())
                .eventType(eventType)
                .orderId(null)
                .providerPaymentId(eventId)
                .signatureValid(signatureValid)
                .processed(processed)
                .payload(payload)
                .build();
            paymentEventRepository.save(event);
        } catch (Exception e) {
            logger.error("记录支付事件失败", e);
        }
    }

    /**
     * 发货逻辑 - 生成兑换码或 License，并发送通知（架构十三）
     */
    @Transactional
    protected void fulfillOrder(String orderId) {
        logger.info("开始发货：orderId={}", orderId);

        Order order = orderRepository.findByOrderNumber(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // 标记订单为已支付
        order.setStatus(Order.OrderStatus.PAID);
        order.setPaymentStatus(Order.PaymentStatus.PAID);
        if (order.getPaidAt() == null) {
            order.setPaidAt(java.time.LocalDateTime.now());
        }
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
