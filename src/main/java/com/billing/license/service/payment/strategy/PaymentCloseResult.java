package com.billing.license.service.payment.strategy;

/**
 * 渠道关单结果（过期收银台会话的「安全关单」）。
 *
 * <p>只有 {@link Status#CLOSED} 能证明该交易**已不可能再收款**，调用方据此把会话置为终态；
 * 其余状态一律保持会话可补偿，不得当作已关闭——误判会把「用户其实已付款」的单子永久丢进清理。
 */
public record PaymentCloseResult(Status status, String message) {

    public enum Status {
        /** 渠道确认已关闭/已过期，不可能再收款 */
        CLOSED,
        /** 渠道没有关闭接口（Paddle / PayPal / Creem），只能等渠道侧自然过期 */
        UNSUPPORTED,
        /** 渠道明确拒绝关闭：通常意味着交易已支付或已完成，必须先重新查单再决定 */
        REJECTED,
        /** 网络异常、验签失败等，结果未知；下一轮继续查单 */
        UNKNOWN
    }

    public static PaymentCloseResult closed(String message) {
        return new PaymentCloseResult(Status.CLOSED, message);
    }

    public static PaymentCloseResult unsupported(String message) {
        return new PaymentCloseResult(Status.UNSUPPORTED, message);
    }

    public static PaymentCloseResult rejected(String message) {
        return new PaymentCloseResult(Status.REJECTED, message);
    }

    public static PaymentCloseResult unknown(String message) {
        return new PaymentCloseResult(Status.UNKNOWN, message);
    }

    public boolean isClosed() {
        return status == Status.CLOSED;
    }
}
