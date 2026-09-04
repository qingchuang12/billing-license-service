package com.billing.license.service.payment.impl;

import com.billing.license.dto.PaymentChannelStatus;
import com.billing.license.exception.ChannelDisabledException;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 支付渠道启用开关 + 启动自检的单元测试。
 *
 * <p>覆盖：留空自动判定、显式配置覆盖自动判定、大小写与空格容错、渠道名拼错 fail-fast、
 * 未启用渠道取策略被拒、状态报告（含缺失项与说明）。
 */
class PaymentServiceFactoryTest {

    private static final List<String> NONE = List.of();

    private static PaymentStrategy strategy(PaymentMethod method, boolean configured, List<String> missing) {
        PaymentStrategy s = mock(PaymentStrategy.class);
        when(s.getPaymentMethod()).thenReturn(method);
        when(s.isConfigured()).thenReturn(configured);
        when(s.missingConfig()).thenReturn(missing == null ? NONE : missing);
        return s;
    }

    /** 构造工厂并触发初始化（单元测试中 @PostConstruct 不会自动执行，需显式调用） */
    private static PaymentServiceFactory factory(String enabledChannelsConfig, PaymentStrategy... strategies) {
        PaymentServiceFactory f = new PaymentServiceFactory(Arrays.asList(strategies));
        ReflectionTestUtils.setField(f, "enabledChannelsConfig", enabledChannelsConfig);
        ReflectionTestUtils.setField(f, "appBaseUrl", "https://pay.example.com");
        f.initEnabledChannels();
        return f;
    }

    @Test
    void autoDetect_shouldEnableOnlyConfiguredChannels() {
        PaymentServiceFactory f = factory("",
                strategy(PaymentMethod.STRIPE, true, NONE),
                strategy(PaymentMethod.ALIPAY, false, List.of("payment.alipay.app-id")));

        assertTrue(f.isEnabled(PaymentMethod.STRIPE), "配置齐全的渠道应被自动启用");
        assertFalse(f.isEnabled(PaymentMethod.ALIPAY), "配置缺失的渠道应被自动禁用");
    }

    @Test
    void explicitConfig_shouldOverrideAutoDetection() {
        // 显式声明的渠道即使配置不全也予以启用（尊重显式意图，启动时告警而非静默忽略）
        PaymentServiceFactory f = factory("stripe",
                strategy(PaymentMethod.STRIPE, false, List.of("payment.stripe.api-key")));

        assertTrue(f.isEnabled(PaymentMethod.STRIPE));
    }

    @Test
    void explicitConfig_shouldIgnoreCaseAndWhitespace() {
        PaymentServiceFactory f = factory(" stripe , ALIPAY ",
                strategy(PaymentMethod.STRIPE, true, NONE),
                strategy(PaymentMethod.ALIPAY, true, NONE),
                strategy(PaymentMethod.PAYPAL, true, NONE));

        assertTrue(f.isEnabled(PaymentMethod.STRIPE));
        assertTrue(f.isEnabled(PaymentMethod.ALIPAY));
        assertFalse(f.isEnabled(PaymentMethod.PAYPAL), "未列入 enabled-channels 的渠道不应启用");
    }

    @Test
    void invalidChannelName_shouldFailFast() {
        PaymentStrategy stripe = strategy(PaymentMethod.STRIPE, true, NONE);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory("stripe,not_a_channel", stripe),
                "渠道名拼错应启动即失败，而不是被静默忽略导致渠道莫名不可用");
        // 代码会先将渠道名统一为大写再解析，故此处按大写匹配
        assertTrue(ex.getMessage().toUpperCase().contains("NOT_A_CHANNEL"));
    }

    @Test
    void getStrategy_whenDisabled_shouldThrowChannelDisabled() {
        PaymentServiceFactory f = factory("",
                strategy(PaymentMethod.ALIPAY, false, List.of("payment.alipay.private-key")));

        ChannelDisabledException ex = assertThrows(ChannelDisabledException.class,
                () -> f.getStrategy(PaymentMethod.ALIPAY));
        assertEquals("CHANNEL_DISABLED", ex.getErrorCode());
        assertEquals(PaymentMethod.ALIPAY, ex.getPaymentMethod());
    }

    @Test
    void getStrategy_whenEnabled_shouldReturnStrategy() {
        PaymentStrategy stripe = strategy(PaymentMethod.STRIPE, true, NONE);
        PaymentServiceFactory f = factory("", stripe);

        assertSame(stripe, f.getStrategy(PaymentMethod.STRIPE));
    }

    @Test
    void channelStatuses_shouldReportMissingConfigAndNotes() {
        PaymentServiceFactory f = factory("stripe",
                strategy(PaymentMethod.STRIPE, false, List.of("payment.stripe.webhook-secret")),
                strategy(PaymentMethod.PAYPAL, true, NONE));

        List<PaymentChannelStatus> statuses = f.getChannelStatuses();

        PaymentChannelStatus stripe = statuses.stream()
                .filter(s -> "STRIPE".equals(s.method())).findFirst().orElseThrow();
        assertTrue(stripe.enabled(), "显式声明的渠道应标记为已启用");
        assertFalse(stripe.configured());
        assertEquals(List.of("payment.stripe.webhook-secret"), stripe.missingConfig());
        assertTrue(stripe.note().contains("配置缺失"), "应提示已启用但关键配置缺失");

        PaymentChannelStatus paypal = statuses.stream()
                .filter(s -> "PAYPAL".equals(s.method())).findFirst().orElseThrow();
        assertFalse(paypal.enabled());
        assertTrue(paypal.configured());
        assertTrue(paypal.note().contains("未被启用"));
    }

    @Test
    void supportedMethods_shouldOnlyContainEnabledChannels() {
        PaymentServiceFactory f = factory("stripe,paypal",
                strategy(PaymentMethod.STRIPE, true, NONE),
                strategy(PaymentMethod.PAYPAL, true, NONE),
                strategy(PaymentMethod.ALIPAY, true, NONE));

        assertEquals(Set.of(PaymentMethod.STRIPE, PaymentMethod.PAYPAL),
                new HashSet<>(f.getSupportedMethods()));
    }
}
