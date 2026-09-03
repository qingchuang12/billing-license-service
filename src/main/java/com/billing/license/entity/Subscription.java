package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订阅记录（B18，Q1 托管 Paddle/Stripe Billing）。
 *
 * 订阅的生命周期由渠道（MoR）托管，本表仅做「对账与 License 联动」：
 * - 首充：由 Webhook 发货（fulfillOrder）签发 License 后，本表建立订阅与 License 的绑定；
 * - 续费（invoice.paid / transaction.billed）：延长已绑定 License 的过期时间；
 * - 取消（subscription.canceled / customer.subscription.deleted）：作废已绑定 License。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId; // 关联首充订单

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private String provider; // PADDLE / STRIPE

    @Column(name = "provider_subscription_id", nullable = false, unique = true)
    private String providerSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.PENDING;

    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    @Column(name = "cancel_at_period_end")
    @Builder.Default
    private Boolean cancelAtPeriodEnd = false;

    @Column(name = "license_id")
    private UUID licenseId; // 关联已签发的 License，用于续期/作废

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

    public enum SubscriptionStatus {
        PENDING,
        ACTIVE,
        PAST_DUE,
        CANCELED,
        EXPIRED
    }
}
