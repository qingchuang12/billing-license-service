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

    /**
     * #26：按渠道侧订阅 ID 反查订阅（首充回调丢失后的自动重修入口）。
     *
     * <p>默认值返回 empty，表示「该渠道不支持查询」——重修路径随即退回人工告警，
     * 不会误判成「渠道确认无此订阅」。只有能从渠道 API 取回 {@code order_id} 的实现
     * （Stripe / Paddle）才需要覆写。
     *
     * <p>实现要求：查询失败、凭据缺失、订阅不存在一律返回 empty（不抛异常），
     * 让调用方能安全地把「查不到」统一当作「需人工」处理。
     *
     * @param subscriptionId 渠道侧订阅 ID
     * @return 查到的订阅信息；查不到或不支持时为 empty
     */
    default java.util.Optional<SubscriptionLookup> lookupSubscription(String subscriptionId) {
        return java.util.Optional.empty();
    }

    /**
     * 订阅自助取消：通知渠道「本周期结束后不再续费」（期末取消，权益保留到 {@code currentPeriodEnd}）。
     *
     * <p>默认值返回 false，表示「该渠道不支持自助取消」；调用方据此拒绝请求，
     * **不得把 false 当成已取消**——用户以为停了却仍被扣下一期，等同于静默资损（与 #25 缺 price id 拒单同理）。
     *
     * <p>实现要求（承继硬约束 16）：渠道 HTTP 一律有超时且在事务外调用；
     * 渠道返回未确认时返回 false（不抛异常），由调用方转成明确的业务错误。
     *
     * @param subscriptionId 渠道侧订阅 ID
     * @return true 表示渠道已确认期末取消；false 表示渠道未确认 / 不支持
     */
    default boolean cancelSubscriptionAtPeriodEnd(String subscriptionId) {
        return false;
    }
}
