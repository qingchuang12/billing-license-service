package com.billing.license.service.payment.strategy;

/**
 * 单笔收银台的支付回跳与支付形态上下文。
 *
 * <p>地址必须已由 {@code CheckoutService} 做过允许列表校验：只接受站内相对路径或与
 * {@code app.base-url} 同源的绝对地址；渠道不支持取消回跳时 {@code cancelUrl} 为 null，
 * 不得伪造。{@code paymentProduct} 表达扫码/跳转，与机器码无关（#57）。
 */
public record PaymentLinkContext(String successUrl, String cancelUrl, PaymentProduct paymentProduct,
                                 Integer installmentNum) {

    public PaymentLinkContext(String successUrl, String cancelUrl, PaymentProduct paymentProduct) {
        this(successUrl, cancelUrl, paymentProduct, null);
    }

    public PaymentLinkContext(String successUrl, String cancelUrl) {
        this(successUrl, cancelUrl, null, null);
    }

    public static PaymentLinkContext empty() {
        return new PaymentLinkContext(null, null, null, null);
    }
}
