package com.billing.license.dto;

import com.billing.license.entity.RedeemCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 兑换码管理视图（I5/I6）——供管理端批量导出与对账使用。
 *
 * <p>与生成接口配合：生成时返回明文码，事后可用本视图按产品/状态导出核对。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "兑换码管理视图；供管理端导出与对账，含码明文与使用情况")
public class RedeemCodeView {

    @Schema(description = "兑换码唯一标识", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private UUID id;

    /** 兑换码明文 */
    @Schema(description = "兑换码明文", example = "RC-8F3C-1D2E-9A4B")
    private String code;

    /** 关联产品 SKU */
    @Schema(description = "关联产品 SKU", example = "PRO_LIFETIME")
    private String productSku;

    /** 状态：UNUSED / USED / EXPIRED / REVOKED */
    @Schema(description = "状态：UNUSED=未使用，USED=已使用，EXPIRED=已过期，REVOKED=已撤销",
            example = "UNUSED", allowableValues = {"UNUSED", "USED", "EXPIRED", "REVOKED"})
    private String status;

    /** 关联订单号（支付后自动生成时写入） */
    @Schema(description = "关联订单号；支付后自动生成时写入，手工生成时为 null",
            example = "ORD20260914224937123")
    private String orderId;

    /** 使用人（客户 UUID） */
    @Schema(description = "使用人（客户 UUID）；未使用时为 null",
            example = "8b2c4d6e-1f3a-4b5c-9d7e-0a1b2c3d4e5f")
    private UUID usedBy;

    @Schema(description = "使用时间；未使用时为 null", example = "2026-09-14T23:10:02")
    private LocalDateTime usedAt;

    @Schema(description = "过期时间；无过期限制时为 null", example = "2027-09-14T22:49:37")
    private LocalDateTime expiresAt;

    @Schema(description = "创建时间（ISO-8601，无时区）", example = "2026-09-14T22:49:37")
    private LocalDateTime createdAt;

    public static RedeemCodeView from(RedeemCode entity) {
        return RedeemCodeView.builder()
            .id(entity.getId())
            .code(entity.getCode())
            .productSku(entity.getProduct() != null ? entity.getProduct().getSku() : null)
            .status(entity.getStatus() != null ? entity.getStatus().name() : null)
            .orderId(entity.getOrderId())
            .usedBy(entity.getUsedBy())
            .usedAt(entity.getUsedAt())
            .expiresAt(entity.getExpiresAt())
            .createdAt(entity.getCreatedAt())
            .build();
    }
}
