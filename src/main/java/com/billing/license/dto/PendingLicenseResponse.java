package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付完成后客户端「按机器码领取待激活授权」的响应（plan-1.0 / S1）。
 *
 * <p>只返回**绑定在本机器码上、且从未被成功校验过**的 ACTIVE 授权，即「刚付完款、客户端还没拿到手」
 * 的那一批。客户端拿到 {@code signedToken} 后直接本地验签落盘即可激活，无需登录。
 *
 * <p>刻意**不含** {@code customerEmail}：公开端点回显归属邮箱会让任何持机器码者看到客户邮箱
 * （与 {@code /api/licenses/verify} 的脱敏口径一致）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "待激活授权领取结果")
public class PendingLicenseResponse {

    @Schema(description = "本机器可领取的授权列表；无可领取件时为空列表")
    private List<Item> licenses;

    @Schema(description = "服务端时间，供客户端校正本地时钟偏差")
    private LocalDateTime serverTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单张待领取授权")
    public static class Item {

        @Schema(description = "License 密钥", example = "LIC-2F8A-7C31-9D04-B5E6")
        private String licenseKey;

        @Schema(description = "签名令牌，客户端本地验签后直接落盘激活")
        private String signedToken;

        @Schema(description = "产品 SKU", example = "pro-buyout")
        private String productSku;

        @Schema(description = "过期时间；永久授权为 null")
        private LocalDateTime expiresAt;

        @Schema(description = "签发时间")
        private LocalDateTime issuedAt;
    }
}
