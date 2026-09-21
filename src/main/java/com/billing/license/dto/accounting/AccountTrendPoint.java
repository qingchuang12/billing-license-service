package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 时间趋势点（按粒度 day / month 分桶，再按币种分组）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "收入时间趋势点")
public class AccountTrendPoint {

    @Schema(description = "时间分桶：DAY 形如 2026-09-20，MONTH 形如 2026-09", example = "2026-09")
    private String bucket;

    @Schema(description = "分桶粒度：DAY / MONTH", example = "MONTH")
    private String granularity;

    @Schema(description = "结算币种", example = "USD")
    private Currency currency;

    @Schema(description = "实收金额", example = "2990.00")
    private BigDecimal grossReceived;

    @Schema(description = "已退款金额", example = "299.00")
    private BigDecimal refunded;

    @Schema(description = "净收入 = 实收 − 已退款", example = "2691.00")
    private BigDecimal net;

    @Schema(description = "该分桶内订单数", example = "10")
    private long orderCount;
}
