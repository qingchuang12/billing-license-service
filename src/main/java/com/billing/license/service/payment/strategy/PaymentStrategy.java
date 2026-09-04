package com.billing.license.service.payment.strategy;

import com.billing.license.entity.Order;

import java.util.List;
import java.util.Map;

/**
 * 支付策略接口 - 统一所有支付渠道的实现
 *
 * 国内支付：
 * - 支付宝
 * - 微信支付
 *
 * 国外支付：
 * - Paddle (Merchant of Record, 处理税务)
 * - Stripe (信用卡、Apple Pay、Google Pay)
 * - PayPal (海外钱包用户)
 *
 * 渠道可用性由 {@link #missingConfig()} 与 {@link #isConfigured()} 自描述，
 * 供渠道启用开关、启动配置自检、管理端自查接口统一消费（避免各处重复判定规则）。
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
     * 缺失的关键配置项名称列表（使用完整配置项名，如 {@code payment.alipay.private-key}）。
     *
     * <p>实现类需声明「要真正跑通该渠道所必需」的配置：通常是密钥/商户号/应用 ID 等，
     * 缺任意一项都会在真实调用时失败。配置齐全时返回空列表（不得返回 null）。
     *
     * <p>本方法是渠道可用性的唯一事实源：设为抽象方法，强制新增渠道时必须显式声明其配置项，
     * 避免新渠道漏声明而被自动判定为「已配置」从而错误启用。
     *
     * @return 缺失配置项名列表；齐全时为空列表
     */
    List<String> missingConfig();

    /**
     * 该渠道关键配置是否齐全（可直接调用渠道 API）。基于 {@link #missingConfig()} 判定。
     *
     * @return true 表示配置齐全
     */
    default boolean isConfigured() {
        List<String> missing = missingConfig();
        return missing == null || missing.isEmpty();
    }

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
