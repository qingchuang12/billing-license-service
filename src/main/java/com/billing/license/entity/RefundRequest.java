package com.billing.license.entity;

import com.billing.license.service.payment.strategy.PaymentMethod;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 退款意图/结果 outbox。渠道 HTTP 必须在事务外调用，本表负责把
 * 「已发起 → 渠道已成功 → 本地已结算」三个状态持久化，供后台任务补偿。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "refund_requests",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_requests_order", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_refund_requests_request_id", columnNames = "refund_request_id")
    })
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod provider;

    @Column(name = "channel_payment_id")
    private String channelPaymentId;

    @Column(name = "channel_refund_id")
    private String channelRefundId;

    @Column(name = "refund_request_id", nullable = false)
    private String refundRequestId;

    /** 实际发给渠道的请求号：降级全额退时为 {@code <base>-full}，反查必须用它。 */
    @Column(name = "channel_request_id")
    private String channelRequestId;

    @Column(name = "requested_amount", nullable = false)
    private BigDecimal requestedAmount;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private boolean degraded;

    private String reason;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 人工转账渠道：BANK / ALIPAY / OTHER，仅 Creem 部分退款使用。 */
    @Column(name = "transfer_channel", length = 32)
    private String transferChannel;

    /** 人工转账流水号/回单号。 */
    @Column(name = "transfer_reference", length = 128)
    private String transferReference;

    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @Column(name = "channel_succeeded_at")
    private LocalDateTime channelSucceededAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum Status {
        /** 已生成退款意图，渠道调用尚未确认 */
        PENDING,
        /** 渠道已确认退款成功，本地权益/流水尚未结算 */
        CHANNEL_SUCCEEDED,
        /** 本地结算完成 */
        SETTLED,
        /** 渠道明确拒绝/不支持，资金未退 */
        FAILED,
        /** 渠道结果未知（超时/响应丢失），需反查后再决定 */
        UNKNOWN,
        /** Creem 退款待管理员审核（全额自动退 / 部分转账退） */
        PENDING_REVIEW,
        /** 管理员已认领审批，渠道执行中（防双击重复退款） */
        APPROVING,
        /** 审核通过的部分退款，等待运营转账并登记流水 */
        MANUAL_REQUIRED,
        /** 审核拒绝，资金未退 */
        REJECTED
    }
}
