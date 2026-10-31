package com.billing.license.dto.accounting;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ChannelStatementLineRequest {
    /** ALIPAY / CREEM / ... */
    private String provider;
    private LocalDate statementDate;
    private String channelTxnId;
    /** CHARGE / REFUND / FEE */
    private String entryType;
    private BigDecimal amount;
    /** 渠道手续费；默认 0 */
    private BigDecimal fee;
    /** USD / CNY */
    private String currency;
    private String orderNumber;
    private String rawLine;
}
