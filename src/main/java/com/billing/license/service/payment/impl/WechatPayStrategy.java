package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 微信支付策略实现 (API v3)
 * 支持 Native 支付（扫码）、JSAPI 支付、APP 支付
 *
 * 关键安全约定（微信支付 API v3）：
 * 1. 上行请求用【商户 API 私钥】签名，Authorization 头携带【商户证书序列号】。
 * 2. 下行回调/应答用【微信支付平台证书】验签与解密；平台证书须动态下载并平滑轮换，
 *    不可使用商户证书验签（旧实现误用商户证书，必然验签失败且安全模型错误）。
 * 3. 敏感资源（回调报文、平台证书）用 APIv3 密钥 AES-256-GCM 解密，associated_data 作为 GCM AAD。
 */
@Service
public class WechatPayStrategy implements com.billing.license.service.payment.strategy.PaymentStrategy {

    private static final Logger logger = LoggerFactory.getLogger(WechatPayStrategy.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String WECHAT_PAY_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/native";
    private static final String WECHAT_QUERY_URL = "https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/%s/query";
    private static final String WECHAT_CERT_URL = "https://api.mch.weixin.qq.com/v3/certificates";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    /** 回调验签时间戳允许的最大偏移（±5 分钟，防重放），单位毫秒 */
    private static final long MAX_TIMESTAMP_SKEW_MS = 5 * 60 * 1000L;

    @Value("${payment.wechat.app-id:}")
    private String appId;

    @Value("${payment.wechat.mch-id:}")
    private String mchId;

    /** APIv3 密钥：用于解密回调报文与平台证书 */
    @Value("${payment.wechat.api-key:}")
    private String apiV3Key;

    /** 商户 API 私钥文件路径（apiclient_key.pem），用于上行请求签名 */
    @Value("${payment.wechat.private-key-path:}")
    private String privateKeyPath;

    /** 商户证书文件路径（apiclient_cert.pem），用于提取商户证书序列号 */
    @Value("${payment.wechat.certificate-path:}")
    private String certificatePath;

    @Value("${payment.wechat.notify-url:}")
    private String notifyUrl;

    /** 商户 API 私钥（启动时加载，缓存复用） */
    private PrivateKey merchantPrivateKey;
    /** 商户证书序列号（启动时从商户证书提取，上行签名授权头使用） */
    private String merchantSerialNo;
    /** 是否已正确加载商户密钥/证书：未配置则本渠道不可用，但不阻塞应用启动 */
    private boolean configured = false;
    /** 平台证书缓存：serial_no -> X509Certificate；线程安全，支持平滑轮换 */
    private final Map<String, X509Certificate> platformCerts = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (!StringUtils.hasText(privateKeyPath) || !StringUtils.hasText(certificatePath)) {
            logger.warn("微信支付未配置（private-key-path/certificate-path 为空），本渠道暂不可用；配置后重启或触发证书刷新即可启用");
            return;
        }
        try {
            this.merchantPrivateKey = loadPrivateKey(privateKeyPath);
            X509Certificate merchantCert = loadCertificate(certificatePath);
            this.merchantSerialNo = merchantCert.getSerialNumber().toString(16).toUpperCase();
            this.configured = true;
            // 启动即拉取平台证书，避免首个回调因缺证书而验签失败
            refreshPlatformCertificates();
        } catch (Exception e) {
            logger.error("微信支付初始化失败（商户密钥/证书加载或平台证书下载异常）：{}", e.getMessage(), e);
            this.configured = false;
        }
    }

    // ---------- 商户密钥/证书加载 ----------

