package com.billing.service.payment.util;

import com.billing.license.entity.Order;
import com.billing.service.payment.strategy.WebhookPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 金额校验工具 - 验证 Webhook 回调中的支付金额是否与订单一致
 * 防止恶意篡改回调数据
 */
@Component
public class AmountValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(AmountValidator.class);
    
    /**
     * 允许的金额误差（分）
     */
    private static final BigDecimal MAX_DIFFERENCE = new BigDecimal("0.01");
    
    /**
     * 验证支付金额
     * 
     * @param order 订单对象
     * @param payload Webhook 回调数据
     * @return true 如果金额匹配，false 否则
     */
    public boolean validateAmount(Order order, WebhookPayload payload) {
        if (order == null) {
            logger.error("订单为空，无法验证金额");
            return false;
        }
        
        if (payload == null) {
            logger.error("Webhook 载荷为空，无法验证金额");
            return false;
        }
        
        BigDecimal orderAmount = order.getTotalAmount();
        BigDecimal webhookAmount = payload.getAmount();
        String orderCurrency = order.getCurrency();
        String webhookCurrency = payload.getCurrency();
        
        // 验证货币类型
        if (!isSameCurrency(orderCurrency, webhookCurrency)) {
            logger.error("货币类型不匹配：order={}, webhook={}", orderCurrency, webhookCurrency);
            return false;
        }
        
        // 验证金额
        if (webhookAmount == null) {
            logger.error("Webhook 金额为空");
            return false;
        }
        
        BigDecimal difference = orderAmount.subtract(webhookAmount).abs();
        boolean isValid = difference.compareTo(MAX_DIFFERENCE) <= 0;
        
        if (!isValid) {
            logger.error("金额不匹配：orderAmount={}, webhookAmount={}, difference={}", 
                orderAmount, webhookAmount, difference);
        } else {
            logger.info("金额验证通过：amount={}, currency={}", orderAmount, orderCurrency);
        }
        
        return isValid;
    }
    
    /**
     * 验证金额（使用字符串比较）
     */
    public boolean validateAmount(String orderAmountStr, String orderCurrency, 
                                   String webhookAmountStr, String webhookCurrency) {
        try {
            BigDecimal orderAmount = new BigDecimal(orderAmountStr);
            BigDecimal webhookAmount = new BigDecimal(webhookAmountStr);
            
            return validateAmountInternal(orderAmount, orderCurrency, webhookAmount, webhookCurrency);
        } catch (NumberFormatException e) {
            logger.error("金额格式错误", e);
            return false;
        }
    }
    
    /**
     * 内部验证方法
     */
    private boolean validateAmountInternal(BigDecimal orderAmount, String orderCurrency,
                                           BigDecimal webhookAmount, String webhookCurrency) {
        // 验证货币类型
        if (!isSameCurrency(orderCurrency, webhookCurrency)) {
            logger.error("货币类型不匹配：order={}, webhook={}", orderCurrency, webhookCurrency);
            return false;
        }
        
        // 验证金额
        BigDecimal difference = orderAmount.subtract(webhookAmount).abs();
        boolean isValid = difference.compareTo(MAX_DIFFERENCE) <= 0;
        
        if (!isValid) {
            logger.error("金额不匹配：orderAmount={}, webhookAmount={}, difference={}", 
                orderAmount, webhookAmount, difference);
        }
        
        return isValid;
    }
    
    /**
     * 判断两种货币是否相同（忽略大小写）
     */
    private boolean isSameCurrency(String currency1, String currency2) {
        if (currency1 == null || currency2 == null) {
            return false;
        }
        
        // 标准化货币代码（转大写）
        String c1 = currency1.toUpperCase().trim();
        String c2 = currency2.toUpperCase().trim();
        
        // 处理常见货币代码别名
        if ("CNY".equals(c1) && "RMB".equals(c2)) return true;
        if ("RMB".equals(c1) && "CNY".equals(c2)) return true;
        if ("USD".equals(c1) && "US$".equals(c2)) return true;
        if ("US$".equals(c1) && "USD".equals(c2)) return true;
        
        return c1.equals(c2);
    }
    
    /**
     * 将金额转换为最小货币单位（如元转分）
     */
    public long toMinorUnit(BigDecimal amount, String currency) {
        int scale = getCurrencyScale(currency);
        return amount.multiply(BigDecimal.valueOf(Math.pow(10, scale))).longValue();
    }
    
    /**
     * 获取货币的小数位数
     */
    private int getCurrencyScale(String currency) {
        if (currency == null) {
            return 2; // 默认 2 位小数
        }
        
        switch (currency.toUpperCase().trim()) {
            case "JPY":
            case "KRW":
            case "VND":
                return 0; // 无小数位
            case "BHD":
            case "IQD":
            case "JOD":
            case "KWD":
            case "LYD":
            case "OMR":
            case "TND":
                return 3; // 3 位小数
            default:
                return 2; // 默认 2 位小数（USD, EUR, CNY, GBP 等）
        }
    }
}
