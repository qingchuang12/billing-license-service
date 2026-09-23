package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 用户端退款结果（plan-4.1）：告知**实退金额**与退款后的支付状态。
 *
 * <p>注意 {@code fullRefund} 与 {@code refundedAmount} 取**实际结果**而非申请时的折算额：
 * 若发生「渠道不受理部分退款 → 自动降级为全额退」，此处回报的即为全额。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "退款结果")
public class UserRefundResponse {

    /** 业务订单号 */
    @Schema(description = "业务订单号", example = "ORD20260914224937123")
    private String orderNumber;

    /** 实际退还金额 */
    @Schema(description = "实际退还金额（部分退款为折算额，降级/全额为实付额）", example = "91.20")
    private BigDecimal refundedAmount;

    /** 是否全额退款（false 表示按剩余有效期折算的部分退款） */
    @Schema(description = "是否全额退款", example = "false")
    private boolean fullRefund;

    /** 退款后的支付状态：REFUNDED（全额）/ PARTIALLY_REFUNDED（部分） */
    @Schema(description = "退款后支付状态", example = "PARTIALLY_REFUNDED",
            allowableValues = {"REFUNDED", "PARTIALLY_REFUNDED"})
    private String paymentStatus;
}
