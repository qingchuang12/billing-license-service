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
     * 带产品级回跳上下文创建支付（默认忽略上下文，保持既有渠道行为）。
     *
     * <p>只有确实支持透传回跳的渠道才覆写；不支持的渠道必须继续用部署级配置或明确拒绝，
     * 不得把任意 URL 透传给渠道（开放跳转风险）。
     */
    default PaymentResponse createPayment(Order order, PaymentLinkContext context) {
        return createPayment(order);
    }

    /**
     * 查询支付状态
     * 
     * @param paymentId 支付ID
     * @return 支付状态
     */
    PaymentStatus queryPayment(String paymentId);

    /**
     * 主动查单（带金额/币种/订单关联证据）。默认退化为仅状态，调用方据此保持待核而不发货。
     */
    default PaymentQueryResult queryPaymentDetail(String paymentId) {
        return PaymentQueryResult.statusOnly(queryPayment(paymentId));
    }

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
     * 退款（管理员发起或平台退款回调）。
     *
     * <p>返回结构化结果：调用方必须区分「明确拒绝」「不支持」「结果未知」——
     * 结果未知时不得改发第二笔退款，只能按同一 {@code refundRequestId} 查询/重试。
     *
     * @param order           原始订单
     * @param paymentId       支付侧交易 ID（如 Stripe PaymentIntent、支付宝 trade_no）
     * @param amount          退款金额
     * @param refundRequestId 稳定的退款请求号（同一退款意图跨重试不变）
     */
    default RefundResult refund(Order order, String paymentId, java.math.BigDecimal amount,
                                String refundRequestId) {
        return RefundResult.unsupported("渠道未实现退款");
    }

    /**
     * 查询某次退款请求的结果（用于 UNKNOWN 后先查明再决定是否重试）。默认未知。
     */
    default RefundResult queryRefund(Order order, String paymentId, String refundRequestId) {
        return RefundResult.unknown("渠道未实现退款查询");
    }

    /**
     * 是否具备退款结果反查能力。默认 false：调用方不得对不支持的渠道无限轮询，
     * 只能标记为待人工核对。
     */
    default boolean supportsRefundQuery() {
        return false;
    }

    /**
     * 关闭「已过期但仍可能被支付」的未付款交易（#50）。
     *
     * <p>收银台会话本地过期后，用户手里的渠道页/二维码仍可能完成支付。支持关单的渠道
     * （支付宝 / 微信 / Stripe）必须在确认**未支付**后关闭交易；不支持关闭的渠道
     * （Paddle / PayPal / Creem）由渠道侧自然过期，调用方只能继续轮询并保留关联。
     *
     * <p>实现要求（承继硬约束 16）：渠道 HTTP 一律有超时且在事务外调用；渠道返回
     * 未确认时返回 {@link PaymentCloseResult.Status#UNKNOWN} 或 {@code REJECTED}，
     * **不得**把「关闭失败」当成已关闭——那会把已付款订单永久排除在补偿之外。
     *
     * @param paymentId 渠道侧交易/会话 ID（与 {@link #queryPaymentDetail} 同口径）
     * @return 关单结果；默认不支持
     */
    default PaymentCloseResult closeUnpaidPayment(String paymentId) {
        return PaymentCloseResult.unsupported("渠道未实现关单");
    }

    /**
     * 兼容旧 boolean 调用点（只读/测试用）。新代码必须用 {@link #refund}。
     */
    @Deprecated
    default boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        String legacyRequestId = "legacy-" + order.getOrderNo() + "-" + amount.toPlainString();
        return refund(order, paymentId, amount, legacyRequestId).isSuccess();
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
