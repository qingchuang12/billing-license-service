package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import com.billing.license.entity.RefundRequest;
import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ManualRefundView {
    private UUID id;
    private String orderNumber;
    private PaymentMethod provider;
    private BigDecimal requestedAmount;
    private BigDecimal amount;
    private Currency currency;
    private RefundRequest.Status status;
    private String lastError;
    private String transferChannel;
    private String transferReference;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ManualRefundView from(RefundRequest r) {
        return ManualRefundView.builder()
            .id(r.getId())
            .orderNumber(r.getOrderNumber())
            .provider(r.getProvider())
            .requestedAmount(r.getRequestedAmount())
            .amount(r.getAmount())
            .currency(r.getCurrency())
            .status(r.getStatus())
            .lastError(r.getLastError())
            .transferChannel(r.getTransferChannel())
            .transferReference(r.getTransferReference())
            .reviewedBy(r.getReviewedBy())
            .reviewedAt(r.getReviewedAt())
            .reviewNote(r.getReviewNote())
            .createdAt(r.getCreatedAt())
            .updatedAt(r.getUpdatedAt())
            .build();
    }
}
