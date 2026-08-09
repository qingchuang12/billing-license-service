package com.billing.service.payment.strategy;

/**
 * 支付状态枚举
 */
public enum PaymentStatus {
    PENDING("待支付"),
    SUCCESS("支付成功"),
    FAILED("支付失败"),
    REFUNDED("已退款"),
    CANCELLED("已取消"),
    UNKNOWN("未知状态");
    
    private final String description;
    
    PaymentStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isCompleted() {
        return this == SUCCESS || this == REFUNDED || this == CANCELLED;
    }
    
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
