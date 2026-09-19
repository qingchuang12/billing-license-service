package com.billing.license.dto;

import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
    private Integer licenseDurationDays;
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
                .licenseDurationDays(p.getLicenseDurationDays())
                .updateUntilDays(p.getUpdateUntilDays())
                .maxMajorVersion(p.getMaxMajorVersion())
                .active(p.getActive())
                .build();
    }
}
