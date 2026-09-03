package com.billing.license.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * w10：支付渠道配置 fail-fast 校验。
 *
 * <p>各渠道 verifyWebhookSignature 在未配置时改为返回 false（拒绝回调），
 * 但「漏配」应在启动期尽早暴露而非运行时静默丢事件。本校验器在<b>非 test profile</b>
 * 下于应用启动完成后检查各渠道关键凭证是否齐全，缺失即抛异常中止启动。
 * test profile 下跳过（测试环境本就未配置真实凭证，不应 fail-fast）。
 */
@Component
@Profile("!test")
@Order(100)
public class ChannelConfigValidator implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ChannelConfigValidator.class);

    @Value("${payment.alipay.app-id:}")
    private String alipayAppId;
    @Value("${payment.alipay.public-key:}")
    private String alipayPublicKey;
    @Value("${payment.wechat.app-id:}")
    private String wechatAppId;
    @Value("${payment.wechat.mch-id:}")
    private String wechatMchId;
    @Value("${payment.wechat.api-key:}")
    private String wechatApiKey;
    @Value("${payment.stripe.api-key:}")
    private String stripeApiKey;
    @Value("${payment.stripe.webhook-secret:}")
    private String stripeWebhookSecret;
    @Value("${payment.paddle.api-key:}")
    private String paddleApiKey;
    @Value("${payment.paddle.webhook-secret:}")
    private String paddleWebhookSecret;
    @Value("${payment.paypal.client-id:}")
    private String paypalClientId;
    @Value("${payment.paypal.client-secret:}")
    private String paypalClientSecret;
    @Value("${payment.paypal.webhook-id:}")
    private String paypalWebhookId;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = new ArrayList<>();
        check(missing, "payment.alipay.app-id", alipayAppId);
        check(missing, "payment.alipay.public-key", alipayPublicKey);
        check(missing, "payment.wechat.app-id", wechatAppId);
        check(missing, "payment.wechat.mch-id", wechatMchId);
        check(missing, "payment.wechat.api-key", wechatApiKey);
        check(missing, "payment.stripe.api-key", stripeApiKey);
        check(missing, "payment.stripe.webhook-secret", stripeWebhookSecret);
        check(missing, "payment.paddle.api-key", paddleApiKey);
        check(missing, "payment.paddle.webhook-secret", paddleWebhookSecret);
        check(missing, "payment.paypal.client-id", paypalClientId);
        check(missing, "payment.paypal.client-secret", paypalClientSecret);
        check(missing, "payment.paypal.webhook-id", paypalWebhookId);

        if (!missing.isEmpty()) {
            String msg = "支付渠道配置缺失（启动 fail-fast，w10）：" + String.join(", ", missing)
                    + "。请配置后重启；未配齐的渠道 Webhook 将拒绝回调（零鉴权风险）。";
            logger.error(msg);
            throw new IllegalStateException(msg);
        }
        logger.info("支付渠道配置校验通过：5 家渠道关键凭证齐全");
    }

    private void check(List<String> missing, String name, String value) {
        if (value == null || value.isBlank() || value.equals("sk_test_xxx")
                || value.equals("whsec_xxx") || value.startsWith("https://")) {
            missing.add(name);
        }
    }
}
