package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class LedgerEntryView {
    private Long id;
    private String orderId;
    private String orderNumber;
    private PaymentLedgerEntry.EntryType entryType;
    private String debitAccount;
    private String creditAccount;
    private BigDecimal amount;
    private Currency currency;
    private PaymentMethod provider;
    private String channelTxnId;
    private Long reversalOf;
    private LocalDateTime occurredAt;

    public static LedgerEntryView from(PaymentLedgerEntry e) {
        return LedgerEntryView.builder()
            .id(e.getId())
            .orderId(e.getOrderIdStr())
            .orderNumber(e.getOrderNumber())
            .entryType(e.getEntryType())
            .debitAccount(e.getDebitAccount())
            .creditAccount(e.getCreditAccount())
            .amount(e.getAmount())
            .currency(e.getCurrency())
            .provider(e.getProvider())
            .channelTxnId(e.getChannelTxnId())
            .reversalOf(e.getReversalOf())
            .occurredAt(e.getOccurredAt())
            .build();
    }
}
