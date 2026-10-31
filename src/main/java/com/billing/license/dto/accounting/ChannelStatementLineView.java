package com.billing.license.dto.accounting;

import com.billing.license.entity.ChannelStatementLine;
import com.billing.license.entity.Currency;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class ChannelStatementLineView {
    private Long id;
    private PaymentMethod provider;
    private LocalDate statementDate;
    private String channelTxnId;
    private PaymentLedgerEntry.EntryType entryType;
    private BigDecimal amount;
    private BigDecimal fee;
    private Currency currency;
    private String orderNumber;
    private String matchStatus;
    private Long matchedLedgerId;
    private LocalDateTime importedAt;

    public static ChannelStatementLineView from(ChannelStatementLine l) {
        return ChannelStatementLineView.builder()
            .id(l.getId())
            .provider(l.getProvider())
            .statementDate(l.getStatementDate())
            .channelTxnId(l.getChannelTxnId())
            .entryType(l.getEntryType())
            .amount(l.getAmount())
            .fee(l.getFee())
            .currency(l.getCurrency())
            .orderNumber(l.getOrderNumber())
            .matchStatus(l.getMatchStatus())
            .matchedLedgerId(l.getMatchedLedgerId())
            .importedAt(l.getImportedAt())
            .build();
    }
}
