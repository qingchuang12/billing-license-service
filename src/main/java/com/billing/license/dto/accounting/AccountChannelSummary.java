package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import com.billing.license.service.payment.strategy.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 分渠道账务汇总（按渠道 × 币种）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分支付渠道账务汇总")
public class AccountChannelSummary {

    @Schema(description = "支付渠道（枚举值，如 ALIPAY / WECHAT_PAY / STRIPE / PADDLE / PAYPAL）", example = "ALIPAY")
    private PaymentMethod channel;

    @Schema(description = "渠道中文名（provider 为 null 时取「未知渠道」）", example = "支付宝")
    private String channelName;

    @Schema(description = "结算币种", example = "CNY")
    private Currency currency;

    @Schema(description = "实收金额", example = "1990.00")
    private BigDecimal grossReceived;

    @Schema(description = "已退款金额", example = "99.00")
    private BigDecimal refunded;

    @Schema(description = "净收入 = 实收 − 已退款", example = "1891.00")
    private BigDecimal net;

    @Schema(description = "该渠道该币种的订单数", example = "8")
    private long orderCount;
}
