package com.billing.license.exception;

import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.Getter;

/**
 * 支付渠道未启用异常
 *
 * <p>当请求（下单 / 查询 / 退款 / Webhook 回调）指向一个未启用的支付渠道时抛出。
 * 渠道是否启用由 {@code payment.enabled-channels} 决定；该配置留空时，
 * 由 PaymentServiceFactory 按「关键配置是否齐全」自动判定。
 *
 * <p>目的：让未启用的渠道在入口处就被明确拒绝，而不是走到调用渠道 API 才失败
 * （后者会产生难以归因的渠道侧报错，且掩盖「该渠道本就没配」这一真实原因）。
 */
@Getter
public class ChannelDisabledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码，供全局异常处理器与客户端识别 */
    private final String errorCode = "CHANNEL_DISABLED";

    /** 被拒绝的支付渠道 */
    private final PaymentMethod paymentMethod;

    public ChannelDisabledException(PaymentMethod paymentMethod) {
        super("支付渠道未启用：" + (paymentMethod == null ? "未知" : paymentMethod.name()));
        this.paymentMethod = paymentMethod;
    }

    public ChannelDisabledException(PaymentMethod paymentMethod, String reason) {
        super("支付渠道未启用：" + (paymentMethod == null ? "未知" : paymentMethod.name())
                + (reason == null || reason.isEmpty() ? "" : "（" + reason + "）"));
        this.paymentMethod = paymentMethod;
    }
}
