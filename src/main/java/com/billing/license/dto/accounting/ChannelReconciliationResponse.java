package com.billing.license.dto.accounting;

import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ChannelReconciliationResponse {
    private PaymentMethod provider;
    private LocalDate from;
    private LocalDate to;
    private Map<String, Long> statusCounts;
    private List<ChannelStatementLineView> lines;
    /** 有 ledger 但渠道账单没出现的交易（最多 200 条，便于人工核查）。 */
    private List<String> ledgerWithoutStatement;
}
