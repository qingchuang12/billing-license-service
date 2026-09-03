package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Order entity representing a customer order
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "orders")
public class Order {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String orderNumber;
    
    @Column(nullable = false)
    private UUID customerId;
    
    @Column(nullable = false)
    private BigDecimal totalAmount;
    
    @Column(nullable = false)
    private String currency = "USD";
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;
    
    @Column(name = "payment_intent_id")
    private String paymentIntentId;
    
    @Column(name = "payment_provider")
    private String paymentProvider;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "paid_at")
    private LocalDateTime paidAt;
    
    @Column(name = "machine_code")
    private String machineCode; // 客户端机器码，用于绑定设备

    @Column(name = "email")
    private String email; // 客户邮箱，用于发货通知
    
    @Column(name = "title")
    private String title; // 订单标题
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description; // 订单描述
    
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private java.util.List<OrderItem> orderItems;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // 兼容方法 - 供支付策略使用
    public String getOrderNo() {
        return this.orderNumber;
    }
    
    public BigDecimal getAmount() {
        return this.totalAmount;
    }
    
    public enum OrderStatus {
        PENDING,
        CONFIRMED,
        PROCESSING,
        COMPLETED,
        CANCELLED,
        REFUNDED,
        REFUND_FAILED,
        PAID
    }

    public enum PaymentStatus {
        UNPAID,
        PAID,
        PARTIALLY_REFUNDED,
        REFUNDED,
        FAILED
    }

    // ============ H15：状态机唯一出口（收敛 status 与 paymentStatus 双字段）============
    // 两条状态字段并存易发散，所有转移统一经由下列方法，保证二者永不矛盾。

    /** 支付成功 → 发货前置：仅置为已支付（发货由 fulfillOrder 处理后续状态） */
    public void markPaid() {
        this.status = OrderStatus.PAID;
        this.paymentStatus = PaymentStatus.PAID;
        if (this.paidAt == null) {
            this.paidAt = LocalDateTime.now();
        }
    }

    /** 退款成功：资金已退回，订单与支付状态一致置为 REFUNDED */
    public void markRefunded() {
        this.status = OrderStatus.REFUNDED;
        this.paymentStatus = PaymentStatus.REFUNDED;
    }

    /**
     * 退款发起但渠道侧失败：绝不可标记 REFUNDED（否则账实不符、资损），
     * 仅置内部失败态，保留 PAID 以便运营到渠道控制台手动退款。
     */
    public void markRefundFailed() {
        this.status = OrderStatus.REFUND_FAILED;
        // paymentStatus 保持 PAID，不触碰
    }

    /**
     * 是否可发货（Webhook 成功回调路径）：已支付/已退款/已取消/退款失败均不可再发货。
     * 用于 fulfillOrder 的前置校验，防止重复发货造成资损（H14）。
     */
    public boolean canFulfill() {
        if (this.status == OrderStatus.PAID
                || this.status == OrderStatus.REFUNDED
                || this.status == OrderStatus.REFUND_FAILED
                || this.status == OrderStatus.CANCELLED) {
            return false;
        }
        return this.paymentStatus != PaymentStatus.REFUNDED;
    }
}
