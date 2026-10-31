package com.billing.license.dto;

import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 公开产品目录 DTO（K16）。
 * 收银台页面从本端点取 SKU / 名称 / 双档价格 / 档位 / 周期 / 可售渠道，避免页面硬编码价格漂移。
 * 权益配置已下线（客户端按 SKU 解锁），故不返回 features / featureViews。
 * 仅暴露下单所需字段，不含私钥、内部 id 等敏感信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPublicDto {

    private String sku;
    /** 产品码（产品维度，如 ai-tools）：收银台 GET /api/products?product= 按此过滤；未归类为 null */
    private String productCode;
    private String name;
    private String description;
    private BigDecimal priceCny;
    private BigDecimal priceUsd;
    private Product.BillingCycle billingCycle;
    /** 收款方式：ONE_TIME / AUTO_RECURRING（#57；接入方据此展示「自动续费」） */
    private com.billing.license.entity.ChargeMode chargeMode;
    /** 是否自动续扣；由 chargeMode 推导，保留旧字段兼容 */
    private Boolean recurring;
    private PlanTier tier;
    private Integer licenseDurationDays;
    /** 英文产品名（收银台双语展示用，K16 延伸） */
    private String nameEn;
    /** 英文产品描述（收银台双语展示用，K16 延伸） */
    private String descriptionEn;
    private Integer updateUntilDays;
    private Integer maxMajorVersion;
    private Boolean active;
    /** 收银台是否展示「推荐」标记（后台产品配置维护） */
    private Boolean recommended;
    /** 当前部署下能卖该套餐的已启用渠道（按渠道能力过滤）；空数组＝该套餐当前无任何可售渠道 */
    private List<ProviderCapability> providers;

    public static ProductPublicDto from(Product p) {
        return ProductPublicDto.builder()
                .sku(p.getSku())
                .productCode(p.getProductCode())
                .name(p.getName())
                .description(p.getDescription())
                .priceCny(p.getPriceCny())
                .priceUsd(p.getPriceUsd())
                .billingCycle(p.getBillingCycle())
                .chargeMode(p.getChargeMode())
                .recurring(p.getChargeMode() == com.billing.license.entity.ChargeMode.AUTO_RECURRING)
                .tier(p.getTier())
                .nameEn(p.getNameEn())
                .descriptionEn(p.getDescriptionEn())
                .licenseDurationDays(p.getLicenseDurationDays())
                .updateUntilDays(p.getUpdateUntilDays())
                .maxMajorVersion(p.getMaxMajorVersion())
                .active(p.getActive())
                .recommended(Boolean.TRUE.equals(p.getRecommended()))
                .build();
    }
}
