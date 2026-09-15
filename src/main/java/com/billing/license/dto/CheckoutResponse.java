package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一收银台响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一收银台响应；创建、选择支付方式、查询状态三个端点共用，字段按阶段填充（未到阶段的字段为 null）")
public class CheckoutResponse {

    @Schema(description = "收银台会话 ID，后续 select-provider / status 端点使用",
            example = "cs_9f8e7d6c5b4a")
    private String checkoutId;

    @Schema(description = "业务订单号", example = "ORD20260914224937123")
    private String orderNumber;

    @Schema(description = "当前状态：创建后创建支付前为 PENDING；渠道确认支付后为 PAID 等订单状态",
            example = "PENDING")
    private String status;

    @Schema(description = "已选定的支付渠道枚举名（未选择时为 null）", example = "ALIPAY")
    private String provider;

    @Schema(description = "可用支付方式列表（渠道枚举名）；仅在未传 provider 的两步流程中返回",
            example = "[\"ALIPAY\", \"WECHAT_PAY\", \"STRIPE\"]")
    private List<String> paymentMethods;

    @Schema(description = "支付跳转链接（兼容字段，与 redirectUrl 同源）",
            example = "https://openapi.alipay.com/gateway.do?...")
    private String payUrl;

    @Schema(description = "支付交付模式：redirect=跳转第三方页面，qrcode=扫码支付",
            example = "qrcode", allowableValues = {"redirect", "qrcode"})
    private String paymentMode;

    @Schema(description = "二维码内容（paymentMode=qrcode 时返回，供客户端渲染）",
            example = "https://qr.alipay.com/bax0123456789")
    private String qrcode;

    @Schema(description = "重定向 URL（paymentMode=redirect 时返回，供浏览器跳转）",
            example = "https://checkout.stripe.com/c/pay/cs_test_xxx")
    private String redirectUrl;

    @Schema(description = "收银台会话过期时间（Unix 毫秒时间戳）", example = "1757868577000")
    private Long expiresAt;

    @Schema(description = "已签发 License 的签名令牌（signedToken，非 licenseKey）；支付完成且绑定了机器码时返回",
            example = "eyJhbGciOiJFUzI1NiJ9...")
    private String license;

    @Schema(description = "已生成的兑换码明文；支付完成且未绑定机器码时返回", example = "RC-8F3C-1D2E-9A4B")
    private String redeemCode;
}
