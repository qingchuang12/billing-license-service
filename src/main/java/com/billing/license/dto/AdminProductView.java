package com.billing.license.dto;

import com.billing.license.entity.Product;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 管理端产品套餐视图。
 *
 * <p>返回套餐维护所需的全部字段；产品码、名称、描述均在套餐行上，权益配置已下线。
 * SKU 建后不可改，{@code active} 只经上下架端点变更。
 */
@Value
@Builder
public class AdminProductView {

    UUID id;
    String sku;
    String productCode;
    String name;
    String nameEn;
    String description;
    String descriptionEn;
    BigDecimal priceCny;
    BigDecimal priceUsd;
    String billingCycle;
    String chargeMode;
    String tier;
    Integer licenseDurationDays;
    Integer updateUntilDays;
    Integer maxMajorVersion;
    String stripePriceId;
    String paddlePriceId;
    String creemProductId;
    boolean active;
    boolean recommended;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static AdminProductView from(Product p) {
        return AdminProductView.builder()
            .id(p.getId())
            .sku(p.getSku())
            .productCode(p.getProductCode())
            .name(p.getName())
            .nameEn(p.getNameEn())
            .description(p.getDescription())
            .descriptionEn(p.getDescriptionEn())
            .priceCny(p.getPriceCny())
            .priceUsd(p.getPriceUsd())
            .billingCycle(p.getBillingCycle() != null ? p.getBillingCycle().name() : null)
            .chargeMode(p.getChargeMode() != null ? p.getChargeMode().name() : null)
            .tier(p.getTier() != null ? p.getTier().name() : null)
            .licenseDurationDays(p.getLicenseDurationDays())
            .updateUntilDays(p.getUpdateUntilDays())
            .maxMajorVersion(p.getMaxMajorVersion())
            .stripePriceId(p.getStripePriceId())
            .paddlePriceId(p.getPaddlePriceId())
            .creemProductId(p.getCreemProductId())
            .active(Boolean.TRUE.equals(p.getActive()))
            .recommended(Boolean.TRUE.equals(p.getRecommended()))
            .createdAt(p.getCreatedAt())
            .updatedAt(p.getUpdatedAt())
            .build();
    }
}
