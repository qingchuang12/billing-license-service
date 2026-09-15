package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 支付渠道的启用与配置状态（管理端配置自查接口的返回项）
 *
 * @param method        渠道枚举名，如 STRIPE / ALIPAY / WECHAT_PAY
 * @param enabled       当前是否启用（可接单）
 * @param configured    关键配置是否齐全
 * @param missingConfig 缺失的配置项名列表；配置齐全时为空列表
 * @param note          附加说明（如「已启用但配置不全」）
 */
@Schema(description = "单个支付渠道的启用与配置状态；不返回任何密钥值，仅暴露缺失配置项名")
public record PaymentChannelStatus(
        @Schema(description = "渠道枚举名", example = "ALIPAY",
                allowableValues = {"ALIPAY", "WECHAT_PAY", "STRIPE", "PADDLE", "PAYPAL"})
        String method,

        @Schema(description = "当前是否启用（可接单）", example = "true")
        boolean enabled,

        @Schema(description = "关键配置是否齐全；false 时查看 missingConfig", example = "true")
        boolean configured,

        @Schema(description = "缺失的配置项名列表；配置齐全时为空数组",
                example = "[\"alipay.private-key\", \"alipay.public-key\"]")
        List<String> missingConfig,

        @Schema(description = "附加说明；仅返回配置项名，不回显密钥值", example = "已启用但配置不全")
        String note
) {
}
