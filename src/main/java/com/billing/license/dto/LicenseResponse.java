package com.billing.license.dto;

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
}
