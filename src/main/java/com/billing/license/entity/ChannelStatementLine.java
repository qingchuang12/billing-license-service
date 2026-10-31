package com.billing.license.entity;

import com.billing.license.service.payment.strategy.PaymentMethod;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 渠道账单行（对账输入）。与 payment_ledger_entries 按 channel_txn_id + entry_type 匹配。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "channel_statement_lines",
    uniqueConstraints = @UniqueConstraint(name = "uk_channel_statement_line",
        columnNames = {"provider", "channel_txn_id", "entry_type"}),
    indexes = {
        @Index(name = "idx_channel_statement_date", columnList = "provider, statement_date"),
        @Index(name = "idx_channel_statement_status", columnList = "provider, match_status")
    })
public class ChannelStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentMethod provider;

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

    @Column(name = "channel_txn_id", nullable = false, length = 128)
    private String channelTxnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private PaymentLedgerEntry.EntryType entryType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal fee = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "order_number", length = 64)
    private String orderNumber;

    @Column(name = "raw_line", columnDefinition = "TEXT")
    private String rawLine;

    @Column(name = "match_status", nullable = false, length = 24)
    @Builder.Default
    private String matchStatus = "UNMATCHED";

    @Column(name = "matched_ledger_id")
    private Long matchedLedgerId;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private LocalDateTime importedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        importedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
