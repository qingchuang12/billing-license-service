package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付事件实体 - 记录所有 Webhook 回调原始事件，用于幂等/对账/审计
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String eventId;

    @Column
    private String eventType;

    @Column
    private String orderId;

    @Column
    private String providerPaymentId;

    @Column
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column
    private Boolean signatureValid;

    @Column(nullable = false)
    private Boolean processed;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
