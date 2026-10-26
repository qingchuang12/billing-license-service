package com.billing.license.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 产品配置表单的元数据：周期 / 档位枚举、权益标签与渠道能力。
 * 权益标签来自 {@code billing.feature-labels} 配置，前端据此渲染多选项，避免页面硬编码；
 * 渠道能力让管理端在选择周期时提示「哪些渠道能卖这个周期」。
 */
@Value
@Builder
public class AdminProductMetaView {

    List<String> billingCycles;
    List<String> tiers;
    List<FeatureOption> features;
    /** 全部渠道的启用状态与售卖能力（含未启用渠道，前端据此提示与置灰） */
    List<PaymentChannelStatus> channels;

    @Value
    @Builder
    public static class FeatureOption {
        String key;
        String labelZh;
        String labelEn;
    }
}
