package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订阅自助视图 DTO（U1 用户资产查询）。
 *
 * <p>只读展示用：不含渠道侧订阅 ID 等内部对账字段；产品 SKU/名称由服务端按
 * {@code productId} 批量解析回填。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "订阅详情（用户自助视图）")
public class SubscriptionView {

    /** 订阅唯一标识 */
    @Schema(description = "订阅唯一标识", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private UUID id;

    /** 订阅渠道：PADDLE, STRIPE */
    @Schema(description = "订阅渠道（MoR 托管方）", example = "STRIPE", allowableValues = {"PADDLE", "STRIPE"})
    private String provider;

    /** 订阅状态：PENDING, ACTIVE, PAST_DUE, CANCELED, EXPIRED */
    @Schema(description = "订阅状态：PENDING=待生效，ACTIVE=生效中，PAST_DUE=扣款逾期，CANCELED=已取消，EXPIRED=已到期",
            example = "ACTIVE", allowableValues = {"PENDING", "ACTIVE", "PAST_DUE", "CANCELED", "EXPIRED"})
    private String status;

    /** 产品 SKU */
    @Schema(description = "产品 SKU（库存量单位）", example = "pro-sub-yearly")
    private String productSku;

    /** 产品名称 */
    @Schema(description = "产品名称", example = "AI-Tools Pro 订阅版")
    private String productName;

    /** 关联的 License ID（订阅续期/作废作用于该 License） */
    @Schema(description = "关联的 License ID；首充尚未发货时为 null",
            example = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
    private UUID licenseId;

    /** 当前周期开始时间 */
    @Schema(description = "当前计费周期开始时间（ISO-8601，无时区）；未开始为 null", example = "2026-09-01T00:00:00")
    private LocalDateTime currentPeriodStart;

    /** 当前周期结束时间 */
    @Schema(description = "当前计费周期结束时间（ISO-8601，无时区）；未开始为 null", example = "2026-10-01T00:00:00")
    private LocalDateTime currentPeriodEnd;

    /** 是否到期不再续订（到期后自动取消） */
    @Schema(description = "是否到期不再续订：true=本周期结束后取消", example = "false")
    private Boolean cancelAtPeriodEnd;

    /** 订阅创建时间 */
    @Schema(description = "订阅创建时间（ISO-8601，无时区）", example = "2026-09-01T12:00:00")
    private LocalDateTime createdAt;
}
