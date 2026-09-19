package com.billing.license.dto;

import com.billing.license.entity.License;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 兑换码兑换结果（J3，2026-09-14）——替换 {@code POST /api/redeem/redeem} 原先返回的
 * {@code Map<String, Object>} 匿名结构，使 OpenAPI 文档中 {@code data} 具备可读字段说明。
 *
 * <p>字段与改造前的 Map 完全一一对应（success / licenseKey / signedToken / expiresAt），
 * 对外契约不变，仅把「不可见的 Map」换成「可生成 Schema 的 DTO」。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "兑换码兑换结果；兑换成功后返回绑定机器码的 License 密钥与签名令牌")
public class RedeemResponse {

    /**
     * 与响应壳 {@code ApiResponse.success} 同义，为兼容既有客户端保留。
     * 失败路径由 {@code GlobalExceptionHandler} 统一返回错误码，不会走到本 DTO。
     */
    @Schema(description = "是否兑换成功；与响应壳 success 同义，为兼容既有客户端保留", example = "true")
    private boolean success;

    @Schema(description = "License 密钥，客户端用于激活与在线校验", example = "2F8A-7C31-9D04-B5E6")
    private String licenseKey;

    @Schema(description = "License 签名令牌（JWS Compact 三段式），供客户端离线验签；请勿写入日志",
            example = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ...")
    private String signedToken;

    @Schema(description = "License 过期时间（ISO-8601，无时区）；永久授权时为 null",
            example = "2027-09-14T22:49:37")
    private LocalDateTime expiresAt;

    /**
     * 服务端当前时间（epoch **毫秒**）。
     *
     * <p>客户端用它抬高本地单调时间下界（vault 里的 {@code server_time_floor}），
     * 抹平「把系统时间改回过去让过期授权复活」这类作弊；缺失时客户端退化为仅依赖本地水印。
     * 2026-09-18（K4）新增——此前客户端读取该字段但服务端从未返回，防回拨能力实际未生效。
     */
    @Schema(description = "服务端当前时间（epoch 毫秒），客户端用于校准本地时间下界；仅用于时间校准，不含业务语义",
            example = "1758000000000")
    private Long serverTime;

    public static RedeemResponse from(License license) {
        return RedeemResponse.builder()
                .success(true)
                .licenseKey(license.getLicenseKey())
                .signedToken(license.getSignedToken())
                .expiresAt(license.getExpiresAt())
                .serverTime(System.currentTimeMillis())
                .build();
    }
}
