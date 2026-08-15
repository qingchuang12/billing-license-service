package com.billing.license.service.payment.strategy;

import com.billing.license.entity.Order;

import java.util.Map;

/**
 * 支付策略接口 - 统一所有支付渠道的实现
 * 
 * 国内支付：
 * - 支付宝
 * - 微信支付
 * - 云闪付/银联
 * 
 * 国外支付：
 * - Paddle (Merchant of Record, 处理税务)
 * - Stripe (信用卡、Apple Pay、Google Pay)
 * - PayPal (海外钱包用户)
 */
public interface PaymentStrategy {

    /**
     * 创建支付订单
     * 
     * @param order 订单信息
     * @return 支付响应（包含支付二维码、跳转链接等）
     */
    PaymentResponse createPayment(Order order);

    /**
     * 查询支付状态
     * 
     * @param paymentId 支付ID
     * @return 支付状态
     */
    PaymentStatus queryPayment(String paymentId);

    /**
     * 验证 Webhook 回调签名
     * 
     * @param payload 回调原始数据
     * @param signature 签名值
     * @param headers HTTP请求头
     * @return 验签是否通过
     */
    boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers);

    /**
     * 解析 Webhook 回调数据
     * 
     * @param payload 回调原始数据
     * @return 标准化的支付事件数据
     */
    WebhookPayload parseWebhookPayload(String payload);

    /**
     * 获取支付方式
     * 
     * @return 支付方式枚举
     */
    PaymentMethod getPaymentMethod();

    /**
     * 退款（管理员发起或平台退款回调）
     *
     * @param order 原始订单
     * @param paymentId 支付侧交易ID（如 Stripe PaymentIntent、支付宝 trade_no）
     * @param amount 退款金额
     * @return true 表示退款已受理/成功，false 表示失败或未实现
     */
    default boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        return false;
    }
}
