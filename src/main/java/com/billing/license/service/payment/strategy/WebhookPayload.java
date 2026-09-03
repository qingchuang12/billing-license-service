package com.billing.license.service.payment.strategy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Webhook回调载荷
 */
public class WebhookPayload {
    private String paymentId;
    private String orderId;
    private String status; // SUCCESS, FAILED, PENDING, REFUNDED, CANCELLED, PAST_DUE
    private BigDecimal amount;
    private String currency;
    private String transactionId; // 第三方支付流水号
    private String paymentMethod;
    private String eventType; // 服务商事件类型（如 checkout.session.completed / PAYMENT.CAPTURE.COMPLETED / subscription.updated / invoice.paid）
    private String buyerId; // 买家ID
    private Long timestamp;
    private Map<String, Object> rawData; // 原始数据

    // ===== 订阅制扩展字段（B18，Q1 托管 Paddle/Stripe Billing）=====
    /** 渠道侧订阅 ID（Paddle subscription_id / Stripe subscription id）。非空即走订阅处理分支 */
    private String subscriptionId;
    /** Webhook 投递事件 ID（Paddle event_id / Stripe evt_xxx）。用于幂等去重，比支付流水号更适合订阅生命周期事件 */
    private String webhookEventId;
    /** 订阅当前周期起止时间（渠道提供时落库，用于对账与续期推算） */
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;

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

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getWebhookEventId() { return webhookEventId; }
    public void setWebhookEventId(String webhookEventId) { this.webhookEventId = webhookEventId; }

    public LocalDateTime getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(LocalDateTime currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public LocalDateTime getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(LocalDateTime currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }
}
