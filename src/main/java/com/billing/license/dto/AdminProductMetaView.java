package com.billing.license.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 产品配置表单的元数据：周期 / 档位枚举与渠道能力。
 * 权益配置已随产品档案下线；渠道能力让管理端在选择周期时提示「哪些渠道能卖这个周期」。
 */
@Value
@Builder
public class AdminProductMetaView {

    List<String> billingCycles;
    List<String> tiers;
    /** 全部渠道的启用状态与售卖能力（含未启用渠道，前端据此提示与置灰） */
    List<PaymentChannelStatus> channels;
}
