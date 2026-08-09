package com.billing.controller.webhook;

import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;

import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.service.notification.EmailNotificationService;
import com.billing.service.payment.impl.PaymentServiceFactory;
import com.billing.service.payment.strategy.PaymentMethod;
import com.billing.service.payment.strategy.PaymentStatus;
import com.billing.service.payment.strategy.WebhookPayload;
import com.billing.service.payment.util.AmountValidator;
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
    private com.billing.service.payment.PaymentService paymentService;
    
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
        try {
            // 1. 获取对应支付策略
            var strategy = paymentServiceFactory.getStrategy(method);
            
            // 2. 验证签名（幂等性第一道防线）
            if (!strategy.verifyWebhookSignature(payload, signature, headers)) {
                logger.error("Webhook 签名验证失败：{}", method);
                return ResponseEntity.status(401).body("Invalid signature");
            }
            
            // 3. 解析回调数据
            WebhookPayload webhookData = strategy.parseWebhookPayload(payload);
            
            // 4. 幂等性检查 - 防止重复处理
            Optional<Payment> existingPaymentOpt = paymentRepository.findByTransactionId(webhookData.getTransactionId());
            if (existingPaymentOpt.isPresent() && existingPaymentOpt.get().getStatus().equals(PaymentStatus.SUCCESS.name())) {
                logger.info("支付已处理，跳过幂等：transactionId={}", webhookData.getTransactionId());
                return ResponseEntity.ok("Already processed");
            }
            
            // 5. 更新支付状态
            Payment payment = paymentService.updatePaymentStatus(
                webhookData.getPaymentId(), 
                PaymentStatus.valueOf(webhookData.getStatus()), 
                webhookData.getTransactionId()
            );
            if (payment == null) {
                logger.error("支付记录不存在：paymentId={}", webhookData.getPaymentId());
                return ResponseEntity.status(404).body("Payment not found");
            }
            
            // 更新支付方式
            payment.setMethod(method.name());
            paymentRepository.save(payment);
            
            // 6. 如果支付成功，执行发货逻辑
            if ("SUCCESS".equals(webhookData.getStatus())) {
                fulfillOrder(payment.getOrderId().toString());
            }
            
            logger.info("Webhook 处理成功：orderId={}, status={}", webhookData.getOrderId(), webhookData.getStatus());
            return ResponseEntity.ok("Success");
            
        } catch (Exception e) {
            logger.error("Webhook 处理失败", e);
            return ResponseEntity.status(500).body("Internal error");
        }
    }

    /**
     * 发货逻辑 - 生成兑换码或 License
     */
    @Transactional
    protected void fulfillOrder(String orderId) {
        logger.info("开始发货：orderId={}", orderId);
        
        Order order = orderRepository.findByOrderNumber(orderId)
            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        // 标记订单为已支付
        order.setStatus(Order.OrderStatus.PAID);
        orderRepository.save(order);
        
        // 判断是否带有机器码（设备绑定）
        String machineCode = order.getMachineCode();
        
        if (machineCode != null && !machineCode.isEmpty()) {
            // 有机器码：直接签发绑定设备的 License
            logger.info("签发绑定设备的 License：orderId={}, machineCode={}", orderId, machineCode);
            licenseService.issueLicense(orderId, machineCode);
        } else {
            // 无机器码：生成兑换码
            logger.info("生成兑换码：orderId={}", orderId);
            redeemCodeService.generateCode(orderId);
        }
        
        logger.info("发货完成：orderId={}", orderId);
    }
}
