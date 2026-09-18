package com.billing.license.dto;

import com.billing.license.entity.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一收银台创建请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一收银台创建请求")
public class CheckoutRequest {

    /** 产品 SKU */
    @Schema(description = "产品 SKU（必填）；不存在或未上架返回 400", example = "PRO_LIFETIME")
    private String productId;

    /** 货币：CNY / USD */
    @Schema(description = "结算货币，与 locale 共同决定国内/国际区域判定",
            example = "CNY", allowableValues = {"CNY", "USD"})
    private Currency currency;

    /** 语言区域，如 zh-CN / en-US */
    @Schema(description = "语言区域；以 zh 开头时按国内区域处理", example = "zh-CN")
    private String locale;

    /** 机器码（购买时已传入则支付后直接签发 License） */
    @Schema(description = "客户端机器码；传入则支付成功后直接签发绑定设备的 License，不传则生成兑换码",
            example = "MACHINE-FP-8823a1")
    private String machineId;

    /** 支付成功回跳地址 */
    @Schema(description = "支付成功回跳地址（网页支付渠道使用）",
            example = "https://app.example.com/pay/success")
    private String returnUrl;

    /** 支付取消回跳地址 */
    @Schema(description = "支付取消回跳地址（网页支付渠道使用）",
            example = "https://app.example.com/pay/cancel")
    private String cancelUrl;

    /** 客户邮箱（对外客户标识；未注册则自动建访客账户） */
    @Email
    @Schema(description = "客户邮箱，作为对外客户标识；未注册邮箱将自动创建访客账户",
            example = "buyer@example.com")
    private String customerEmail;

    /**
     * I7（2026-09-14）：支付渠道（可选）。传入则**一步完成**「创建会话 + 创建支付」，
     * 直接返回二维码/跳转链接；不传则先返回可用支付方式列表，再由
     * {@code POST /api/checkout/{checkoutId}/select-provider} 二次选择。
     * 取值：alipay / wechat_pay / stripe / paddle / paypal
     */
    @Schema(description = "支付渠道（可选）。传入则一步完成「创建会话 + 创建支付」并直接返回二维码/跳转链接；"
            + "不传则先返回可用支付方式列表，再由 select-provider 端点二次选择。取值不区分大小写",
            example = "alipay",
            allowableValues = {"alipay", "wechat_pay", "stripe", "paddle", "paypal"})
    private String provider;
}
