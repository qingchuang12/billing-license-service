package com.billing.license.dto;

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
public record PaymentChannelStatus(
        String method,
        boolean enabled,
        boolean configured,
        List<String> missingConfig,
        String note
) {
}
