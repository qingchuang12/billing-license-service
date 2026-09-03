package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 统一收银台会话实体 - 记录一次收银台创建到支付完成的生命周期
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "checkout_sessions")
public class CheckoutSession {

    public enum Status {
        CREATED,
        PENDING,
        PAID,
        FAILED,
        EXPIRED,
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "checkout_id", nullable = false, unique = true)
    private String checkoutId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_number")
    private String orderNumber;

    @Column
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CREATED;

    @Column(length = 3)
    private String currency;

    @Column
    private BigDecimal amount;

    @Column
    private String locale;

    @Column
    private String country;

    @Column(name = "machine_id")
    private String machineId;

    @Column
    private String email;

    @Column(name = "return_url", columnDefinition = "TEXT")
    private String returnUrl;

    @Column(name = "cancel_url", columnDefinition = "TEXT")
    private String cancelUrl;

    @Column(name = "provider_session_id")
    private String providerSessionId;

    @Column(name = "pay_url", columnDefinition = "TEXT")
    private String payUrl;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_compensated_at")
    private LocalDateTime lastCompensatedAt; // R3：上次主动对账补偿时间，用于轮询冷却窗口

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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
