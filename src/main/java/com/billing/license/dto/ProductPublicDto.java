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
 * 收银台页面从本端点取 SKU / 名称 / 双档价格 / 档位 / 周期 / 权益，避免页面硬编码价格漂移。
 * 仅暴露下单所需字段，不含私钥、内部 id 等敏感信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductPublicDto {

    private String sku;
    private String name;
    private String description;
    private BigDecimal priceCny;
    private BigDecimal priceUsd;
    private Product.BillingCycle billingCycle;
    private PlanTier tier;
    private String features;
    /** 权益特性视图（配置驱动的中英文显示名），收银台渲染用；原始键见 {@link #features} */
    private List<FeatureView> featureViews;
    private Integer licenseDurationDays;
    /** 英文产品名（收银台双语展示用，K16 延伸） */
    private String nameEn;
    /** 英文产品描述（收银台双语展示用，K16 延伸） */
    private String descriptionEn;
    private Integer updateUntilDays;
    private Integer maxMajorVersion;
    private Boolean active;

    public static ProductPublicDto from(Product p) {
        return ProductPublicDto.builder()
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .priceCny(p.getPriceCny())
                .priceUsd(p.getPriceUsd())
                .billingCycle(p.getBillingCycle())
                .tier(p.getTier())
                .features(p.getFeatures())
                .nameEn(p.getNameEn())
                .descriptionEn(p.getDescriptionEn())
                .licenseDurationDays(p.getLicenseDurationDays())
                .updateUntilDays(p.getUpdateUntilDays())
                .maxMajorVersion(p.getMaxMajorVersion())
                .active(p.getActive())
                .build();
    }

    /** 单个权益特性视图（键 + 中英文显示名），由 ProductController 关联 BillingProperties.featureLabels 后填充 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeatureView {
        private String key;
        private String labelZh;
        private String labelEn;
    }
}
