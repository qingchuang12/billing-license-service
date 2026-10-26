package com.billing.license.service.payment.strategy;

/**
 * 渠道提供的支付产品形态：客户端据此决定渲染二维码还是跳转链接。
 *
 * <p>与「渠道」是两个维度：同一渠道可能同时提供多种形态（支付宝绑机走扫码、未绑机走网页表单）。
 */
public enum PaymentProduct {
    /** 扫码支付（返回二维码内容） */
    QRCODE,
    /** 网页跳转支付（返回跳转链接或表单） */
    REDIRECT
}
