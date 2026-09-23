package com.billing.license.dto;

import com.billing.license.entity.License;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 凭证激活结果（方案 A）——兑换码与许可证密钥两条分支**统一返回本合同**，
 * 客户端从此只认一个入口、一套字段。
 *
 * <p>字段口径对齐 {@link RedeemResponse}（含 {@code serverTime} 防系统时间回拨）：
 * 兑换码分支的既有客户端可无痛迁移到本端点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "凭证激活结果；成功返回绑定机器码的 License 密钥与签名令牌")
public class ActivateResponse {

    /** 与响应壳 {@code ApiResponse.success} 同义，为兼容既有客户端保留 */
    @Schema(description = "是否激活成功；与响应壳 success 同义，为兼容既有客户端保留", example = "true")
    private boolean success;

    @Schema(description = "License 密钥，客户端用于激活与在线校验", example = "2F8A-7C31-9D04-B5E6")
    private String licenseKey;

    @Schema(description = "License 签名令牌（JWS Compact 三段式），供客户端离线验签；请勿写入日志",
            example = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ...")
    private String signedToken;

    @Schema(description = "License 状态：ACTIVE / EXPIRED / REVOKED / REISSUED", example = "ACTIVE")
    private String status;

    @Schema(description = "本次绑定生效的机器码", example = "MACHINE-FP-8823a1")
    private String machineId;

    @Schema(description = "License 过期时间（ISO-8601，无时区）；永久授权时为 null",
            example = "2027-09-14T22:49:37")
    private LocalDateTime expiresAt;

    /**
     * 服务端当前时间（epoch **毫秒**），口径同 {@link RedeemResponse#getServerTime()}：
     * 客户端用它抬高本地单调时间下界（vault 的 {@code server_time_floor}），
     * 抹平「把系统时间改回过去让过期授权复活」这类作弊。
     */
    @Schema(description = "服务端当前时间（epoch 毫秒），客户端用于校准本地时间下界；仅用于时间校准，不含业务语义",
            example = "1758000000000")
    private Long serverTime;

    public static ActivateResponse from(License license) {
        return ActivateResponse.builder()
                .success(true)
                .licenseKey(license.getLicenseKey())
                .signedToken(license.getSignedToken())
                .status(license.getStatus() != null ? license.getStatus().name() : null)
                .machineId(license.getMachineCode())
                .expiresAt(license.getExpiresAt())
                .serverTime(System.currentTimeMillis())
                .build();
    }
}
