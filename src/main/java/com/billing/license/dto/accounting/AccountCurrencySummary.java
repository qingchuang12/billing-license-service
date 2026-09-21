package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 单币种账务汇总块（收入总览 / 分渠道 / 分产品共用）。
 *
 * <p>口径（与 {@code AccountingService} 一致，固化在代码注释中）：
 * <ul>
 *   <li>gmv：区间内全部订单金额之和（不论支付状态）。</li>
 *   <li>grossReceived（实收）：{@code OrderStatus ∈ {PAID, REFUNDED, REFUND_FAILED}} 的订单金额之和
 *       （REFUND_FAILED 时钱没退成，仍算实收）。</li>
 *   <li>refunded（已退款）：{@code paymentStatus = REFUNDED}（即 OrderStatus = REFUNDED）的订单金额之和。</li>
 *   <li>net（净收入）：grossReceived − refunded。</li>
 *   <li>avgOrderValue（客单价）：grossReceived / paidOrderCount。</li>
 * </ul>
 * 所有金额仅在同一 {@link #currency} 内相加，禁止跨币种求和。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "单币种账务汇总")
public class AccountCurrencySummary {

    @Schema(description = "结算币种（ISO 4217，如 USD / CNY）", example = "USD")
    private Currency currency;

    @Schema(description = "GMV：区间内全部订单金额（不论支付状态）", example = "5990.00")
    private BigDecimal gmv;

    @Schema(description = "实收：已支付/已退款/退款失败订单金额之和", example = "2990.00")
    private BigDecimal grossReceived;

    @Schema(description = "已退款金额", example = "299.00")
    private BigDecimal refunded;

    @Schema(description = "净收入 = 实收 − 已退款", example = "2691.00")
    private BigDecimal net;

    @Schema(description = "区间内订单总数（全部状态）", example = "20")
    private long orderCount;

    @Schema(description = "已支付类订单数（计入实收的订单数）", example = "10")
    private long paidOrderCount;

    @Schema(description = "客单价 = 实收 / 已支付订单数", example = "299.00")
    private BigDecimal avgOrderValue;
}
