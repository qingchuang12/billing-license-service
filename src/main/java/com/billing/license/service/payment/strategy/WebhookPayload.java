package com.billing.license.service.payment.strategy;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Webhook回调载荷
 */
public class WebhookPayload {
    private String paymentId;
    private String orderId;
    private String status; // SUCCESS, FAILED, PENDING, REFUNDED
    private BigDecimal amount;
    private String currency;
    private String transactionId; // 第三方支付流水号
    private String paymentMethod;
    private String eventType; // 服务商事件类型（如 checkout.session.completed / PAYMENT.CAPTURE.COMPLETED）
    private String buyerId; // 买家ID
    private Long timestamp;
    private Map<String, Object> rawData; // 原始数据
    
    public WebhookPayload() {}
    
    // Getters and Setters
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public String getBuyerId() { return buyerId; }
    public void setBuyerId(String buyerId) { this.buyerId = buyerId; }
    
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    
    public Map<String, Object> getRawData() { return rawData; }
    public void setRawData(Map<String, Object> rawData) { this.rawData = rawData; }
}
