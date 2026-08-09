package com.billing.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.service.payment.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 云闪付/银联支付策略实现（国内支付）
 * 
 * 推荐通过聚合支付接入，直连较为复杂
 */
@Service
public class UnionPayStrategy implements PaymentStrategy {

    private static final Logger logger = LoggerFactory.getLogger(UnionPayStrategy.class);

    @Value("${payment.unionpay.merchant-id:}")
    private String merchantId;

    @Value("${payment.unionpay.acquirer-id:}")
    private String acquirerId;

    @Value("${payment.unionpay.private-key-path:}")
    private String privateKeyPath;

    @Value("${payment.unionpay.public-key-path:}")
    private String publicKeyPath;

    @Value("${payment.unionpay.notify-url:}")
    private String notifyUrl;

    @Value("${payment.unionpay.gateway-url:https://gateway.95516.com}")
    private String gatewayUrl;

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建云闪付支付订单：orderId={}", order.getOrderNo());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("unionpay_" + order.getOrderNo());
        response.setStatus("PENDING");

        // TODO: 集成银联支付API
        // 云闪付支付方式：
        // 1. 网关支付（跳转银联页面）
        // 2. 二维码支付（当面付）
        // 3. APP支付
        //
        // 示例伪代码（网关支付）：
        // Map<String, String> params = new HashMap<>();
        // params.put("version", "5.1.0");
        // params.put("signMethod", "01"); // RSA
        // params.put("txnType", "01"); // 消费
        // params.put("txnSubType", "00");
        // params.put("bizType", "000201");
        // params.put("accessType", "0"); // 0-商户，1-机构
        // params.put("channelType", "07"); // 07-互联网
        // params.put("merId", merchantId);
        // params.put("orderId", order.getOrderNo());
        // params.put("txnTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        // params.put("txnAmt", order.getAmount().multiply(new BigDecimal("100")).longValue() + "");
        // params.put("currencyCode", "156"); // CNY
        // params.put("backUrl", notifyUrl);
        //
        // // 签名
        // String sign = SignUtils.sign(params, privateKeyPath);
        // params.put("signature", sign);
        //
        // // 构建跳转URL
        // String payUrl = gatewayUrl + "/gateway/api/frontTransReq.do";
        // response.setPayUrl(buildPostForm(payUrl, params));

        // 模拟返回支付链接
        response.setPayUrl(gatewayUrl + "/mock/pay?orderId=" + order.getOrderNo());

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("merchantId", merchantId);
        extraParams.put("acquirerId", acquirerId);
        response.setExtraParams(extraParams);

        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询云闪付支付状态：paymentId={}", paymentId);

        // TODO: 调用银联查询接口
        // POST /gateway/api/queryTransReq.do

        return PaymentStatus.PENDING;
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证云闪付 Webhook 签名");

        // TODO: 使用银联公钥验证签名
        // 银联回调验签逻辑：
        // 1. 从回调参数中获取 signature
        // 2. 去除 signature 字段后，其余参数按字典序排序拼接
        // 3. 使用银联公钥进行 RSA 验签

        try {
            // 伪代码示例：
            // SignUtils.verify(payload, signature, publicKeyPath);

            logger.warn("开发环境：跳过云闪付签名验证");
            return true;
        } catch (Exception e) {
            logger.error("云闪付签名验证失败", e);
            return false;
        }
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析云闪付 Webhook 回调");

        WebhookPayload webhookPayload = new WebhookPayload();

        // TODO: 解析银联回调参数
        // 银联回调格式为 form表单
        // 关键字段：
        // - orderId: 商户订单号
        // - txnTraceNo: 交易流水号
        // - txnTraceDt: 交易日期
        // - respCode: 响应码（00表示成功）
        // - txnAmt: 交易金额

        // 模拟解析
        webhookPayload.setOrderId("ORDER_123");
        webhookPayload.setPaymentId("unionpay_ORDER_123");
        webhookPayload.setStatus("SUCCESS");
        webhookPayload.setTransactionId("20240101123456789");
        webhookPayload.setTimestamp(System.currentTimeMillis());

        return webhookPayload;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.UNIONPAY;
    }
}
