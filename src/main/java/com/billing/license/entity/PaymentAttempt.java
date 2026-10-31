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
 * 一次「选择渠道发起支付」的尝试（#55）。
 *
 * <p>与 {@link PaymentLedgerEntry} 的分工：
 * <ul>
 *   <li>Attempt 记录「用户点了哪个渠道、渠道返回的会话号、该会话的状态」，可多次、可覆盖失败；</li>
 *   <li>Ledger 只记录**已确认发生的资金变动**（收款/退款），不可变、按幂等键去重。</li>
 * </ul>
 * 换渠道不再覆盖上一次尝试，也不会把退款写回 Attempt。
 */
@Entity
@Table(name = "payment_attempts",
    indexes = {
        @Index(name = "idx_payment_attempts_order", columnList = "order_id"),
        @Index(name = "idx_payment_attempts_status", columnList = "order_id,status")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderIdStr;

    @Column(name = "order_number", length = 64)
    private String orderNumber;

    /** 渠道侧会话/交易 ID（Stripe cs_、Creem checkout id、支付宝占位号等）。 */
    @Column(name = "payment_id", unique = true, nullable = false, length = 128)
    private String paymentId;

    @Column(name = "transaction_id", length = 128)
    private String transactionId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", length = 32)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private PaymentStatus status;

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
