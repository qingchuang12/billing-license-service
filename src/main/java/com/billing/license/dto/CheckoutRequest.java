package com.billing.license.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 统一收银台创建请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {

    /** 产品 SKU */
    private String productId;

    /** 货币：CNY / USD */
    private String currency;

    /** 语言区域，如 zh-CN / en-US */
    private String locale;

    /** 机器码（购买时已传入则支付后直接签发 License） */
    private String machineId;

    /** 客户邮箱（用于发货通知） */
    private String email;

    /** 支付成功回跳地址 */
    private String returnUrl;

    /** 支付取消回跳地址 */
    private String cancelUrl;

    /** 客户标识（可选，未传则生成匿名客户） */
    private UUID customerId;
}
