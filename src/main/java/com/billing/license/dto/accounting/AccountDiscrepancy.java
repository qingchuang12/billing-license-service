package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账差异记录：订单与支付记录状态/金额不一致的疑点。
 *
 * <p>类型（{@link #type}）取值（注意：当前退款仅变更订单状态、不写 REFUNDED 支付记录，
 * 故差异规则围绕「订单状态 ↔ 成功支付记录」展开）：
 * <ul>
 *   <li>PAID_BUT_NO_SUCCESS_PAYMENT：订单为已支付类（PAID/REFUNDED/REFUND_FAILED），但无 SUCCESS 支付记录。</li>
 *   <li>AMOUNT_MISMATCH：成功支付金额（或币种）与订单金额不一致。</li>
 *   <li>REFUNDED_BUT_NO_PAYMENT：订单已退款，但找不到任何 SUCCESS 支付记录（历史退款可能未留支付明细）。</li>
 *   <li>SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID：存在 SUCCESS 支付记录，但对应订单未处于已支付类状态。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "对账差异疑点")
public class AccountDiscrepancy {

    @Schema(description = "业务订单号", example = "ORD20260920112233")
    private String orderNumber;

    @Schema(description = "订单 ID（UUID 字符串）", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private String orderId;

    @Schema(description = "差异类型", example = "AMOUNT_MISMATCH")
    private String type;

    @Schema(description = "差异说明", example = "成功支付金额 299.00 USD 与订单金额 199.00 USD 不一致")
    private String description;

    @Schema(description = "订单金额", example = "199.00")
    private BigDecimal orderAmount;

    @Schema(description = "订单币种", example = "USD")
    private Currency orderCurrency;

    @Schema(description = "订单状态（OrderStatus）", example = "PAID")
    private String orderStatus;

    @Schema(description = "订单支付状态（Order.PaymentStatus）", example = "PAID")
    private String orderPaymentStatus;

    @Schema(description = "成功支付累计金额（按订单币种对齐后）", example = "299.00")
    private BigDecimal paidAmount;

    @Schema(description = "发现时间（用于排序）", example = "2026-09-20T10:11:12")
    private LocalDateTime foundAt;
}
