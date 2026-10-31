package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "支付渠道/功能运行时开关")
public record PaymentSettingsView(
        List<ChannelSettingView> channels,
        List<FeatureSettingView> features
) {
    public record ChannelSettingView(
            String method,
            String name,
            boolean enabled,
            boolean configured,
            Boolean override,
            List<String> missingConfig
    ) {
    }

    public record FeatureSettingView(
            String key,
            String name,
            boolean enabled,
            boolean implemented,
            Boolean override
    ) {
    }
}
