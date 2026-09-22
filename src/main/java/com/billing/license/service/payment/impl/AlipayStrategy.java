package com.billing.license.service.payment.impl;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.*;
import com.billing.license.service.payment.util.FormParamParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 加签模式：PUBLIC_KEY（普通公钥）/ CERT（公钥证书）。留空则自动判定（见 {@link #isCertMode()}）。
     * 两种模式不兼容：证书模式的请求会额外携带 app_cert_sn / alipay_root_cert_sn。
     * 应用在开放平台选了哪种加签方式，这里就必须与之对齐，否则网关返回 isv.invalid-signature。
     */
    @Value("${payment.alipay.sign-mode:}")
    private String signMode;

    /** 证书模式：应用公钥证书（appCertPublicKey_*.crt）路径 */
    @Value("${payment.alipay.app-cert-path:}")
    private String appCertPath;

    /** 证书模式：支付宝公钥证书（alipayCertPublicKey_RSA2.crt）路径 */
    @Value("${payment.alipay.alipay-cert-path:}")
    private String alipayCertPath;

    /** 证书模式：支付宝根证书（alipayRootCert.crt）路径 */
    @Value("${payment.alipay.root-cert-path:}")
    private String rootCertPath;

    /** 是否走公钥证书模式：显式配置优先；留空则三份证书路径齐全即判定为证书模式 */
    private boolean isCertMode() {
        if (StringUtils.hasText(signMode)) {
            return "CERT".equalsIgnoreCase(signMode.trim());
        }
        return StringUtils.hasText(appCertPath)
                && StringUtils.hasText(alipayCertPath)
                && StringUtils.hasText(rootCertPath);
    }

    /**
     * 证书模式启动自检：校验三份证书是否存在、是否过期。
     * 证书过期不会让本地报错，而是到网关侧才被拒（表现为下单失败/404/invalid-signature），
     * 排查成本很高，故在启动时就显式告警。
     */
    @PostConstruct
    public void checkCertificates() {
        if (!isCertMode()) {
            return;
        }
        String[][] certs = {
            {"应用公钥证书", appCertPath},
            {"支付宝公钥证书", alipayCertPath},
            {"支付宝根证书", rootCertPath}
        };
        for (String[] item : certs) {
            String name = item[0];
            String path = item[1];
            File file = new File(path);
            if (!file.exists()) {
                logger.error("支付宝证书模式自检：{} 文件不存在，path={}", name, path);
                continue;
            }
            try (InputStream in = new FileInputStream(file)) {
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                if (cert.getNotAfter().before(new java.util.Date())) {
                    logger.error("支付宝证书模式自检：{} 已过期（有效期至 {}），请到开放平台重新下载，"
                            + "否则下单会被网关拒绝", name, cert.getNotAfter());
                }
            } catch (Exception e) {
                logger.warn("支付宝证书模式自检：{} 解析失败，path={}", name, path, e);
            }
        }
    }

    private AlipayClient getAlipayClient() throws AlipayApiException {
        if (!isCertMode()) {
            return new DefaultAlipayClient(gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, signType);
        }
        CertAlipayRequest certRequest = new CertAlipayRequest();
        certRequest.setServerUrl(gatewayUrl);
        certRequest.setAppId(appId);
        certRequest.setPrivateKey(privateKey);
        certRequest.setFormat("json");
        certRequest.setCharset("UTF-8");
        certRequest.setSignType(signType);
        certRequest.setCertPath(appCertPath);
        certRequest.setAlipayPublicCertPath(alipayCertPath);
        certRequest.setRootCertPath(rootCertPath);
        return new DefaultAlipayClient(certRequest);
    }

    /**
     * 按加签模式执行请求：证书模式必须走 {@code certificateExecute}，
     * 直接调 {@code execute} 会被 SDK 拒绝（AlipayApiException：证书模式下请改为调用 certificateExecute）。
     */
    private <T extends AlipayResponse> T doExecute(AlipayClient client, AlipayRequest<T> request) throws AlipayApiException {
        return isCertMode() ? client.certificateExecute(request) : client.execute(request);
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
                
                AlipayTradePrecreateResponse alipayResponse = doExecute(client, request);

                if (alipayResponse == null) {
                    // 网关无响应/空响应时 SDK 可能返回 null，直接 isSuccess() 会 NPE 并裸奔成 500
                    logger.error("支付宝预下单返回空响应：orderNo={}", order.getOrderNo());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("支付宝接口无响应（网关未返回结果）");
                } else if (alipayResponse.isSuccess()) {
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
                
                // 注：SDK 只提供了 certificateExecute（发起 HTTP 请求类），没有 pageCertificateExecute。
                // 网页支付仅生成自动提交表单、不发起 HTTP 请求，故沿用 pageExecute。
                // 若后续证书模式下网页支付被 SDK 以同样理由拒绝，需再适配（当前扫码场景不走此分支）。
                AlipayTradePagePayResponse alipayResponse = client.pageExecute(request);

                if (alipayResponse == null) {
                    logger.error("支付宝网页支付返回空响应：orderNo={}", order.getOrderNo());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("支付宝接口无响应（网关未返回结果）");
                } else if (alipayResponse.isSuccess()) {
                    response.setRedirectUrl(alipayResponse.getBody());
                    logger.info("支付宝网页支付表单生成成功");
                } else {
                    logger.error("支付宝网页支付失败：code={}, msg={}", alipayResponse.getCode(), alipayResponse.getMsg());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage(alipayResponse.getMsg());
                }
            }
            
        } catch (Exception e) {
            // 与其余渠道策略（微信/Stripe/Paddle/PayPal 均为 catch Exception）保持一致：
            // 只捕获 AlipayApiException 会让「execute() 返回 null → NPE」「签名/验签运行时异常」等
            // 非受检异常裸奔到 GlobalExceptionHandler，前端只看到 traceId、看不到真因（500）。
            // 此处统一降级为 FAILED + 明确原因，由 CheckoutService 抛出 PAYMENT_CREATE_FAILED 显式反馈。
            logger.error("支付宝 API 调用异常：orderNo={}", order.getOrderNo(), e);
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage("支付宝接口调用失败（" + e.getClass().getSimpleName() + "）：" + e.getMessage());
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
            
            AlipayTradeQueryResponse response = doExecute(client, request);
            
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
            
        } catch (Exception e) {
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

            // 使用支付宝 SDK 验签：证书模式用支付宝公钥证书校验，公钥模式用支付宝公钥
            boolean isValid = isCertMode()
                ? AlipaySignature.rsaCertCheckV1(
                    params,
                    alipayCertPath,
                    StandardCharsets.UTF_8.name(),
                    signType
                  )
                : AlipaySignature.rsaCheckV1(
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
            // 支付宝异步通知唯一 ID（notify_id），用作幂等去重键。
            // 退款通知与支付通知的 trade_no 相同，必须用 notify_id 区分，
            // 否则退款通知会被支付事件的去重键拦截、License 永不吊销（资损）。
            webhookPayload.setWebhookEventId(params.get("notify_id"));

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
            // 支付宝异步退款结果通知（notify_type=refund）：其 trade_status 仍为原交易 SUCCESS，
            // 必须按 notify_type/refund_status 判定，否则会被误判为支付成功而重复发货、且不会触发 License 吊销（资损）。
            String notifyType = params.get("notify_type");
            String refundStatus = params.get("refund_status");
            if ("refund".equals(notifyType) || (refundStatus != null && !refundStatus.isEmpty())) {
                // 退款成功（REFUND_SUCCESS）映射 REFUNDED 触发吊销；其余中间态（如 REFUND_PROCESSING）置 PENDING
                webhookPayload.setStatus("REFUND_SUCCESS".equals(refundStatus)
                        ? PaymentStatus.REFUNDED.name() : PaymentStatus.PENDING.name());
            } else if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
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
     * 支付宝必备配置：应用 ID、商户私钥（RSA2 签名）、支付宝公钥（回调验签）、异步通知地址。
     * 缺任意一项都会在真实下单或回调验签时失败。
     */
    @Override
    public List<String> missingConfig() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(appId)) {
            missing.add("payment.alipay.app-id");
        }
        if (!StringUtils.hasText(privateKey)) {
            missing.add("payment.alipay.private-key");
        }
        if (!StringUtils.hasText(notifyUrl)) {
            missing.add("payment.alipay.notify-url");
        }
        if (isCertMode()) {
            if (!StringUtils.hasText(appCertPath)) {
                missing.add("payment.alipay.app-cert-path");
            }
            if (!StringUtils.hasText(alipayCertPath)) {
                missing.add("payment.alipay.alipay-cert-path");
            }
            if (!StringUtils.hasText(rootCertPath)) {
                missing.add("payment.alipay.root-cert-path");
            }
        } else if (!StringUtils.hasText(alipayPublicKey)) {
            missing.add("payment.alipay.public-key");
        }
        return missing;
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

            AlipayTradeRefundResponse response = doExecute(client, request);
            if (response != null && response.isSuccess()) {
                logger.info("支付宝退款成功：orderNo={}, tradeNo={}", outTradeNo, response.getTradeNo());
                return true;
            }
            logger.error("支付宝退款失败：orderNo={}, code={}, msg={}",
                outTradeNo, response != null ? response.getCode() : "null",
                response != null ? response.getMsg() : "null");
            return false;
        } catch (Exception e) {
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
