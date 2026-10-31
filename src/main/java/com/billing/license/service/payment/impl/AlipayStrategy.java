package com.billing.license.service.payment.impl;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradeFastpayRefundQueryRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradeFastpayRefundQueryResponse;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.billing.license.config.PaymentHttpClientConfig;
import com.billing.license.service.payment.PaymentFeature;
import com.billing.license.service.payment.PaymentSettingsService;
import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.*;
import com.billing.license.service.payment.util.FormParamParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * 支付宝支付策略实现
 * 支持扫码支付（预下单）和网页支付
 */
@Service
public class AlipayStrategy implements PaymentStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(AlipayStrategy.class);

    /**
     * 证书解析用的 provider。构造一次即可（它会注册上千个算法，别每次调用都 new）。
     * 见 {@link #inspectCertificates} 中关于国密 SM2 的说明。
     */
    private static final BouncyCastleProvider BC_PROVIDER = new BouncyCastleProvider();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * 期望的支付宝商户 PID（2088 开头）。证书模式下支付宝平台证书能验过**其它应用**的报文，
     * 故除验签外必须核对 app_id（必要时 seller_id），否则可被跨应用通知冒领发货。
     */
    @Value("${payment.alipay.seller-id:}")
    private String sellerId;

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

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    /** Web 端功能开关；单元测试无容器时为 null，按功能默认值放行。 */
    @Autowired(required = false)
    private PaymentSettingsService paymentSettingsService;

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
        List<String> problems = new ArrayList<>();
        for (String[] item : certs) {
            String name = item[0];
            String path = item[1];
            File file = new File(path);
            if (!file.exists()) {
                problems.add(name + " 文件不存在：" + path);
                continue;
            }
            for (String problem : inspectCertificates(file)) {
                problems.add(name + " " + problem);
            }
        }
        if (!problems.isEmpty()) {
            problems.forEach(p -> logger.error("支付宝证书模式自检：{}", p));
            if (isProdProfile()) {
                throw new IllegalStateException("支付宝证书自检失败（生产环境拒绝启动）："
                    + String.join("；", problems));
            }
        }
    }

    private boolean isProdProfile() {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return false;
        }
        for (String profile : activeProfiles.split(",")) {
            if ("prod".equalsIgnoreCase(profile.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 逐张检查证书文件内的<b>全部</b>证书，返回问题清单（空列表 = 无问题）。
     * package-private 以便单测直接断言。
     *
     * <p><b>为什么必须走 BouncyCastle</b>：支付宝根证书文件（{@code alipayRootCert.crt}）的
     * <b>第一张就是国密 SM2 根证书</b>（公钥 OID {@code 1.2.156.10197.1.301}），JDK 内置
     * provider 的命名曲线表不认它，用 {@code CertificateFactory.getInstance("X.509")}
     * 必然抛 {@code CertificateParsingException: Unknown named curve}。
     * 支付宝 SDK 自身也是走 BC 解析的（{@code AntCertificationUtil} 静态块注册 provider），
     * 故这里只是与它保持一致——<b>不是换算法，是补上缺失的 provider</b>。
     *
     * <p><b>为什么不把 provider 注册进 {@code Security}</b>：注册是全局副作用，且
     * {@code getInstance(type, provider)} 直接传实例即可工作，无需依赖 SDK 静态块的执行顺序。
     *
     * <p><b>为什么遍历全部而不是只取第一张</b>：根证书文件里有 4 张、支付宝公钥证书里有 2 张，
     * 只取首张会漏掉其余证书过期——自检就形同虚设。
     */
    List<String> inspectCertificates(File file) {
        List<String> problems = new ArrayList<>();
        try (InputStream in = new FileInputStream(file)) {
            Collection<? extends Certificate> certs =
                CertificateFactory.getInstance("X.509", BC_PROVIDER).generateCertificates(in);
            if (certs.isEmpty()) {
                problems.add("未解析出任何证书（文件可能不是 PEM 格式）");
                return problems;
            }
            int index = 0;
            for (Certificate item : certs) {
                index++;
                if (!(item instanceof X509Certificate cert)) {
                    problems.add("第 " + index + " 张不是 X.509 证书");
                    continue;
                }
                if (cert.getNotAfter().before(new Date())) {
                    problems.add("第 " + index + " 张已过期（" + cert.getSubjectX500Principal()
                        + "，有效期至 " + cert.getNotAfter() + "），请到开放平台重新下载，"
                        + "否则下单会被网关拒绝");
                }
            }
        } catch (Exception e) {
            problems.add("解析失败：" + e);
        }
        return problems;
    }

    /**
     * 复用的网关客户端：原实现每次调用都新建 {@link DefaultAlipayClient}，连接无法复用。
     * 配置在启动期注入、运行期不变，故首次取用时构建一次即可；测试仍可覆写本方法返回 mock。
     */
    private volatile AlipayClient cachedClient;

    /** 便于测试替换网关客户端；生产实现按加签模式返回默认或证书客户端。 */
    protected AlipayClient getAlipayClient() throws AlipayApiException {
        AlipayClient client = cachedClient;
        if (client == null) {
            synchronized (this) {
                client = cachedClient;
                if (client == null) {
                    client = buildClient();
                    cachedClient = client;
                }
            }
        }
        return client;
    }

    private AlipayClient buildClient() throws AlipayApiException {
        AlipayClient client;
        if (!isCertMode()) {
            client = new DefaultAlipayClient(gatewayUrl, appId, privateKey, "json", "UTF-8", alipayPublicKey, signType);
        } else {
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
            client = new DefaultAlipayClient(certRequest);
        }
        // 超时口径与其余四家渠道同源（PaymentHttpClientConfig）：连接 5s、单次请求 10s。
        // AlipayClient 接口不暴露超时设置，只能落在其 AbstractAlipayClient 实现上；
        // 不设时 SDK 默认值远大于渠道网关的重试窗口，对端卡住会拖住请求线程与 DB 连接。
        AbstractAlipayClient timed = (AbstractAlipayClient) client;
        timed.setConnectTimeout((int) PaymentHttpClientConfig.CONNECT_TIMEOUT.toMillis());
        timed.setReadTimeout((int) PaymentHttpClientConfig.REQUEST_TIMEOUT.toMillis());
        return client;
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
        return createPayment(order, PaymentLinkContext.empty());
    }

    @Override
    public PaymentResponse createPayment(Order order, PaymentLinkContext context) {
        logger.info("创建支付宝支付订单：orderId={}, amount={}", order.getOrderNo(), order.getAmount());

        PaymentResponse response = new PaymentResponse();
        response.setPaymentId("alipay_" + order.getOrderNo());
        response.setStatus(PaymentStatus.PENDING.name());
        response.setPaymentMethod(PaymentMethod.ALIPAY.name());
        // 支付宝只结算人民币：USD 订单即使数值相同也不能按 CNY 收款（否则等于按 1:1 汇率收款）
        if (order.getCurrency() != null && order.getCurrency() != Currency.CNY) {
            response.setStatus(PaymentStatus.FAILED.name());
            response.setErrorMessage("支付宝仅支持人民币（CNY）订单，当前为 " + order.getCurrency().code());
            return response;
        }

        String effectiveReturnUrl = (context != null && StringUtils.hasText(context.successUrl()))
                ? context.successUrl() : returnUrl;

        try {
            AlipayClient client = getAlipayClient();
            
            // 支付形态由请求/渠道能力决定，与机器码无关（#57）
            boolean useQr = context != null && context.paymentProduct() == PaymentProduct.QRCODE;
            boolean useWap = context != null && context.paymentProduct() == PaymentProduct.WAP;
            Integer installmentNum = context != null ? context.installmentNum() : null;
            if (useWap && paymentSettingsService != null
                    && !paymentSettingsService.isFeatureEnabled(PaymentFeature.ALIPAY_WAP)) {
                response.setStatus(PaymentStatus.FAILED.name());
                response.setErrorMessage("支付宝手机网站支付功能未启用");
                return response;
            }
            if (installmentNum != null) {
                if (!Set.of(3, 6, 12, 24).contains(installmentNum)) {
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("花呗分期期数仅支持 3/6/12/24");
                    return response;
                }
                if (paymentSettingsService != null
                        && !paymentSettingsService.isFeatureEnabled(PaymentFeature.ALIPAY_HUABEI_INSTALLMENT)) {
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("花呗分期功能未启用");
                    return response;
                }
                if (useQr) {
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("扫码支付不支持花呗分期，请选择手机网站或电脑网站支付");
                    return response;
                }
            }
            if (useWap) {
                AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
                request.setNotifyUrl(notifyUrl);
                request.setReturnUrl(effectiveReturnUrl);
                request.setBizContent(buildBizContent(order, "QUICK_WAP_WAY", context));
                AlipayTradeWapPayResponse alipayResponse = client.pageExecute(request, "GET");
                if (alipayResponse == null) {
                    logger.error("支付宝手机网站支付返回空响应：orderNo={}", order.getOrderNo());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("支付宝接口无响应（网关未返回结果）");
                } else if (alipayResponse.isSuccess()) {
                    String body = alipayResponse.getBody();
                    if (body == null || !(body.startsWith("http://") || body.startsWith("https://"))) {
                        logger.error("支付宝手机网站支付未返回可跳转 URL：orderNo={}", order.getOrderNo());
                        response.setStatus(PaymentStatus.FAILED.name());
                        response.setErrorMessage("支付宝手机网站支付未返回可跳转地址");
                    } else {
                        response.setRedirectUrl(body);
                        logger.info("支付宝手机网站支付链接生成成功");
                    }
                } else {
                    logger.error("支付宝手机网站支付失败：code={}, msg={}", alipayResponse.getCode(), alipayResponse.getMsg());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage(alipayResponse.getMsg());
                }
            } else if (useQr) {
                // 扫码支付（预下单）
                AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
                request.setNotifyUrl(notifyUrl);
                request.setReturnUrl(effectiveReturnUrl);
                
                request.setBizContent(buildBizContent(order, "FACE_TO_FACE_PAYMENT", context));
                
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
                request.setReturnUrl(effectiveReturnUrl);
                request.setBizContent(buildBizContent(order, "FAST_INSTANT_TRADE_PAY", context));

                // pageExecute(request, "GET") 返回的是**可直接跳转的网关 URL**；
                // 无参 pageExecute 返回自动提交 HTML 表单，塞进 redirectUrl 后前端 location.assign
                // 只会跳到一段 HTML 文本（无法支付）。这里统一取 URL 形态，并对返回值再校验。
                AlipayTradePagePayResponse alipayResponse = client.pageExecute(request, "GET");

                if (alipayResponse == null) {
                    logger.error("支付宝网页支付返回空响应：orderNo={}", order.getOrderNo());
                    response.setStatus(PaymentStatus.FAILED.name());
                    response.setErrorMessage("支付宝接口无响应（网关未返回结果）");
                } else if (alipayResponse.isSuccess()) {
                    String body = alipayResponse.getBody();
                    if (body == null || !(body.startsWith("http://") || body.startsWith("https://"))) {
                        logger.error("支付宝网页支付未返回可跳转 URL：orderNo={}, bodyPrefix={}",
                            order.getOrderNo(), body == null ? "null" : body.substring(0, Math.min(32, body.length())));
                        response.setStatus(PaymentStatus.FAILED.name());
                        response.setErrorMessage("支付宝网页支付未返回可跳转地址");
                    } else {
                        response.setRedirectUrl(body);
                        logger.info("支付宝网页支付链接生成成功");
                    }
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
        return queryPaymentDetail(paymentId).status();
    }

    @Override
    public PaymentQueryResult queryPaymentDetail(String paymentId) {
        logger.info("查询支付宝支付状态：paymentId={}", paymentId);

        try {
            AlipayClient client = getAlipayClient();
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();

            String outTradeNo = paymentId.replaceFirst("alipay_", "");
            Map<String, Object> biz = new HashMap<>();
            biz.put("out_trade_no", outTradeNo);
            request.setBizContent(OBJECT_MAPPER.writeValueAsString(biz));

            AlipayTradeQueryResponse response = doExecute(client, request);

            if (response == null || !response.isSuccess()) {
                logger.warn("支付宝查询失败：code={}, msg={}", response != null ? response.getCode() : "null",
                    response != null ? response.getMsg() : "null");
                return PaymentQueryResult.statusOnly(PaymentStatus.UNKNOWN);
            }
            String tradeStatus = response.getTradeStatus();
            PaymentStatus status;
            switch (tradeStatus) {
                case "TRADE_SUCCESS":
                case "TRADE_FINISHED":
                    status = PaymentStatus.SUCCESS;
                    break;
                case "WAIT_BUYER_PAY":
                    status = PaymentStatus.PENDING;
                    break;
                case "TRADE_CLOSED":
                    status = PaymentStatus.CANCELLED;
                    break;
                default:
                    status = PaymentStatus.PENDING;
            }
            BigDecimal amount = null;
            if (StringUtils.hasText(response.getTotalAmount())) {
                amount = new BigDecimal(response.getTotalAmount());
            }
            boolean verified = amount != null
                && StringUtils.hasText(response.getOutTradeNo())
                && outTradeNo.equals(response.getOutTradeNo());
            return new PaymentQueryResult(status, amount, "CNY", response.getTradeNo(),
                response.getOutTradeNo(), verified);
        } catch (Exception e) {
            logger.error("支付宝查询异常", e);
            return PaymentQueryResult.statusOnly(PaymentStatus.UNKNOWN);
        }
    }

    /**
     * #50：关闭未付款交易，防止收银台会话本地过期后用户仍用原二维码/链接付款。
     * 已支付/已关闭的交易会被支付宝拒绝（ACQ.TRADE_STATUS_ERROR），返回 REJECTED，
     * 由调用方下一轮查单确认是否其实已收款。
     */
    @Override
    public PaymentCloseResult closeUnpaidPayment(String paymentId) {
        logger.info("关闭支付宝交易：paymentId={}", paymentId);
        if (paymentId == null || paymentId.isBlank()) {
            return PaymentCloseResult.rejected("paymentId 为空");
        }
        try {
            AlipayClient client = getAlipayClient();
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            Map<String, Object> biz = new HashMap<>();
            biz.put("out_trade_no", paymentId.replaceFirst("alipay_", ""));
            request.setBizContent(OBJECT_MAPPER.writeValueAsString(biz));

            AlipayTradeCloseResponse response = doExecute(client, request);
            if (response != null && response.isSuccess()) {
                return PaymentCloseResult.closed("支付宝关单成功");
            }
            String code = response == null ? "空响应"
                : (response.getSubCode() == null ? "" : response.getSubCode() + ":")
                    + (response.getSubMsg() == null ? "" : response.getSubMsg());
            logger.warn("支付宝关单未确认：paymentId={}, code={}", paymentId, code);
            return PaymentCloseResult.rejected(code);
        } catch (Exception e) {
            logger.error("支付宝关单异常：paymentId={}", paymentId, e);
            return PaymentCloseResult.unknown(e.getMessage());
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
            
            if (!isValid) {
                logger.error("支付宝签名验证失败");
                return false;
            }

            // 证书模式下平台证书能验过其它应用的报文：验签后必须核对 app_id（及可选的 seller_id），
            // 否则别人的合法通知可被当成我们的已付款事件（收钱/发货归属错乱）。
            String notifiedAppId = params.get("app_id");
            if (!StringUtils.hasText(appId) || !appId.equals(notifiedAppId)) {
                logger.error("支付宝回调 app_id 不匹配：expected={}, actual={}", appId, notifiedAppId);
                return false;
            }
            // seller_id 必须是必填项：证书模式下平台证书能验过其它商户报文，
            // 只校验 app_id 仍可能被同应用的其他收款主体/伪造通知冒领发货。
            String notifiedSellerId = params.get("seller_id");
            if (!StringUtils.hasText(sellerId) || !sellerId.equals(notifiedSellerId)) {
                logger.error("支付宝回调 seller_id 缺失或不匹配：expected={}, actual={}",
                    sellerId, notifiedSellerId);
                return false;
            }
            logger.info("支付宝签名验证通过");
            return true;
            
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

            // 退款通知的唯一可靠判据是 refund_fee / gmt_refund 是否存在（官方 FAQ 4.11、4.13：
            // 订单超时关闭两者都没有）。不能用 notify_type=refund：主站异步通知的 notify_type 恒为
            // trade_status_sync；refund_status 是支付宝全球版字段，主站通知里没有。
            String refundFee = params.get("refund_fee");
            String gmtRefund = params.get("gmt_refund");
            String notifyType = params.get("notify_type");
            String refundStatus = params.get("refund_status");
            boolean refundNotice = StringUtils.hasText(refundFee)
                || StringUtils.hasText(gmtRefund)
                || "refund".equals(notifyType)
                || StringUtils.hasText(refundStatus);

            webhookPayload.setOrderId(outTradeNo);
            webhookPayload.setPaymentId("alipay_" + outTradeNo);
            webhookPayload.setTransactionId(tradeNo);
            // 金额缺失时保持 null（不要伪造 0）：成功事件缺金额必须被公共层拒绝，而不是按 0 元放行
            webhookPayload.setAmount(StringUtils.hasText(totalAmount) ? new BigDecimal(totalAmount) : null);
            webhookPayload.setCurrency("CNY");
            webhookPayload.setBuyerId(buyerId);
            // 支付宝异步通知唯一 ID（notify_id），用作幂等去重键。
            // 退款通知与支付通知的 trade_no 相同，必须用 notify_id 区分，
            // 否则退款通知会被支付事件的去重键拦截、License 永不吊销（资损）。
            // notify_id 缺失时，退款通知必须退到 out_biz_no（退款请求号，官方 FAQ 4.12）——
            // 同一订单的多次部分退款 trade_no 相同，落到 trade_no 上会让第二笔被静默去重。
            String notifyId = params.get("notify_id");
            webhookPayload.setWebhookEventId(StringUtils.hasText(notifyId)
                ? notifyId : (refundNotice ? params.get("out_biz_no") : null));

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
            if (refundNotice) {
                // 退款通知：全额退款 trade_status=TRADE_CLOSED（且带 gmt_close），部分退款的
                // trade_status 仍是 TRADE_SUCCESS（官方 FAQ 4.12）。两者都必须落 REFUND_SUCCEEDED——
                // 若按 trade_status 分流，全额退款会被当成「未付款关单」不撤权、部分退款会被当成
                // 「收款成功」重复发货，两条都是资损。
                if ("REFUND_FAIL".equals(refundStatus)) {
                    // 退款失败不代表资金已退，仅审计
                    webhookPayload.setStatus(PaymentStatus.PENDING.name());
                    webhookPayload.setEventKind(PaymentEventType.OTHER);
                } else {
                    webhookPayload.setStatus(PaymentStatus.REFUNDED.name());
                    webhookPayload.setEventKind(PaymentEventType.REFUND_SUCCEEDED);
                    if (StringUtils.hasText(refundFee)) {
                        try {
                            // 官方口径：refund_fee 是「总退款金额」而非本次退款额（FAQ 4.11）——
                            // 多次部分退款各投递一次且金额递增，按本次额入账会重复累计，
                            // 故标记为累计口径，由结算层按差额入账。
                            webhookPayload.setRefundAmount(new BigDecimal(refundFee));
                            webhookPayload.setRefundAmountCumulative(true);
                        } catch (NumberFormatException e) {
                            logger.warn("支付宝退款金额无法解析：refund_fee={}", refundFee);
                        }
                    }
                    webhookPayload.setRefundCurrency("CNY");
                }
            } else if ("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus)) {
                webhookPayload.setStatus(PaymentStatus.SUCCESS.name());
                webhookPayload.setEventKind(PaymentEventType.PAYMENT_SUCCEEDED);
            } else if ("TRADE_CLOSED".equals(tradeStatus)) {
                // 未付款交易关闭 ≠ 退款：只更新流水/关单，不得吊销权益
                webhookPayload.setStatus(PaymentStatus.CANCELLED.name());
                webhookPayload.setEventKind(PaymentEventType.PAYMENT_CANCELLED);
            } else {
                webhookPayload.setStatus(PaymentStatus.PENDING.name());
                webhookPayload.setEventKind(PaymentEventType.OTHER);
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
        if (!StringUtils.hasText(sellerId)) {
            missing.add("payment.alipay.seller-id");
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
     * 支付宝退款（统一收单交易退款接口）。
     *
     * <p>关键点：
     * <ul>
     *   <li>配置校验走 {@link #missingConfig()}（CERT 模式不再错误要求普通公钥）；</li>
     *   <li>带稳定的 {@code out_request_no}：同一次退款重试必须复用同一请求号，
     *       渠道据此幂等，避免「已受理但响应丢失 → 再退一次」；</li>
     *   <li>业务失败与网络异常分开返回 REJECTED / UNKNOWN，调用方不得对 UNKNOWN 直接重发退款。</li>
     * </ul>
     */
    @Override
    public RefundResult refund(Order order, String paymentId, java.math.BigDecimal amount,
                               String refundRequestId) {
        List<String> missing = missingConfig();
        if (!missing.isEmpty()) {
            logger.warn("支付宝未配置完整，无法发起退款：orderNo={}, missing={}", order.getOrderNo(), missing);
            return RefundResult.rejected("支付宝配置缺失：" + String.join(", ", missing));
        }
        try {
            AlipayClient client = getAlipayClient();
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            Map<String, Object> biz = new HashMap<>();
            biz.put("out_trade_no", order.getOrderNo());
            biz.put("refund_amount", amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
            biz.put("refund_reason", "管理员退款");
            biz.put("out_request_no", refundRequestId);
            request.setBizContent(OBJECT_MAPPER.writeValueAsString(biz));

            AlipayTradeRefundResponse response = doExecute(client, request);
            if (response == null) {
                return RefundResult.unknown("支付宝退款无响应");
            }
            if (response.isSuccess()) {
                logger.info("支付宝退款成功：orderNo={}, tradeNo={}, outRequestNo={}",
                    order.getOrderNo(), response.getTradeNo(), refundRequestId);
                return RefundResult.success(response.getTradeNo());
            }
            String code = response.getCode();
            String subCode = response.getSubCode();
            logger.error("支付宝退款失败：orderNo={}, code={}, subCode={}, msg={}",
                order.getOrderNo(), code, subCode, response.getMsg());
            if ("20000".equals(code) || "20001".equals(code)
                    || (subCode != null && subCode.contains("SYSTEM_ERROR"))) {
                return RefundResult.unknown("支付宝退款结果未知：" + code + "/" + subCode);
            }
            if (subCode != null && (subCode.contains("NOT_SUPPORT") || subCode.contains("UNSUPPORTED"))) {
                return RefundResult.unsupported("支付宝不支持本次退款：" + subCode);
            }
            return RefundResult.rejected("支付宝退款被拒绝：" + code + "/" + subCode);
        } catch (Exception e) {
            logger.error("支付宝退款异常（结果未知，需先查询）：orderNo={}", order.getOrderNo(), e);
            return RefundResult.unknown("支付宝退款异常：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 按 out_request_no 查询退款结果：用于 UNKNOWN 后先查明，避免重复退款。
     */
    @Override
    public boolean supportsRefundQuery() {
        return true;
    }

    @Override
    public RefundResult queryRefund(Order order, String paymentId, String refundRequestId) {
        if (!missingConfig().isEmpty()) {
            return RefundResult.unknown("支付宝配置缺失，无法查询退款");
        }
        try {
            AlipayClient client = getAlipayClient();
            AlipayTradeFastpayRefundQueryRequest request = new AlipayTradeFastpayRefundQueryRequest();
            Map<String, Object> biz = new HashMap<>();
            biz.put("out_trade_no", order.getOrderNo());
            biz.put("out_request_no", refundRequestId);
            request.setBizContent(OBJECT_MAPPER.writeValueAsString(biz));

            AlipayTradeFastpayRefundQueryResponse response = doExecute(client, request);
            if (response == null) {
                return RefundResult.unknown("支付宝退款查询无响应");
            }
            if (response.isSuccess()) {
                boolean refunded = response.getRefundAmount() != null
                    && new BigDecimal(response.getRefundAmount()).signum() > 0;
                return refunded
                    ? RefundResult.success(response.getTradeNo())
                    : RefundResult.rejected("支付宝未查到该退款请求的退款记录");
            }
            String code = response.getCode();
            if ("40004".equals(code)) {
                return RefundResult.rejected("支付宝未查到该退款请求");
            }
            return RefundResult.unknown("支付宝退款查询失败：" + code + "/" + response.getSubCode());
        } catch (Exception e) {
            logger.error("支付宝退款查询异常：orderNo={}", order.getOrderNo(), e);
            return RefundResult.unknown("支付宝退款查询异常");
        }
    }

    /** 复用 SDK 模型序列化，避免字符串拼接被商品标题里的引号/反斜线/换行破坏。 */
    private String buildBizContent(Order order, String productCode, PaymentLinkContext context) {
        Map<String, Object> biz = new HashMap<>();
        biz.put("out_trade_no", order.getOrderNo());
        biz.put("total_amount", order.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        biz.put("subject", StringUtils.hasText(order.getTitle()) ? order.getTitle() : "Order " + order.getOrderNo());
        biz.put("body", order.getDescription() != null ? order.getDescription() : "");
        biz.put("product_code", productCode);
        if (context != null && context.installmentNum() != null) {
            Map<String, Object> extendParams = new HashMap<>();
            extendParams.put("hb_fq_num", String.valueOf(context.installmentNum()));
            // 0 表示手续费由买家承担；若商家承担需按协议改为对应比例
            extendParams.put("hb_fq_seller_percent", "0");
            biz.put("extend_params", extendParams);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(biz);
        } catch (Exception e) {
            throw new IllegalStateException("支付宝请求参数序列化失败", e);
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
