package com.billing.license.service.payment.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.internal.util.AlipaySignature;
import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.FormParamParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付宝支付策略实现
 * 支持扫码支付（预下单）和网页支付
 */
@Service
public class AlipayStrategy implements PaymentStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(AlipayStrategy.class);
    
    @Value("${payment.alipay.app-id:}")
    private String appId;
    
    @Value("${payment.alipay.private-key:}")
    private String privateKey;
    
    @Value("${payment.alipay.public-key:}")
    private String alipayPublicKey;
    
    @Value("${payment.alipay.notify-url:}")
    private String notifyUrl;
    
    @Value("${payment.alipay.return-url:}")
    private String returnUrl;
    
    @Value("${payment.alipay.gateway-url:https://openapi.alipay.com/gateway.do}")
    private String gatewayUrl;
    
    @Value("${payment.alipay.sign-type:RSA2}")
    private String signType;

    private AlipayClient getAlipayClient() {
        return new DefaultAlipayClient(gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, signType);
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建支付宝支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());
        
        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("alipay_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.ALIPAY.name());
        
        try {
            AlipayClient client = getAlipayClient();
            
            // 根据订单类型选择支付方式：有 machineCode 用扫码，否则用网页
            if (StringUtils.hasText(order.getMachineCode())) {
                // 扫码支付（预下单）
                AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
                request.setNotifyUrl(notifyUrl);
                request.setReturnUrl(returnUrl);
                
                String bizContent = String.format(
                    "{" +
                    "\"out_trade_no\":\"%s\"," +
                    "\"total_amount\":\"%s\"," +
                    "\"subject\":\"%s\"," +
                    "\"body\":\"%s\"," +
                    "\"product_code\":\"FACE_TO_FACE_PAYMENT\"" +
                    "}",
                    order.getOrderNo(),
                    order.getAmount().setScale(2, RoundingMode.HALF_UP).toString(),
                    order.getTitle(),
                    order.getDescription() != null ? order.getDescription() : ""
                );
                request.setBizContent(bizContent);
                
                AlipayTradePrecreateResponse alipayResponse = client.execute(request);
                
                if (alipayResponse.isSuccess()) {
                    response.setQrCode(alipayResponse.getQrCode());
                    // 支付宝预下单响应中只有 outTradeNo，没有 tradeNo，tradeNo 在支付成功后才会有
                    response.setExtraParams(buildExtraParams(alipayResponse.getOutTradeNo(), null));
                    logger.info("支付宝预下单成功：qrCode={}", alipayResponse.getQrCode());
                } else {
                    logger.error("支付宝预下单失败：code={}, msg={}", alipayResponse.getCode(), alipayResponse.getMsg());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage(alipayResponse.getMsg());
                }
            } else {
                // 网页支付
                AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
                request.setNotifyUrl(notifyUrl);
                request.setReturnUrl(returnUrl);
                
                String bizContent = String.format(
                    "{" +
                    "\"out_trade_no\":\"%s\"," +
                    "\"total_amount\":\"%s\"," +
                    "\"subject\":\"%s\"," +
                    "\"body\":\"%s\"," +
                    "\"product_code\":\"FAST_INSTANT_TRADE_PAY\"" +
                    "}",
                    order.getOrderNo(),
                    order.getAmount().setScale(2, RoundingMode.HALF_UP).toString(),
                    order.getTitle(),
                    order.getDescription() != null ? order.getDescription() : ""
                );
                request.setBizContent(bizContent);
                
                AlipayTradePagePayResponse alipayResponse = client.pageExecute(request);
                
                if (alipayResponse.isSuccess()) {
                    response.setRedirectUrl(alipayResponse.getBody());
                    logger.info("支付宝网页支付表单生成成功");
                } else {
                    logger.error("支付宝网页支付失败：code={}, msg={}", alipayResponse.getCode(), alipayResponse.getMsg());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage(alipayResponse.getMsg());
                }
            }
            
        } catch (AlipayApiException e) {
            logger.error("支付宝 API 调用异常", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage("支付宝接口调用失败：" + e.getMessage());
        }
        
        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询支付宝支付状态：paymentId={}", paymentId);
        
        try {
            AlipayClient client = getAlipayClient();
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            
            String outTradeNo = paymentId.replaceFirst("alipay_", "");
            request.setBizContent("{\"out_trade_no\":\"" + outTradeNo + "\"}");
            
            AlipayTradeQueryResponse response = client.execute(request);
            
            if (response.isSuccess()) {
                String tradeStatus = response.getTradeStatus();
                switch (tradeStatus) {
                    case "TRADE_SUCCESS":
                    case "TRADE_FINISHED":
                        return PaymentStatus.SUCCESS;
                    case "WAIT_BUYER_PAY":
                        return PaymentStatus.PENDING;
                    case "TRADE_CLOSED":
                        return PaymentStatus.CANCELLED;
                    default:
                        return PaymentStatus.PENDING;
                }
            } else {
                logger.warn("支付宝查询失败：code={}, msg={}", response.getCode(), response.getMsg());
                return PaymentStatus.UNKNOWN;
            }
            
        } catch (AlipayApiException e) {
            logger.error("支付宝查询异常", e);
            return PaymentStatus.UNKNOWN;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证支付宝 Webhook 签名");
        
        if (!StringUtils.hasText(signature)) {
            logger.error("支付宝签名为空");
            return false;
        }
        
        try {
            // 解析回调参数（含 sign/sign_type，保留原样）
            Map<String, String> params = FormParamParser.parse(payload);

            // 注意：不要在此处 remove("sign")/remove("sign_type")——支付宝 SDK 的
            // rsaCheckV1 内部第一步就是从 params 取 sign，随后 getSignCheckContentV1 自行剔除。
            // 业务侧提前 remove 会让 SDK 取到 null → 验签必然失败。

            // 使用支付宝 SDK 验签
            boolean isValid = AlipaySignature.rsaCheckV1(
                params,
                alipayPublicKey,
                StandardCharsets.UTF_8.name(),
                signType
            );
            
            if (isValid) {
                logger.info("支付宝签名验证通过");
            } else {
                logger.error("支付宝签名验证失败");
            }
            
            return isValid;
            
        } catch (Exception e) {
            logger.error("支付宝签名验证异常", e);
            return false;
        }
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析支付宝 Webhook 回调");
        
        WebhookPayload webhookPayload = new WebhookPayload();
        
        try {
            Map<String, String> params = FormParamParser.parse(payload);

            String outTradeNo = params.get("out_trade_no");
            String tradeNo = params.get("trade_no");
            String tradeStatus = params.get("trade_status");
            String totalAmount = params.get("total_amount");
            String buyerId = params.get("buyer_id");
            String gmtPayment = params.get("gmt_payment");
            
            webhookPayload.setOrderId(outTradeNo);
            webhookPayload.setPaymentId("alipay_" + outTradeNo);
            webhookPayload.setTransactionId(tradeNo);
            webhookPayload.setAmount(new BigDecimal(totalAmount != null ? totalAmount : "0"));
            webhookPayload.setCurrency("CNY");
            webhookPayload.setBuyerId(buyerId);
            
            // 解析支付时间
            if (StringUtils.hasText(gmtPayment)) {
                try {
                    java.time.LocalDateTime paymentTime = java.time.LocalDateTime.parse(
                        gmtPayment.replace(" ", "T")
                    );
                    webhookPayload.setTimestamp(paymentTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                } catch (Exception e) {
                    webhookPayload.setTimestamp(System.currentTimeMillis());
                }
            } else {
                webhookPayload.setTimestamp(System.currentTimeMillis());
            }
            
            // 转换状态
            if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
            } else if ("TRADE_CLOSED".equals(tradeStatus)) {
                webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
            }
            
            // 原始数据用于对账
            Map<String, Object> rawData = new HashMap<>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                rawData.put(entry.getKey(), entry.getValue());
            }
            webhookPayload.setRawData(rawData);
            
            logger.info("支付宝回调解析成功：orderId={}, status={}", outTradeNo, webhookPayload.getStatus());
            
        } catch (Exception e) {
            logger.error("支付宝回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }
        
        return webhookPayload;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.ALIPAY;
    }

    /**
     * H5：支付宝退款（统一收单交易退款接口）。
     * 未配置或调用失败时返回 false（绝不谎报成功），由调用方决定是否标记退款。
     */
    @Override
    public boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(privateKey) || !StringUtils.hasText(alipayPublicKey)) {
            logger.warn("支付宝未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return false;
        }
        try {
            AlipayClient client = getAlipayClient();
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            String outTradeNo = order.getOrderNo();
            String bizContent = String.format(
                "{\"out_trade_no\":\"%s\",\"refund_amount\":\"%s\",\"refund_reason\":\"%s\"}",
                outTradeNo,
                amount.setScale(2, RoundingMode.HALF_UP).toString(),
                "管理员退款");
            request.setBizContent(bizContent);

            AlipayTradeRefundResponse response = client.execute(request);
            if (response != null && response.isSuccess()) {
                logger.info("支付宝退款成功：orderNo={}, tradeNo={}", outTradeNo, response.getTradeNo());
                return true;
            }
            logger.error("支付宝退款失败：orderNo={}, code={}, msg={}",
                outTradeNo, response != null ? response.getCode() : "null",
                response != null ? response.getMsg() : "null");
            return false;
        } catch (AlipayApiException e) {
            logger.error("支付宝退款异常：orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }
    
    /**
     * 构建额外参数
     */
    private Map<String, Object> buildExtraParams(String outTradeNo, String tradeNo) {
        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("outTradeNo", outTradeNo);
        extraParams.put("tradeNo", tradeNo);
        extraParams.put("channel", "ALIPAY");
        return extraParams;
    }
}
