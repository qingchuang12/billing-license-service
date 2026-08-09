package com.billing.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.service.payment.strategy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 微信支付策略实现 (API v3)
 * 支持 Native 支付（扫码）、JSAPI 支付、APP 支付
 */
@Service
public class WechatPayStrategy implements PaymentStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(WechatPayStrategy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String WECHAT_PAY_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/native";
    private static final String WECHAT_QUERY_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/%s/query";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    
    @Value("${payment.wechat.app-id:}")
    private String appId;
    
    @Value("${payment.wechat.mch-id:}")
    private String mchId;
    
    @Value("${payment.wechat.private-key:}")
    private String privateKeyPem;
    
    @Value("${payment.wechat.certificate:}")
    private String merchantCertificatePem;
    
    @Value("${payment.wechat.api-v3-key:}")
    private String apiV3Key;
    
    @Value("${payment.wechat.notify-url:}")
    private String notifyUrl;
    
    @Value("${payment.wechat.serial-no:}")
    private String serialNo;

    private PrivateKey getPrivateKey() throws Exception {
        String pemContent = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(pemContent);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private String generateSignature(String method, String url, String body, String nonce, long timestamp) throws Exception {
        String signContent = method + "\n" + url + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(getPrivateKey());
        sig.update(signContent.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    @Override
    public PaymentResponse createPayment(Order order) {
        logger.info("创建微信支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());
        
        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("wechat_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.WECHAT_PAY.name());
        
        try {
            HttpClient client = HttpClient.newHttpClient();
            String nonce = java.util.UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis() / 1000;
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("appid", appId);
            requestBody.put("mchid", mchId);
            requestBody.put("description", order.getTitle());
            requestBody.put("out_trade_no", order.getOrderNo());
            requestBody.put("notify_url", notifyUrl);
            
            Map<String, Object> amount = new HashMap<>();
            amount.put("total", order.getAmount().multiply(new BigDecimal("100")).setScale(0, RoundingMode.HALF_UP).intValue());
            amount.put("currency", "CNY");
            requestBody.put("amount", amount);
            
            if (StringUtils.hasText(order.getMachineCode())) {
                Map<String, String> sceneInfo = new HashMap<>();
                sceneInfo.put("device_id", order.getMachineCode());
                requestBody.put("scene_info", sceneInfo);
            }
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String signature = generateSignature("POST", "/v3/pay/transactions/native", jsonBody, nonce, timestamp);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WECHAT_PAY_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchId + "\",nonce_str=\"" + nonce + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + serialNo + "\"")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            
            if (result.has("code_url")) {
                String codeUrl = result.get("code_url").asText();
                response.setQrCode(codeUrl);
                
                Map<String, Object> extraParams = new HashMap<>();
                extraParams.put("appId", appId);
                extraParams.put("mchId", mchId);
                extraParams.put("outTradeNo", order.getOrderNo());
                extraParams.put("transactionId", result.has("transaction_id") ? result.get("transaction_id").asText() : null);
                extraParams.put("codeUrl", codeUrl);
                response.setExtraParams(extraParams);
                
                logger.info("微信支付预下单成功：codeUrl={}", codeUrl);
            } else {
                logger.error("微信支付预下单失败：{}", result.toString());
                response.setStatus(PaymentStatus.FAILED.name());
                response.setErrorMessage(result.has("message") ? result.get("message").asText() : "未知错误");
            }
            
        } catch (Exception e) {
            logger.error("创建微信支付订单失败", e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage(e.getMessage());
        }
        
        return response;
    }

    @Override
    public PaymentStatus queryPayment(String paymentId) {
        logger.info("查询微信支付状态：paymentId={}", paymentId);
        
        try {
            String outTradeNo = paymentId.replaceFirst("wechat_", "");
            HttpClient client = HttpClient.newHttpClient();
            String nonce = java.util.UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis() / 1000;
            String queryUrl = String.format(WECHAT_QUERY_URL, outTradeNo);
            
            String signature = generateSignature("GET", "/v3/pay/transactions/out-trade-no/" + outTradeNo + "/query", "", nonce, timestamp);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl + "?mchid=" + mchId))
                    .header("Accept", "application/json")
                    .header("Authorization", "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchId + "\",nonce_str=\"" + nonce + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + serialNo + "\"")
                    .GET()
                    .build();
            
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());
            
            if (result.has("trade_state")) {
                String tradeState = result.get("trade_state").asText();
                switch (tradeState) {
                    case "SUCCESS":
                        return PaymentStatus.SUCCESS;
                    case "NOTPAY":
                        return PaymentStatus.PENDING;
                    case "CLOSED":
                        return PaymentStatus.CANCELLED;
                    default:
                        return PaymentStatus.UNKNOWN;
                }
            }
            
            return PaymentStatus.UNKNOWN;
            
        } catch (Exception e) {
            logger.error("查询微信支付状态失败", e);
            return PaymentStatus.UNKNOWN;
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证微信支付回调签名");
        
        try {
            String timestamp = headers.get("Wechatpay-Timestamp");
            String nonce = headers.get("Wechatpay-Nonce");
            String serialNo = headers.get("Wechatpay-Serial");
            
            if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
                logger.error("微信回调缺少必要 Header");
                return false;
            }
            
            String signContent = timestamp + "\n" + nonce + "\n" + payload + "\n";
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(getPublicKeyFromCert(serialNo));
            sig.update(signContent.getBytes(StandardCharsets.UTF_8));
            
            boolean isValid = sig.verify(Base64.getDecoder().decode(signature));
            
            if (isValid) {
                logger.info("微信签名验证通过");
            } else {
                logger.error("微信签名验证失败");
            }
            
            return isValid;
            
        } catch (Exception e) {
            logger.error("微信签名验证异常", e);
            return false;
        }
    }

    private java.security.PublicKey getPublicKeyFromCert(String serialNo) throws Exception {
        // 简化处理：实际应从证书文件加载
        String pemContent = merchantCertificatePem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "").replaceAll("\\s+", "");
        byte[] certBytes = Base64.getDecoder().decode(pemContent);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate cert = cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
        return cert.getPublicKey();
    }

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析微信支付回调");
        
        WebhookPayload webhookPayload = new WebhookPayload();
        
        try {
            JsonNode root = objectMapper.readTree(payload);
            
            String eventType = root.has("event_type") ? root.get("event_type").asText() : "";
            JsonNode resource = root.has("resource") ? root.get("resource") : null;
            
            if (resource != null && resource.has("ciphertext")) {
                String ciphertext = resource.get("ciphertext").asText();
                String nonce = resource.get("nonce").asText();
                String associatedData = resource.has("associated_data") ? resource.get("associated_data").asText() : "";
                
                // 解密回调数据
                String decryptedData = decryptResource(ciphertext, nonce, associatedData);
                JsonNode data = objectMapper.readTree(decryptedData);
                
                String outTradeNo = data.has("out_trade_no") ? data.get("out_trade_no").asText() : "";
                String transactionId = data.has("transaction_id") ? data.get("transaction_id").asText() : "";
                String tradeState = data.has("trade_state") ? data.get("trade_state").asText() : "";
                
                webhookPayload.setOrderId(outTradeNo);
                webhookPayload.setPaymentId("wechat_" + outTradeNo);
                webhookPayload.setTransactionId(transactionId);
                
                if (data.has("amount")) {
                    JsonNode amountNode = data.get("amount");
                    if (amountNode.has("total")) {
                        webhookPayload.setAmount(new BigDecimal(amountNode.get("total").asInt()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
                    }
                    webhookPayload.setCurrency(amountNode.has("currency") ? amountNode.get("currency").asText() : "CNY");
                }
                
                if (data.has("success_time")) {
                    try {
                        LocalDateTime paymentTime = LocalDateTime.parse(data.get("success_time").asText(), TIME_FORMATTER);
                        webhookPayload.setTimestamp(paymentTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
                    } catch (Exception e) {
                        webhookPayload.setTimestamp(System.currentTimeMillis());
                    }
                } else {
                    webhookPayload.setTimestamp(System.currentTimeMillis());
                }
                
                if ("SUCCESS".equals(tradeState)) {
                    webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                } else if ("NOTPAY".equals(tradeState)) {
                    webhookPayload.setStatus(PaymentStatus.PENDING.name());
                } else {
                    webhookPayload.setStatus(PaymentStatus.FAILED.name());
                }
                
                Map<String, Object> rawData = new HashMap<>();
                rawData.put("decryptedData", decryptedData);
                webhookPayload.setRawData(rawData);
                
                logger.info("微信回调解析成功：orderId={}, status={}", outTradeNo, webhookPayload.getStatus());
            }
            
        } catch (Exception e) {
            logger.error("微信回调解析失败", e);
            webhookPayload.setStatus(PaymentStatus.FAILED.name());
        }
        
        return webhookPayload;
    }

    private String decryptResource(String ciphertext, String nonce, String associatedData) throws Exception {
        byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        
        byte[] encryptedData = Base64.getDecoder().decode(ciphertext);
        byte[] decryptedData = cipher.doFinal(encryptedData);
        
        return new String(decryptedData, StandardCharsets.UTF_8);
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.WECHAT_PAY;
    }
}
