package com.billing.license.dto.accounting;

import com.billing.license.entity.Currency;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易流水明细视图（一行对应一条 {@code Payment} 记录，即一次资金变动）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交易流水明细（一行一条支付记录）")
public class AccountTransactionView {

    @Schema(description = "支付记录 ID", example = "PAY20260920xxxx")
    private String paymentId;

    @Schema(description = "关联订单 ID（Order UUID 字符串）", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private String orderId;

    @Schema(description = "渠道交易号（回填后才有）", example = "2026092022001...")
    private String transactionId;

    @Schema(description = "变动金额", example = "299.00")
    private BigDecimal amount;

    @Schema(description = "币种", example = "USD")
    private Currency currency;

    @Schema(description = "支付渠道（枚举值）", example = "ALIPAY")
    private PaymentMethod channel;

    @Schema(description = "渠道中文名", example = "支付宝")
    private String channelName;

    @Schema(description = "支付状态（SUCCESS / REFUNDED / FAILED / PENDING / CANCELLED / UNKNOWN）", example = "SUCCESS")
    private PaymentStatus status;

    @Schema(description = "状态中文描述", example = "支付成功")
    private String statusDesc;

    @Schema(description = "记录创建时间", example = "2026-09-20T10:11:12")
    private LocalDateTime createdAt;

    @Schema(description = "实际支付/退款完成时间", example = "2026-09-20T10:11:30")
    private LocalDateTime paidAt;
}
