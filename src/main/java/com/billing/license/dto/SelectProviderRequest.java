package com.billing.license.dto;

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
public class SelectProviderRequest {

    /** 支付方式，如 alipay / wechat_pay / unionpay / stripe / paddle / paypal */
    private String provider;
}
