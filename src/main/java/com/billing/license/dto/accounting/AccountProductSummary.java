package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 分产品账务汇总（按产品 × 币种）。
 *
 * <p>金额来自订单明细 {@code OrderItem.totalPrice}（与订单同币种），并按订单级支付状态
 * 归入「实收 / 已退款 / 净收入」。一个订单含多个明细时，各明细金额分别计入对应产品，不重复放大。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分产品账务汇总")
public class AccountProductSummary {

    @Schema(description = "产品 ID", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private UUID productId;

    @Schema(description = "产品 SKU", example = "PRO-LIFETIME")
    private String sku;

    @Schema(description = "产品名称", example = "Pro 买断版")
    private String name;

    @Schema(description = "产品档位：PRO / PRO_PLUS", example = "PRO")
    private PlanTier tier;

    @Schema(description = "计费周期：ONE_TIME / MONTHLY / QUARTERLY / YEARLY / LIFETIME", example = "LIFETIME")
    private Product.BillingCycle billingCycle;

    @Schema(description = "结算币种", example = "USD")
    private Currency currency;

    @Schema(description = "实收金额", example = "2990.00")
    private BigDecimal grossReceived;

    @Schema(description = "已退款金额", example = "299.00")
    private BigDecimal refunded;

    @Schema(description = "净收入 = 实收 − 已退款", example = "2691.00")
    private BigDecimal net;

    @Schema(description = "涉及该产品的已支付类订单数", example = "10")
    private long orderCount;
}
