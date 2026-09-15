package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 选择支付方式请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "选择支付方式请求")
public class SelectProviderRequest {

    /** 支付方式，如 alipay / wechat_pay / stripe / paddle / paypal */
    @Schema(description = "支付渠道（必填）；取值不区分大小写，未知渠道返回 400",
            example = "alipay",
            allowableValues = {"alipay", "wechat_pay", "stripe", "paddle", "paypal"})
    private String provider;
}
