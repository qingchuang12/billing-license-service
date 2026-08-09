package com.billing.service.payment;

import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.repository.PaymentRepository;
import com.billing.service.payment.impl.PaymentServiceFactory;
import com.billing.service.payment.strategy.PaymentMethod;
import com.billing.service.payment.strategy.PaymentResponse;
import com.billing.service.payment.strategy.PaymentStatus;
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
    
    @Autowired
    private PaymentServiceFactory paymentServiceFactory;
    
    @Autowired
    private PaymentRepository paymentRepository;
    
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
        payment.setCurrency(method.isDomestic() ? "CNY" : "USD");
        payment.setMethod(method.name());
        payment.setStatus(response.getStatus());
        payment.setChannel(method.name());
        payment.setCreatedAt(LocalDateTime.now());
        
        if (response.getExtraParams() != null) {
            // 存储额外参数为 JSON
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                payment.setMetadata(mapper.writeValueAsString(response.getExtraParams()));
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
     * 更新支付状态（Webhook 回调后调用）
     */
    @Transactional
    public Payment updatePaymentStatus(String paymentId, PaymentStatus status, String transactionId) {
        Optional<Payment> paymentOpt = paymentRepository.findByPaymentId(paymentId);
        
        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            payment.setStatus(status.name());
            
            if (transactionId != null) {
                payment.setTransactionId(transactionId);
            }
            
            if (PaymentStatus.SUCCESS.name().equals(status.name())) {
                payment.setPaidAt(LocalDateTime.now());
            }
            
            return paymentRepository.save(payment);
        }
        
        throw new RuntimeException("支付记录不存在：" + paymentId);
    }
    
    /**
     * 获取支付记录
     */
    public Optional<Payment> getPaymentByPaymentId(String paymentId) {
        return paymentRepository.findByPaymentId(paymentId);
    }
    
    /**
     * 获取支付记录
     */
    public Optional<Payment> getPaymentByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
}
