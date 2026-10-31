package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class FeeSummaryView {
    private PaymentMethod provider;
    private Currency currency;
    private BigDecimal feeAmount;
    private long entryCount;
}
