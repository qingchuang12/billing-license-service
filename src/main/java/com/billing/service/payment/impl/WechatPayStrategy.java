package com.billing.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.service.payment.strategy.*;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpHeaders;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.core.http.HttpResponse;
import com.wechat.pay.java.core.http.MediaType;
import com.wechat.pay.java.core.http.RequestBody;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.core.util.NonceUtil;
// import com.billing.service.payment.v3.model.PrepayRequest;
// import com.billing.service.payment.v3.model.PrepayResponse;
// import com.billing.service.payment.v3.model.QueryOrderRequest;
// import com.billing.service.payment.v3.model.QueryOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 微信支付策略实现 (API v3)
 * 支持 Native 支付（扫码）和 JSAPI 支付
 */
@Service
public class WechatPayStrategy implements PaymentStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(WechatPayStrategy.class);
    
    @Value("${payment.wechat.app-id:}")
    private String appId;
    
    @Value("${payment.wechat.mch-id:}")
    private String mchId;
    
    @Value("${payment.wechat.api-key:}")
    private String apiKey;
    
    @Value("${payment.wechat.private-key-path:}")
    private String privateKeyPath;
    
    @Value("${payment.wechat.certificate-path:}")
    private String certificatePath;
    
    @Value("${payment.wechat.notify-url:}")
    private String notifyUrl;
    
    @Value("${payment.wechat.api-v3-key:}")
    private String apiV3Key;

    private Config getConfig() {
        return new RSAAutoCertificateConfig.Builder()
            .merchantId(mchId)
            .privateKeyFromPath(privateKeyPath)
            .apiV3Key(apiV3Key)
            .certificateFromPath(certificatePath)
            .build();
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建微信支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());
        
        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("wechat_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.WECHAT_PAY.name());
        
        try {
            Config config = getConfig();
            HttpClient httpClient = new HttpClient.Builder().config(config).build();
            
            // 构建 Native 下单请求
            String requestUrl = "https://api.mch.weixin.qq.com/v3/pay/transactions/native";
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("appid", appId);
            requestBody.put("mchid", mchId);
            requestBody.put("description", order.getTitle());
            requestBody.put("out_trade_no", order.getOrderNo());
            requestBody.put("notify_url", notifyUrl);
            
            if (StringUtils.hasText(order.getMachineCode())) {
                requestBody.put("attach", order.getMachineCode());
            }
            
            Map<String, Object> amount = new HashMap<>();
            amount.put("total", order.getAmount().multiply(new BigDecimal("100")).intValue());
            amount.put("currency", "CNY");
            requestBody.put("amount", amount);
            
            HttpRequest httpRequest = new HttpRequest.Builder()
                .url(requestUrl)
                .method(HttpRequest.Method.POST)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .body(RequestBody.fromJson(com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestBody)))
                .build();
            
            HttpResponse httpResponse = httpClient.execute(httpRequest);
            
            if (httpResponse.getStatusCode() == 200 || httpResponse.getStatusCode() == 207) {
                String responseBody = httpResponse.getBody();
                Map<String, Object> result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(responseBody, Map.class);
                
                String codeUrl = (String) result.get("code_url");
                String prepayId = (String) result.get("prepay_id");
                
                response.setQrCode(codeUrl);
                response.setExtraParams(buildExtraParams(prepayId));
                logger.info("微信 Native 下单成功：codeUrl={}", codeUrl);
            } else {
                logger.error("微信下单失败：code={}, body={}", httpResponse.getStatusCode(), httpResponse.getBody());
                response.setStatus(PaymentStatus.FAILED.name());
                response.setErrorMessage("微信下单失败：" + httpResponse.getBody());
            }
            
        } catch (Exception e) {
            logger.error("微信支付 API 调用异常", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage("微信支付接口调用失败：" + e.getMessage());
        }
        
        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询微信支付状态：paymentId={}", paymentId);
        
        try {
            Config config = getConfig();
            HttpClient httpClient = new HttpClient.Builder().config(config).build();
            
            String outTradeNo = paymentId.replaceFirst("wechat_", "");
            String requestUrl = String.format(
                "https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/%s?mchid=%s",
                outTradeNo, mchId
            );
            
            HttpRequest httpRequest = new HttpRequest.Builder()
                .url(requestUrl)
                .method(HttpRequest.Method.GET)
                .addHeader("Accept", "application/json")
                .build();
            
            HttpResponse httpResponse = httpClient.execute(httpRequest);
            
            if (httpResponse.getStatusCode() == 200 || httpResponse.getStatusCode() == 207) {
                Map<String, Object> result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(httpResponse.getBody(), Map.class);
                
                String tradeState = (String) result.get("trade_state");
                switch (tradeState) {
                    case "SUCCESS":
                        return PaymentStatus.SUCCESS;
                    case "NOTPAY":
                        return PaymentStatus.PENDING;
                    case "CLOSED":
                        return PaymentStatus.CANCELLED;
                    default:
                        return PaymentStatus.PENDING;
                }
            } else {
                logger.warn("微信查询失败：code={}", httpResponse.getStatusCode());
                return PaymentStatus.UNKNOWN;
            }
            
        } catch (Exception e) {
            logger.error("微信支付查询异常", e);
            return PaymentStatus.UNKNOWN;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证微信支付 Webhook 签名");
        
        String serialNumber = headers.get("Wechatpay-Serial");
        String timestamp = headers.get("Wechatpay-Timestamp");
        String nonce = headers.get("Wechatpay-Nonce");
        
        if (!StringUtils.hasText(signature) || !StringUtils.hasText(serialNumber)) {
            logger.error("微信签名参数不完整");
            return false;
        }
        
        try {
            RequestParam requestParam = new RequestParam.Builder()
                .serialNumber(serialNumber)
                .nonce(nonce)
                .signature(signature)
                .timestamp(timestamp)
                .body(payload)
                .build();
            
            NotificationParser parser = new NotificationParser(getConfig());
            parser.parse(requestParam, Object.class);
            
            logger.info("微信签名验证通过");
            return true;
            
        } catch (Exception e) {
            logger.error("微信签名验证失败", e);
            return false;
        }
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析微信支付 Webhook 回调");
        
        WebhookPayload webhookPayload = new WebhookPayload();
        
        try {
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(payload, Map.class);
            
            Map<String, Object> resource = (Map<String, Object>) data.get("resource");
            String algorithm = (String) resource.get("algorithm");
            String ciphertext = (String) resource.get("ciphertext");
            String associatedData = (String) resource.get("associated_data");
            String nonce = (String) resource.get("nonce");
            
            // 解密数据
            String decryptedData = decryptResource(algorithm, ciphertext, associatedData, nonce);
            Map<String, Object> decrypted = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(decryptedData, Map.class);
            
            String outTradeNo = (String) decrypted.get("out_trade_no");
            String transactionId = (String) decrypted.get("transaction_id");
            String tradeState = (String) decrypted.get("trade_state");
            Map<String, Object> amountData = (Map<String, Object>) decrypted.get("amount");
            int totalAmount = (Integer) amountData.get("total");
            
            webhookPayload.setOrderId(outTradeNo);
            webhookPayload.setPaymentId("wechat_" + outTradeNo);
            webhookPayload.setTransactionId(transactionId);
            webhookPayload.setAmount(new BigDecimal(totalAmount).divide(new BigDecimal("100")));
            webhookPayload.setCurrency("CNY");
            
            if ("SUCCESS".equals(tradeState)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
            } else if ("CLOSED".equals(tradeState)) {
                webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
            }
            
            webhookPayload.setRawData(decrypted);
            logger.info("微信回调解析成功：orderId={}, status={}", outTradeNo, webhookPayload.getStatus());
            
        } catch (Exception e) {
            logger.error("微信回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }
        
        return webhookPayload;
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.WECHAT_PAY;
    }
    
    private Map<String, String> buildExtraParams(String prepayId) {
        Map<String, String> extraParams = new HashMap<>();
        extraParams.put("prepayId", prepayId);
        extraParams.put("channel", "WECHAT");
        return extraParams;
    }
    
    private String decryptResource(String algorithm, String ciphertext, String associatedData, String nonce) {
        // 使用微信支付 SDK 的解密工具
        try {
            return com.wechat.pay.java.core.util.AesUtil.decryptToString(
                apiV3Key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ciphertext
            );
        } catch (Exception e) {
            throw new RuntimeException("解密失败", e);
        }
    }
}
