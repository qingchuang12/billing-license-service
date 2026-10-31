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

    /**
     * 渠道是否已撤销「期末取消」：{@code TRUE} 表示已安排期末取消，{@code FALSE} 表示未安排
     * （含用户撤销取消后恢复），{@code null} 表示渠道该事件未告知——**不得**据此改动本地标记。
     *
     * <p>Creem 的订阅对象没有 {@code cancel_at_period_end} 字段，只给 {@code canceled_at}：
     * 有值＝已请求取消，{@code null}＝未安排。撤销取消走 {@code POST /subscriptions/{id}/resume}，
     * 渠道随后只发 active/update 这类同步事件，不重发付费事件；若本地只靠下一期扣款成功才复位，
     * 用户恢复订阅到下次扣款之间会一直显示「将于期末取消」。
     */
    private Boolean cancelScheduled;

    /** 业务语义（收款/退款/取消/订阅同步等）；未显式设置时按 {@link #getEffectiveEventKind()} 从 status 推导。 */
    private PaymentEventType eventKind;

    /** 退款实际金额与币种（渠道退款回调，缺省表示未知，按全额处理前须先核实）。 */
    private BigDecimal refundAmount;
    private String refundCurrency;

    /**
     * {@link #refundAmount} 是否为**累计退款总额**（渠道只给累计口径时置 true）。
     *
     * <p>支付宝异步通知的 {@code refund_fee} 是「总退款金额」而非本次退款额，多次部分退款会各投递一次；
     * 结算层据此按「目标累计额 − 已入账额」的差额入账，避免把累计额当单笔额重复叠加。
     */
    private boolean refundAmountCumulative;

    public WebhookPayload() {}

    /**
     * 有效事件语义：策略未显式设置时按资金状态保守推导。
     * 订阅渠道必须显式设置——它们的 status 与事件语义并不一一对应。
     */
    public PaymentEventType getEffectiveEventKind() {
        if (eventKind != null) {
            return eventKind;
        }
        if (PaymentStatus.SUCCESS.name().equals(status)) {
            return subscriptionId != null ? PaymentEventType.SUBSCRIPTION_PAYMENT : PaymentEventType.PAYMENT_SUCCEEDED;
        }
        if (PaymentStatus.REFUNDED.name().equals(status)) {
            return PaymentEventType.REFUND_SUCCEEDED;
        }
        if (PaymentStatus.CANCELLED.name().equals(status)) {
            // 订阅取消与「一次性付款关单」是两件事，不能都当未付款关闭
            return subscriptionId != null
                ? PaymentEventType.SUBSCRIPTION_CANCELED : PaymentEventType.PAYMENT_CANCELLED;
        }
        if ("PAST_DUE".equals(status)) {
            return subscriptionId != null
                ? PaymentEventType.SUBSCRIPTION_PAST_DUE : PaymentEventType.OTHER;
        }
        if ("EXPIRED".equals(status)) {
            return subscriptionId != null
                ? PaymentEventType.SUBSCRIPTION_EXPIRED : PaymentEventType.OTHER;
        }
        return PaymentEventType.OTHER;
    }

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

    public Boolean getCancelScheduled() { return cancelScheduled; }
    public void setCancelScheduled(Boolean cancelScheduled) { this.cancelScheduled = cancelScheduled; }

    public PaymentEventType getEventKind() { return eventKind; }
    public void setEventKind(PaymentEventType eventKind) { this.eventKind = eventKind; }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }

    public String getRefundCurrency() { return refundCurrency; }
    public void setRefundCurrency(String refundCurrency) { this.refundCurrency = refundCurrency; }

    public boolean isRefundAmountCumulative() { return refundAmountCumulative; }
    public void setRefundAmountCumulative(boolean refundAmountCumulative) {
        this.refundAmountCumulative = refundAmountCumulative;
    }
}
