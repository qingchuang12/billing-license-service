package com.billing.license.service.payment.strategy;

/**
 * 渠道退款结果。
 *
 * <p>原 {@code boolean} 把「渠道明确拒绝」「渠道不支持部分退款」「请求超时/响应丢失」
 * 混成同一个 false，调用方据此自动改发全额退款时可能在渠道已受理的情况下重复退款。
 * 必须区分四态：
 * <ul>
 *   <li>{@link Outcome#SUCCESS}：渠道确认退款成功；</li>
 *   <li>{@link Outcome#REJECTED}：渠道明确拒绝（参数/风控/余额等），资金未退；</li>
 *   <li>{@link Outcome#UNSUPPORTED}：渠道不支持本次退款（如部分退款），资金未退；</li>
 *   <li>{@link Outcome#UNKNOWN}：超时、网络异常、响应无法判定——**不得当作失败重试退款**，
 *       必须先按同一退款请求号查询结果。</li>
 * </ul>
 */
public record RefundResult(Outcome outcome, String message, String channelRefundId) {

    public enum Outcome {
        SUCCESS,
        REJECTED,
        UNSUPPORTED,
        UNKNOWN
    }

    public static RefundResult success(String channelRefundId) {
        return new RefundResult(Outcome.SUCCESS, null, channelRefundId);
    }

    public static RefundResult rejected(String message) {
        return new RefundResult(Outcome.REJECTED, message, null);
    }

    public static RefundResult unsupported(String message) {
        return new RefundResult(Outcome.UNSUPPORTED, message, null);
    }

    public static RefundResult unknown(String message) {
        return new RefundResult(Outcome.UNKNOWN, message, null);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