    private PrivateKey loadPrivateKey(String path) throws Exception {
        String pem = stripPem(Files.readString(Path.of(path)), "PRIVATE KEY");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem));
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private X509Certificate loadCertificate(String path) throws Exception {
        String pem = stripPem(Files.readString(Path.of(path)), "CERTIFICATE");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(Base64.getDecoder().decode(pem)));
    }

    private static String stripPem(String content, String type) {
        return content
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s+", "");
    }

    private PrivateKey getPrivateKey() throws Exception {
        if (merchantPrivateKey == null) {
            throw new IllegalStateException("微信支付商户私钥未初始化（请检查 private-key-path 配置）");
        }
        return merchantPrivateKey;
    }

    // ---------- 上行请求签名 ----------

    private String generateSignature(String method, String url, String body, String nonce, long timestamp) throws Exception {
        String signContent = method + "\n" + url + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(getPrivateKey());
        sig.update(signContent.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private String buildAuthorization(String method, String url, String body) throws Exception {
        String nonce = java.util.UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = generateSignature(method, url, body, nonce, timestamp);
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + mchId + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + merchantSerialNo + "\"";
    }

    // ---------- 支付创建/查询 ----------

    @Override
    public PaymentResponse createPayment(com.billing.license.entity.Order order) {
        logger.info("创建微信支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("wechat_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.WECHAT_PAY.name());

        try {
            HttpClient client = HttpClient.newHttpClient();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("appid", appId);
            requestBody.put("mchid", mchId);
            requestBody.put("description", order.getTitle());
            requestBody.put("out_trade_no", order.getOrderNo());
            requestBody.put("notify_url", notifyUrl);

            Map<String, Object> amount = new HashMap<>();
            amount.put("total", order.getAmount().multiply(new BigDecimal("100")).setScale(0, java.math.RoundingMode.HALF_UP).intValue());
            amount.put("currency", "CNY");
            requestBody.put("amount", amount);

            // i10：微信 scene_info.device_id 用于上报下单设备标识；业务侧以 machineCode（机器码）充当该设备标识
            String deviceId = order.getMachineCode();
            if (StringUtils.hasText(deviceId)) {
                Map<String, String> sceneInfo = new HashMap<>();
                sceneInfo.put("device_id", deviceId);
                requestBody.put("scene_info", sceneInfo);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            String auth = buildAuthorization("POST", "/v3/pay/transactions/native", jsonBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WECHAT_PAY_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", auth)
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
            String queryUrl = String.format(WECHAT_QUERY_URL, outTradeNo);
            String auth = buildAuthorization("GET", "/v3/pay/transactions/out-trade-no/" + outTradeNo + "/query", "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl + "?mchid=" + mchId))
                    .header("Accept", "application/json")
                    .header("Authorization", auth)
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

    // ---------- 平台证书动态下载与轮换 ----------

    /**
     * 下载微信支付平台证书并缓存。证书到期前微信会同时下发新老两张，
     * 全部按 serial_no 缓存即可实现平滑轮换；首次或缺失时由验签路径触发。
     */
    public void refreshPlatformCertificates() {
        if (!configured) {
            logger.warn("微信支付未配置，跳过平台证书下载");
            return;
        }
        try {
            String auth = buildAuthorization("GET", "/v3/certificates", "");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WECHAT_CERT_URL))
                    .header("Accept", "application/json")
                    .header("Authorization", auth)
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(httpResponse.body());

            int loaded = 0;
            if (root.has("data")) {
                for (JsonNode entry : root.get("data")) {
                    String serialNo = entry.has("serial_no") ? entry.get("serial_no").asText() : null;
                    JsonNode enc = entry.has("encrypt_certificate") ? entry.get("encrypt_certificate") : null;
                    if (!StringUtils.hasText(serialNo) || enc == null) {
                        continue;
                    }
                    String ciphertext = enc.has("ciphertext") ? enc.get("ciphertext").asText() : "";
                    String nonce = enc.has("nonce") ? enc.get("nonce").asText() : "";
                    String associatedData = enc.has("associated_data") ? enc.get("associated_data").asText() : "";
                    String pem = decryptToString(apiV3Key, associatedData, nonce, ciphertext);
                    X509Certificate cert = parseCertificate(pem);
                    if (cert != null) {
                        platformCerts.put(serialNo, cert);
                        loaded++;
                    }
                }
            }
            logger.info("微信支付平台证书刷新完成：本次加载 {} 张，缓存共 {} 张", loaded, platformCerts.size());
        } catch (Exception e) {
            logger.error("微信支付平台证书下载失败（不影响已缓存证书验签）：{}", e.getMessage(), e);
        }
    }

    private X509Certificate parseCertificate(String pem) {
        try {
            String stripped = stripPem(pem, "CERTIFICATE");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(Base64.getDecoder().decode(stripped)));
        } catch (Exception e) {
            logger.error("解析平台证书 PEM 失败", e);
            return null;
        }
    }

    // ---------- 回调验签（使用平台证书公钥） ----------

    @Override
    public boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers) {
        logger.info("验证微信支付回调签名");

        try {
            String timestamp = headers.get("Wechatpay-Timestamp");
            String nonce = headers.get("Wechatpay-Nonce");
            String serialNo = headers.get("Wechatpay-Serial");

            if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                    || !StringUtils.hasText(serialNo) || !StringUtils.hasText(signature)) {
                logger.error("微信回调缺少必要 Header（Timestamp/Nonce/Serial/Signature）");
                return false;
            }

            // 重放防护：时间戳与当前时间偏差超过 ±5 分钟直接拒绝
            long tsMillis = Long.parseLong(timestamp) * 1000L;
            long skew = Math.abs(System.currentTimeMillis() - tsMillis);
            if (skew > MAX_TIMESTAMP_SKEW_MS) {
                logger.error("微信回调时间戳偏移过大（{}ms），疑似重放，拒绝验签", skew);
                return false;
            }

            X509Certificate cert = getPlatformCert(serialNo);
            if (cert == null) {
                // 缓存缺失：触发一次刷新后重试，兼容新序列号轮换
                refreshPlatformCertificates();
                cert = getPlatformCert(serialNo);
            }
            if (cert == null) {
                logger.error("微信回调序列号 {} 对应的平台证书未找到", serialNo);
                return false;
            }

            String signContent = timestamp + "\n" + nonce + "\n" + payload + "\n";
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(cert.getPublicKey());
            sig.update(signContent.getBytes(StandardCharsets.UTF_8));

            boolean isValid = sig.verify(Base64.getDecoder().decode(signature));
            if (isValid) {
                logger.info("微信签名验证通过（serial_no={}）", serialNo);
            } else {
                logger.error("微信签名验证失败（serial_no={}）", serialNo);
            }
            return isValid;

        } catch (NumberFormatException e) {
            logger.error("微信回调时间戳格式非法：{}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("微信签名验证异常", e);
            return false;
        }
    }

    private X509Certificate getPlatformCert(String serialNo) {
        return platformCerts.get(serialNo);
    }

    // ---------- 回调报文解析与解密 ----------

    @Override
    public WebhookPayload parseWebhookPayload(String payload) {
        logger.info("解析微信支付回调");

        WebhookPayload webhookPayload = new WebhookPayload();

        try {
            JsonNode root = objectMapper.readTree(payload);

            String eventType = root.has("event_type") ? root.get("event_type").asText() : "";
            webhookPayload.setEventType(eventType);

            JsonNode resource = root.has("resource") ? root.get("resource") : null;

            if (resource != null && resource.has("ciphertext")) {
                String ciphertext = resource.get("ciphertext").asText();
                String nonce = resource.get("nonce").asText();
                String associatedData = resource.has("associated_data") ? resource.get("associated_data").asText() : "";

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
                        webhookPayload.setAmount(new BigDecimal(amountNode.get("total").asInt())
                                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
                    }
                    webhookPayload.setCurrency(amountNode.has("currency") ? amountNode.get("currency").asText() : "CNY");
                }

                if (data.has("success_time")) {
                    try {
                        LocalDateTime paymentTime = LocalDateTime.parse(data.get("success_time").asText(), TIME_FORMATTER);
                        webhookPayload.setTimestamp(paymentTime.toInstant(ZoneOffset.of("+08:00")).toEpochMilli());
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

    /**
     * 解密微信回调 resource（AES-256-GCM，APIv3 密钥）。
     * 修复旧实现缺失 GCM AAD 的缺陷：associated_data 非空时必须作为 AAD 传入，否则解密失败。
     */
    private String decryptResource(String ciphertext, String nonce, String associatedData) throws Exception {
        return decryptToString(apiV3Key, associatedData, nonce, ciphertext);
    }

    private static String decryptToString(String apiV3Key, String associatedData, String nonce, String ciphertext) throws Exception {
        byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec spec = new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        if (StringUtils.hasText(associatedData)) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }

        byte[] encryptedData = Base64.getDecoder().decode(ciphertext);
        byte[] decryptedData = cipher.doFinal(encryptedData);

        return new String(decryptedData, StandardCharsets.UTF_8);
    }

    /**
     * H5：微信支付退款（API v3 退款接口 /v3/refund/domestic/refunds）。
     * 未配置或调用失败返回 false（绝不谎报成功）；成功受理（返回 refund_id）才返回 true。
     */
    @Override
    public boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        if (!configured) {
            logger.warn("微信支付未配置，无法发起退款：orderNo={}", order.getOrderNo());
            return false;
        }
        try {
            long totalFen = order.getAmount().multiply(new BigDecimal("100"))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
            long refundFen = amount.multiply(new BigDecimal("100"))
                    .setScale(0, RoundingMode.HALF_UP).longValue();
            String outRefundNo = order.getOrderNo() + "_rf" + System.nanoTime();

            Map<String, Object> amountMap = new HashMap<>();
            amountMap.put("currency", "CNY");
            amountMap.put("refund", refundFen);
            amountMap.put("total", totalFen);

            Map<String, Object> body = new HashMap<>();
            body.put("out_trade_no", order.getOrderNo());
            body.put("out_refund_no", outRefundNo);
            body.put("reason", "管理员退款");
            body.put("amount", amountMap);

            String jsonBody = objectMapper.writeValueAsString(body);
            String auth = buildAuthorization("POST", "/v3/refund/domestic/refunds", jsonBody);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mch.weixin.qq.com/v3/refund/domestic/refunds"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode result = objectMapper.readTree(httpResponse.body());

            if (result.has("refund_id")) {
                logger.info("微信退款受理成功：orderNo={}, refundId={}", order.getOrderNo(), result.get("refund_id").asText());
                return true;
            }
            logger.error("微信退款失败：orderNo={}, body={}", order.getOrderNo(), result.toString());
            return false;
        } catch (Exception e) {
            logger.error("微信退款异常：orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }

    @Override
    public PaymentMethod getPaymentMethod() {
        return PaymentMethod.WECHAT_PAY;
    }

    /**
     * 微信支付 API v3 必备配置：AppID、商户号、APIv3 密钥、商户私钥与商户证书路径、回调地址。
     * 私钥与商户证书还需文件真实存在且可解析（加载失败见启动日志中的初始化错误）。
     */
    @Override
    public List<String> missingConfig() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(appId)) {
            missing.add("payment.wechat.app-id");
        }
        if (!StringUtils.hasText(mchId)) {
            missing.add("payment.wechat.mch-id");
        }
        if (!StringUtils.hasText(apiV3Key)) {
            missing.add("payment.wechat.api-key");
        }
        if (!StringUtils.hasText(privateKeyPath)) {
            missing.add("payment.wechat.private-key-path");
        }
        if (!StringUtils.hasText(certificatePath)) {
            missing.add("payment.wechat.certificate-path");
        }
        if (!StringUtils.hasText(notifyUrl)) {
            missing.add("payment.wechat.notify-url");
        }
        return missing;
    }
}
