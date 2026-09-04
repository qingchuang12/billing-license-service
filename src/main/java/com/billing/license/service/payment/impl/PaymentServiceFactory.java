package com.billing.license.service.payment.impl;

import com.billing.license.dto.PaymentChannelStatus;
import com.billing.license.exception.ChannelDisabledException;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 支付服务工厂 - 管理所有支付策略
 *
 * <p>渠道启用机制：
 * <ul>
 *   <li>所有 {@link PaymentStrategy} 实现类由 Spring 注入并注册（多渠道天然并存）。</li>
 *   <li>是否「启用」由 {@code payment.enabled-channels} 决定：
 *       <ul>
 *         <li>显式配置（逗号分隔，如 {@code stripe,alipay}）：以配置为准；
 *             显式启用的渠道若关键配置缺失，启动时告警但不阻止启用（尊重显式意图）。</li>
 *         <li>留空：按各渠道 {@link PaymentStrategy#isConfigured()}（关键配置是否齐全）自动判定，
 *             配置齐全即启用。此为向后兼容的默认行为。</li>
 *       </ul>
 *   </li>
 *   <li>未启用渠道在 {@link #getStrategy(PaymentMethod)} 处即被拒绝（抛
 *       {@link ChannelDisabledException}），而非走到调用渠道 API 才失败。</li>
 * </ul>
 *
 * <p>启动时打印渠道配置自检报告，便于部署后一眼确认「哪些渠道可用、缺哪些配置」。
 */
@Service
public class PaymentServiceFactory {

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceFactory.class);

    private final Map<PaymentMethod, PaymentStrategy> strategies = new ConcurrentHashMap<>();

    /** 生效的启用渠道集合（线程安全，运行期只读） */
    private final Set<PaymentMethod> enabledChannels = ConcurrentHashMap.newKeySet();

    /**
     * 显式启用的渠道（逗号分隔，大小写不敏感）。留空则按关键配置是否齐全自动判定。
     */
    @Value("${payment.enabled-channels:}")
    private String enabledChannelsConfig;

    /** 对外基址，用于自检时判断回调地址是否指向本机（上线前必须改为公网地址） */
    @Value("${app.base-url:}")
    private String appBaseUrl;

    /** true=按 payment.enabled-channels 显式启用；false=按配置完备性自动判定 */
    private boolean explicitEnabled = false;

    @Autowired
    public PaymentServiceFactory(List<PaymentStrategy> strategyList) {
        for (PaymentStrategy strategy : strategyList) {
            strategies.put(strategy.getPaymentMethod(), strategy);
            logger.info("注册支付策略：{}", strategy.getPaymentMethod());
        }
    }

    /**
     * 计算生效的启用渠道并打印自检报告。
     * 策略 Bean 在本工厂之前完成初始化（构造器注入依赖），故此处读取的配置状态是最终态。
     */
    @PostConstruct
    public void initEnabledChannels() {
        Set<PaymentMethod> declared = parseChannels(enabledChannelsConfig);
        enabledChannels.clear();
        if (declared.isEmpty()) {
            explicitEnabled = false;
            for (PaymentStrategy strategy : strategies.values()) {
                if (strategy.isConfigured()) {
                    enabledChannels.add(strategy.getPaymentMethod());
                }
            }
        } else {
            explicitEnabled = true;
            enabledChannels.addAll(declared);
        }
        logChannelReport();
    }

    /**
     * 解析 {@code payment.enabled-channels}。无法识别的渠道名直接抛错（fail-fast），
     * 避免拼写错误被静默忽略、导致渠道莫名不可用。
     */
    private Set<PaymentMethod> parseChannels(String raw) {
        Set<PaymentMethod> result = EnumSet.noneOf(PaymentMethod.class);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String token : raw.split(",")) {
            String name = token.trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            try {
                result.add(PaymentMethod.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "payment.enabled-channels 含无法识别的支付渠道：" + name
                                + "；可选值：" + Arrays.toString(PaymentMethod.values()), e);
            }
        }
        return result;
    }

    /** 打印支付渠道配置自检报告（部署后置此确认配置是否正确） */
    private void logChannelReport() {
        StringBuilder sb = new StringBuilder("\n========== 支付渠道启用状态自检 ==========\n");
        sb.append("判定模式：").append(explicitEnabled
                ? "显式配置（payment.enabled-channels）"
                : "自动判定（payment.enabled-channels 留空，按关键配置是否齐全自动启用）").append('\n');

        for (PaymentMethod method : PaymentMethod.values()) {
            PaymentStrategy strategy = strategies.get(method);
            if (strategy == null) {
                continue;
            }
            boolean enabled = enabledChannels.contains(method);
            List<String> missing = strategy.missingConfig();
            boolean configured = missing == null || missing.isEmpty();

            sb.append('[').append(enabled ? "已启用" : "已禁用").append("] ")
                    .append(String.format("%-11s", method.name()));
            sb.append(configured ? "配置完整" : "缺失：" + String.join(", ", missing));
            if (enabled && !configured) {
                sb.append("  <-- 已启用但配置不全，真实调用会失败");
            }
            sb.append('\n');
        }

        sb.append("------------------------------------------\n");
        if (enabledChannels.isEmpty()) {
            sb.append("警告：当前没有任何支付渠道被启用，所有下单请求都会被拒绝。\n");
        }
        sb.append("回调基址 app.base-url = ").append(appBaseUrl == null ? "(未配置)" : appBaseUrl).append('\n');
        if (isLocalhostBaseUrl()) {
            sb.append("警告：app.base-url 指向本机（localhost/127.0.0.1），渠道回调无法送达，"
                    + "上线前必须设置 APP_BASE_URL 为公网可达地址。\n");
        }
        sb.append("===========================================");
        logger.info("{}", sb);
    }

    private boolean isLocalhostBaseUrl() {
        if (appBaseUrl == null || appBaseUrl.isBlank()) {
            return true;
        }
        String v = appBaseUrl.toLowerCase();
        return v.contains("localhost") || v.contains("127.0.0.1");
    }

    /**
     * 根据支付方式获取策略。
     *
     * @throws IllegalArgumentException 渠道未注册
     * @throws ChannelDisabledException 渠道已注册但未启用
     */
    public PaymentStrategy getStrategy(PaymentMethod paymentMethod) {
        PaymentStrategy strategy = strategies.get(paymentMethod);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的支付方式：" + paymentMethod);
        }
        if (!isEnabled(paymentMethod)) {
            throw new ChannelDisabledException(paymentMethod, "该渠道未启用或关键配置缺失");
        }
        return strategy;
    }

    /**
     * 根据支付方式名称获取策略
     */
    public PaymentStrategy getStrategyByName(String name) {
        try {
            PaymentMethod method = PaymentMethod.valueOf(name.toUpperCase());
            return getStrategy(method);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的支付方式：" + name);
        }
    }

    /** 该渠道当前是否启用 */
    public boolean isEnabled(PaymentMethod paymentMethod) {
        return paymentMethod != null && enabledChannels.contains(paymentMethod);
    }

    /**
     * 获取已启用的支付方式
     */
    public List<PaymentMethod> getSupportedMethods() {
        return strategies.keySet().stream()
                .filter(this::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * 获取已启用的国内支付方式
     */
    public List<PaymentMethod> getDomesticMethods() {
        return strategies.keySet().stream()
                .filter(PaymentMethod::isDomestic)
                .filter(this::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * 获取已启用的国际支付方式
     */
    public List<PaymentMethod> getInternationalMethods() {
        return strategies.keySet().stream()
                .filter(PaymentMethod::isInternational)
                .filter(this::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * 获取全部渠道的启用与配置状态（供管理端自查接口展示）。
     */
    public List<PaymentChannelStatus> getChannelStatuses() {
        List<PaymentChannelStatus> statuses = new ArrayList<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            PaymentStrategy strategy = strategies.get(method);
            if (strategy == null) {
                continue;
            }
            List<String> missing = strategy.missingConfig();
            boolean configured = missing == null || missing.isEmpty();
            boolean enabled = enabledChannels.contains(method);

            String note = "";
            if (enabled && !configured) {
                note = "已启用但关键配置缺失，真实调用会失败";
            } else if (!enabled && configured) {
                note = "配置完整但未被启用（未列入 payment.enabled-channels）";
            }
            statuses.add(new PaymentChannelStatus(
                    method.name(),
                    enabled,
                    configured,
                    configured ? List.of() : missing,
                    note));
        }
        return statuses;
    }
}
