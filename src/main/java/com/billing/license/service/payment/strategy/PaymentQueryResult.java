package com.billing.license.service.payment.strategy;

import java.math.BigDecimal;

/**
 * 主动查单结果（比裸 {@link PaymentStatus} 多带资金与归属证据）。
 *
 * <p>「渠道说 SUCCESS」不等于「本订单已收对钱」：补偿发货必须核对实收金额、币种与
 * 订单关联。{@code verified} 表示该渠道是否返回了足够证据（金额/币种/订单号）；
 * 调用方在 {@code verified=false} 时只能保持待核，不得直接发货。
 */
public record PaymentQueryResult(
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String transactionId,
        String orderReference,
        boolean verified) {

    public static PaymentQueryResult statusOnly(PaymentStatus status) {
        return new PaymentQueryResult(status, null, null, null, null, false);
    }
}
