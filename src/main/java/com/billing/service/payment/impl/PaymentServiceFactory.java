package com.billing.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.service.payment.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 支付服务工厂 - 管理所有支付策略
 */
@Service
public class PaymentServiceFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceFactory.class);
    
    private final Map<PaymentMethod, PaymentStrategy> strategies = new ConcurrentHashMap<>();
    
    @Autowired
    public PaymentServiceFactory(List<PaymentStrategy> strategyList) {
        for (PaymentStrategy strategy : strategyList) {
            strategies.put(strategy.getPaymentMethod(), strategy);
            logger.info("注册支付策略：{}", strategy.getPaymentMethod());
        }
    }
    
    /**
     * 根据支付方式获取策略
     */
    public PaymentStrategy getStrategy(PaymentMethod paymentMethod) {
        PaymentStrategy strategy = strategies.get(paymentMethod);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的支付方式：" + paymentMethod);
        }
        return strategy;
    }
    
    /**
     * 根据支付方式名称获取策略
     */
    public PaymentStrategy getStrategyByName(String name) {
        try {
            PaymentMethod method = PaymentMethod.valueOf(name.toUpperCase());
            return getStrategy(method);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的支付方式：" + name);
        }
    }
    
    /**
     * 获取所有支持的支付方式
     */
    public List<PaymentMethod> getSupportedMethods() {
        return strategies.keySet().stream().collect(Collectors.toList());
    }
    
    /**
     * 获取国内支付方式
     */
    public List<PaymentMethod> getDomesticMethods() {
        return strategies.keySet().stream()
                .filter(PaymentMethod::isDomestic)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取国际支付方式
     */
    public List<PaymentMethod> getInternationalMethods() {
        return strategies.keySet().stream()
                .filter(PaymentMethod::isInternational)
                .collect(Collectors.toList());
    }
}
