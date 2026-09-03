package com.billing.license.dto;

import com.billing.license.entity.License;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

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
public class LicenseResponse {
    
    /** License 唯一标识 */
    private UUID id;
    
    /** License 密钥，用于激活软件 */
    private String licenseKey;
    
    /** 客户唯一标识 */
    private UUID customerId;
    
    /** 产品 SKU（库存量单位） */
    private String productSku;
    
    /** License 状态：ACTIVE, EXPIRED, REVOKED, SUSPENDED, PENDING_ACTIVATION */
    private String status;
    
    /** License 签发时间 */
    private LocalDateTime issuedAt;
    
    /** License 过期时间 */
    private LocalDateTime expiresAt;
    
    /** License 激活时间 */
    private LocalDateTime activatedAt;
    
    /** 签名的 License Token，用于离线验证 */
    private String signedToken;

    /**
     * H10：管理后台安全视图。映射除敏感字段外的全部信息，
     * 显式不设置 {@code signedToken}（内部离线校验令牌，不应经管理接口返回）。
     */
    public static LicenseResponse adminView(License license) {
        return LicenseResponse.builder()
            .id(license.getId())
            .licenseKey(license.getLicenseKey())
            .customerId(license.getCustomerId())
            .productSku(license.getProduct() != null ? license.getProduct().getSku() : null)
            .status(license.getStatus() != null ? license.getStatus().name() : null)
            .issuedAt(license.getIssuedAt())
            .expiresAt(license.getExpiresAt())
            .activatedAt(license.getActivatedAt())
            .build();
    }
}
