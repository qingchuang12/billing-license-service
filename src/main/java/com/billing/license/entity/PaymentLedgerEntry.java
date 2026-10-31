package com.billing.license.entity;

import com.billing.license.service.payment.strategy.PaymentMethod;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 不可变资金账（#55）：一次已确认的收款或退款。
 *
 * <p>幂等键 {@code idempotencyKey} 唯一：同一渠道事件/退款请求重复投递不会写第二条。
 * 记录只增不改，退款不会覆盖原收款行；对账、净收入、逐期订阅收款都以本表为准。
 */
@Entity
@Table(name = "payment_ledger_entries",
    indexes = {
        @Index(name = "idx_ledger_order", columnList = "order_id"),
        @Index(name = "idx_ledger_occurred", columnList = "occurred_at"),
        @Index(name = "idx_ledger_provider", columnList = "provider,occurred_at")
    })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentLedgerEntry {

    public enum EntryType {
        /** 已确认收款 */
        CHARGE,
        /** 已确认退款（全/部分按金额区分） */
        REFUND,
        /** 渠道手续费 */
        FEE,
        /** 冲正（交换原分录借贷方向） */
        REVERSAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderIdStr;

    @Column(name = "order_number", length = 64)
    private String orderNumber;

    /** 对应尝试；订阅续期等无独立尝试时为 null。 */
    @Column(name = "attempt_id")
    private Long attemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 32)
    private PaymentMethod provider;

    /** 渠道交易号/退款号，用于与渠道账单核对。 */
    @Column(name = "channel_txn_id", length = 128)
    private String channelTxnId;

    /** 幂等键（唯一）：charge:&lt;paymentId&gt; / refund:&lt;refundId&gt; / sub:&lt;subId&gt;:&lt;txn&gt; */
    @Column(name = "idempotency_key", unique = true, nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 复式记账借方科目；历史行由 V25 回填。 */
    @Column(name = "debit_account", length = 64)
    private String debitAccount;

    /** 复式记账贷方科目；历史行由 V25 回填。 */
    @Column(name = "credit_account", length = 64)
    private String creditAccount;

    /** 冲正分录指向的原分录 ID。 */
    @Column(name = "reversal_of")
    private Long reversalOf;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        createdAt = occurredAt;
    }
}
