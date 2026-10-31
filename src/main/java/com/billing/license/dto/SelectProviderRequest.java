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

    /** 支付形态（QRCODE / REDIRECT）；留空按渠道能力选默认，与机器码无关 */
    @Schema(description = "支付形态：QRCODE 扫码 / REDIRECT 跳转；留空由服务端按渠道能力选择",
            allowableValues = {"QRCODE", "REDIRECT"})
    private String paymentProduct;

    /** 花呗分期期数（3/6/12），仅支付宝手机/网页支付生效；空=不分期 */
    private Integer installmentNum;
}
