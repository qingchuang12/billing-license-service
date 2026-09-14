package com.billing.license.dto;

import com.billing.license.entity.RedeemCode;
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
public class RedeemCodeView {

    private UUID id;

    /** 兑换码明文 */
    private String code;

    /** 关联产品 SKU */
    private String productSku;

    /** 状态：UNUSED / USED / EXPIRED / REVOKED */
    private String status;

    /** 关联订单号（支付后自动生成时写入） */
    private String orderId;

    /** 使用人（客户 UUID） */
    private UUID usedBy;

    private LocalDateTime usedAt;

    private LocalDateTime expiresAt;

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
