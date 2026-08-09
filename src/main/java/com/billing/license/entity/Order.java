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
    
    public enum OrderStatus {
        PENDING,
        CONFIRMED,
        PROCESSING,
        COMPLETED,
        CANCELLED,
        REFUNDED
    }
    
    public enum PaymentStatus {
        UNPAID,
        PAID,
        PARTIALLY_REFUNDED,
        REFUNDED,
        FAILED
    }
}
