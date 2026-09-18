package com.billing.license.dto;

import com.billing.license.entity.License;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * License 响应 DTO
 * 用于返回软件许可证信息给客户端
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "License 详情；公开校验端点返回完整视图（含 signedToken），管理端返回脱敏视图（不含 signedToken）")
public class LicenseResponse {
    
    /** License 唯一标识 */
    @Schema(description = "License 唯一标识", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private UUID id;
    
    /** License 密钥，用于激活软件 */
    @Schema(description = "License 密钥，用于激活软件", example = "LIC-2F8A-7C31-9D04-B5E6")
    private String licenseKey;
    
    /** 客户邮箱（对外客户标识） */
    @Schema(description = "客户邮箱（对外客户标识）；匿名历史证件或对外脱敏场景为 null", example = "buyer@example.com")
    private String customerEmail;
    
    /** 产品 SKU（库存量单位） */
    @Schema(description = "产品 SKU（库存量单位）", example = "PRO_LIFETIME")
    private String productSku;
    
    /** License 状态：ACTIVE, EXPIRED, REVOKED, REISSUED（D3） */
    @Schema(description = "License 状态：ACTIVE=有效，EXPIRED=已过期，REVOKED=已吊销，REISSUED=已换机重发",
            example = "ACTIVE", allowableValues = {"ACTIVE", "EXPIRED", "REVOKED", "REISSUED"})
    private String status;
    
    /** License 签发时间 */
    @Schema(description = "License 签发时间（ISO-8601，无时区）", example = "2026-09-14T22:49:37")
    private LocalDateTime issuedAt;
    
    /** License 过期时间 */
    @Schema(description = "License 过期时间；永久授权时为 null", example = "2027-09-14T22:49:37")
    private LocalDateTime expiresAt;
    
    /** 最近校验时间（verify 时更新） */
    @Schema(description = "最近一次校验时间（调用 verify 时更新）；从未校验过为 null",
            example = "2026-09-14T23:10:02")
    private LocalDateTime lastVerifiedAt;
    
    /** 换机重发时指向的原 License ID（仅 REISSUED 记录的后续新证会带值） */
    @Schema(description = "换机重发时指向的原 License ID；非重发签发为 null",
            example = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
    private UUID reissuedFrom;
    
    /** 签名的 License Token，用于离线验证 */
    @Schema(description = "签名的 License Token（signedToken），供客户端离线验签；管理端脱敏视图不返回此字段",
            example = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ...")
    private String signedToken;

    /**
     * H10：管理后台安全视图。映射除敏感字段外的全部信息，
     * 显式不设置 {@code signedToken}（内部离线校验令牌，不应经管理接口返回）。
     *
     * <p>E3（客户标识邮箱化）：{@code customerEmail} 由调用方解析后传入，避免在实体层逐条查库
     * （N+1）；匿名历史证件或无法解析时为 null。
     */
    public static LicenseResponse adminView(License license, String customerEmail) {
        return LicenseResponse.builder()
            .id(license.getId())
            .licenseKey(license.getLicenseKey())
            .customerEmail(customerEmail)
            .productSku(license.getProduct() != null ? license.getProduct().getSku() : null)
            .status(license.getStatus() != null ? license.getStatus().name() : null)
            .issuedAt(license.getIssuedAt())
            .expiresAt(license.getExpiresAt())
            .lastVerifiedAt(license.getLastVerifiedAt())
            .reissuedFrom(license.getReissuedFrom())
            .build();
    }
}
