package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 履约事件（#56）：支付核心只负责「确认收款」，交付由适配器按事件执行。
 *
 * <p>同一订单 + 事件类型唯一；只有业务处理成功才置 DONE，失败保留 FAILED 由后台重试。
 * 当前只有 {@code LICENSE} 适配器，后续非授权类产品可注册新的事件类型与处理器。
 */
@Entity
@Table(name = "fulfillment_events",
    uniqueConstraints = @UniqueConstraint(name = "uk_fulfillment_order_type",
        columnNames = {"order_id", "event_type"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FulfillmentEvent {

    public enum Status {
        PENDING,
        DONE,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderIdStr;

    @Column(name = "order_number", nullable = false, length = 64)
    private String orderNumber;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

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
