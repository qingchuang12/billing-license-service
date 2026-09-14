package com.billing.license.entity;

import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体
 */
@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderIdStr;
    
    // 兼容 Long 类型的 orderId（旧代码使用）
    @Transient
    public void setOrderId(Long orderId) {
        this.orderIdStr = orderId != null ? orderId.toString() : null;
    }
    
    @Transient
    public Long getOrderId() {
        try {
            return orderIdStr != null ? Long.parseLong(orderIdStr) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    @Column(name = "payment_id", unique = true, nullable = false, length = 128)
    private String paymentId;
    
    @Column(name = "transaction_id", length = 128)
    private String transactionId;
    
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;
    
    /** 币种（ISO 4217，枚举化） */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3)
    private Currency currency;
    
    /** 支付方式（支付渠道，枚举化） */
    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 32)
    private PaymentMethod method;
    
    /** 支付状态（枚举化） */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private PaymentStatus status;
    
    /** 支付渠道（与 method 同源，枚举化） */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 32)
    private PaymentMethod channel;
    
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
