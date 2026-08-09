package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Payment Transaction entity
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "payment_transactions")
public class PaymentTransaction {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String transactionId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    
    @Column(nullable = false)
    private String provider;
    
    @Column(nullable = false)
    private String providerTransactionId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status = TransactionStatus.PENDING;
    
    @Column(nullable = false)
    private java.math.BigDecimal amount;
    
    @Column(nullable = false)
    private String currency = "USD";
    
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @Column(name = "processed_at")
    private LocalDateTime processedAt;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    public enum TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED,
        REFUNDED,
        CANCELLED
    }
}
