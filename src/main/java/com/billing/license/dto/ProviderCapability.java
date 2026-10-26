package com.billing.license.dto;

import com.billing.license.service.payment.strategy.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 公开目录中「某个已启用渠道对该套餐的售卖能力」。
 *
 * <p>只列已启用且能力匹配该套餐周期的渠道；空数组表示当前部署下该套餐没有任何可售渠道。
 */
@Schema(description = "已启用渠道对指定套餐的售卖能力")
public record ProviderCapability(
        @Schema(description = "渠道枚举名", example = "STRIPE") String method,
        @Schema(description = "渠道区域：CHINA / GLOBAL", example = "GLOBAL") String region,
        @Schema(description = "该渠道是否具备订阅（自动续费）能力", example = "true") boolean supportsRecurring,
        @Schema(description = "该渠道可结算的计费周期", example = "[\"MONTHLY\", \"HALF_YEAR\"]")
        List<String> supportedBillingCycles,
        @Schema(description = "该渠道提供的支付产品形态", example = "[\"REDIRECT\"]")
        List<String> paymentProducts
) {

    public static ProviderCapability from(PaymentMethod method) {
        return new ProviderCapability(
            method.name(),
            method.getRegion(),
            method.supportsRecurring(),
            method.getSupportedBillingCycles().stream().map(Enum::name).toList(),
            method.getPaymentProducts().stream().map(Enum::name).toList());
    }
}
