package com.billing.license.service.payment;

import com.billing.license.entity.PaymentChannelSetting;
import com.billing.license.entity.PaymentFeatureSetting;
import com.billing.license.repository.PaymentChannelSettingRepository;
import com.billing.license.repository.PaymentFeatureSettingRepository;
import com.billing.license.service.payment.strategy.PaymentMethod;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 支付渠道/功能运行时开关：数据库覆盖环境变量默认值。
 *
 * <p>渠道停用只拦截新建支付；已支付订单的 Webhook、退款仍走
 * {@code getStrategyForExistingPayment} 继续处理，避免停渠道导致旧单无法收口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSettingsService {

    private final PaymentChannelSettingRepository channelRepository;
    private final PaymentFeatureSettingRepository featureRepository;

    private volatile Map<String, Boolean> channelOverrides = Map.of();
    private volatile Map<String, Boolean> featureOverrides = Map.of();

    @PostConstruct
    public void reload() {
        Map<String, Boolean> channels = new HashMap<>();
        channelRepository.findAll().forEach(s -> channels.put(s.getChannel(), s.isEnabled()));
        Map<String, Boolean> features = new HashMap<>();
        featureRepository.findAll().forEach(s -> features.put(s.getFeatureKey(), s.isEnabled()));
        channelOverrides = Map.copyOf(channels);
        featureOverrides = Map.copyOf(features);
        log.info("支付运行时开关已加载：channels={}, features={}", channels, features);
    }

    public Boolean channelOverride(PaymentMethod method) {
        return method == null ? null : channelOverrides.get(method.name());
    }

    public boolean isFeatureEnabled(PaymentFeature feature) {
        if (feature == null) {
            return false;
        }
        Boolean override = featureOverrides.get(feature.key());
        return override != null ? override : feature.defaultEnabled();
    }

    public Map<String, Boolean> channelOverrides() {
        return channelOverrides;
    }

    public Map<String, Boolean> featureOverrides() {
        return featureOverrides;
    }

    @Transactional
    public synchronized void setChannelEnabled(PaymentMethod method, boolean enabled, UUID actor) {
        if (method == null) {
            throw new IllegalArgumentException("channel required");
        }
        PaymentChannelSetting setting = channelRepository.findById(method.name()).orElseGet(() -> {
            PaymentChannelSetting created = new PaymentChannelSetting();
            created.setChannel(method.name());
            return created;
        });
        setting.setEnabled(enabled);
        setting.setUpdatedBy(actor != null ? actor.toString() : null);
        channelRepository.save(setting);
        reload();
    }

    @Transactional
    public synchronized void setFeatureEnabled(PaymentFeature feature, boolean enabled, UUID actor) {
        if (feature == null) {
            throw new IllegalArgumentException("feature required");
        }
        if (!feature.implemented() && enabled) {
            throw new IllegalArgumentException("功能尚未接入，不能开启：" + feature.key());
        }
        PaymentFeatureSetting setting = featureRepository.findById(feature.key()).orElseGet(() -> {
            PaymentFeatureSetting created = new PaymentFeatureSetting();
            created.setFeatureKey(feature.key());
            return created;
        });
        setting.setEnabled(enabled);
        setting.setUpdatedBy(actor != null ? actor.toString() : null);
        featureRepository.save(setting);
        reload();
    }
}
