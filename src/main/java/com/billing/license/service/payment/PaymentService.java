package com.billing.license.service.payment;

import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 统一支付服务 - 协调各支付策略完成支付流程
 */
@Service
public class PaymentService {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    
    // i2：共享 ObjectMapper 单例，避免每次调用 createPayment 都 new 一个（浪费且可能重复配置）
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PaymentServiceFactory paymentServiceFactory;
    
    @Autowired
    private PaymentRepository paymentRepository;

    // B2：paymentId/transactionId 均定位失败时，按业务订单号回退查 Payment（订单号 → Order UUID → Payment）
    @Autowired
    private com.billing.license.repository.OrderRepository orderRepository;
    
    /**
     * 创建支付订单
     */
    @Transactional
    public PaymentResponse createPayment(Order order, PaymentMethod method) {
        logger.info("创建支付订单：orderId={}, method={}", order.getOrderNo(), method);
        
        // 使用对应策略创建支付
        PaymentResponse response = paymentServiceFactory.getStrategy(method).createPayment(order);
        
        // 保存支付记录
        Payment payment = new Payment();
        // 将 UUID 转换为 String 存储（因为 Order.id 是 UUID 类型）
        payment.setOrderIdStr(order.getId().toString());
        payment.setPaymentId(response.getPaymentId());
        payment.setAmount(order.getAmount());
        // w16：币种跟随订单币种（双币种场景），不再按 method.isDomestic() 推断
        payment.setCurrency(order.getCurrency());
        payment.setMethod(method);
        payment.setStatus(toStatus(response.getStatus()));
        payment.setChannel(method);
        payment.setCreatedAt(LocalDateTime.now());
        
        if (response.getExtraParams() != null) {
            // 存储额外参数为 JSON
            try {
                payment.setMetadata(objectMapper.writeValueAsString(response.getExtraParams()));
            } catch (Exception e) {
                logger.warn("序列化支付元数据失败", e);
            }
        }
        
        paymentRepository.save(payment);
        
        return response;
    }
    
    /**
     * 查询支付状态
     */
    public PaymentStatus queryPaymentStatus(String paymentId, PaymentMethod method) {
        logger.info("查询支付状态：paymentId={}", paymentId);
        
        return paymentServiceFactory.getStrategy(method).queryPayment(paymentId);
    }
    
    /**
     * 更新支付状态（Webhook 回调后调用）。
     *
     * <p>B2/B7（资损修复）：回调携带的 {@code paymentId} 与落库口径可能不一致——
     * PayPal 下单存 Order ID，回调却是 capture ID；Stripe 一次性支付存 Session ID，
     * 部分事件却带 payment_intent。原实现「按 paymentId 查无即抛 RuntimeException」会在
     * {@code processWebhook} 内 fulfillOrder 之前中断整个事务，导致已扣款订单永不发货、
     * 渠道对同一 500 反复重试。
     *
     * <p>现改为多路定位（paymentId → transactionId → 订单号回退），且**查无一律降级为
     * 日志并返回 null**，绝不抛异常阻断发货：发货由 {@code fulfillOrder} 按订单号独立驱动，
     * Payment 记录仅用于对账，缺失不应挡住 License 签发。
     *
     * @param orderNumber 回调解析出的业务订单号，用于 paymentId/transactionId 均定位失败时回退
     */
    @Transactional
    public Payment updatePaymentStatus(String paymentId, PaymentStatus status, String transactionId, String orderNumber) {
        Payment payment = locatePayment(paymentId, transactionId, orderNumber);
        if (payment == null) {
            logger.warn("支付记录不存在，跳过状态更新（不阻断发货）：paymentId={}, transactionId={}, orderNumber={}",
                paymentId, transactionId, orderNumber);
            return null;
        }

        payment.setStatus(status);
        if (transactionId != null) {
            payment.setTransactionId(transactionId);
        }
        if (PaymentStatus.SUCCESS.name().equals(status.name())) {
            payment.setPaidAt(LocalDateTime.now());
        }
        return paymentRepository.save(payment);
    }

    /**
     * 兼容旧签名（无订单号回退）。保留供既有调用方/测试使用。
     */
    @Transactional
    public Payment updatePaymentStatus(String paymentId, PaymentStatus status, String transactionId) {
        return updatePaymentStatus(paymentId, status, transactionId, null);
    }

    /**
     * 多路定位支付记录：先按 paymentId，再按渠道交易号，最后按订单号（Order UUID）回退。
     * 全部落空返回 null（交由调用方降级处理，不抛异常）。
     */
    private Payment locatePayment(String paymentId, String transactionId, String orderNumber) {
        if (paymentId != null) {
            Optional<Payment> byId = paymentRepository.findByPaymentId(paymentId);
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        if (transactionId != null) {
            Optional<Payment> byTxn = paymentRepository.findByTransactionId(transactionId);
            if (byTxn.isPresent()) {
                return byTxn.get();
            }
        }
        if (orderNumber != null) {
            // 回调订单号是业务单号（orderNumber），Payment.orderIdStr 存 Order UUID → 需先换取 UUID
            Optional<com.billing.license.entity.Order> order = orderRepository.findByOrderNumber(orderNumber);
            if (order.isPresent()) {
                Optional<Payment> byOrder = paymentRepository.findByOrderIdStr(order.get().getId().toString());
                if (byOrder.isPresent()) {
                    return byOrder.get();
                }
            }
        }
        return null;
    }
    
    /**
     * 获取支付记录
     */
    public Optional<Payment> getPaymentByPaymentId(String paymentId) {
        return paymentRepository.findByPaymentId(paymentId);
    }

    /** 渠道返回的状态字符串 → 枚举；未知/空返回 null（不阻断落库） */
    private static PaymentStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PaymentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
