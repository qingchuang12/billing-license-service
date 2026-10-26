package com.billing.license.dto;

import com.billing.license.entity.ProductProfile;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 管理端产品档案视图：按产品码聚合的名称 / 描述 / 权益，附挂载的套餐数量。
 */
@Value
@Builder
public class AdminProductProfileView {

    UUID id;
    String productCode;
    String name;
    String nameEn;
    String description;
    String descriptionEn;
    List<String> features;
    long planCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;

    public static AdminProductProfileView from(ProductProfile profile, List<String> features, long planCount) {
        return AdminProductProfileView.builder()
            .id(profile.getId())
            .productCode(profile.getProductCode())
            .name(profile.getName())
            .nameEn(profile.getNameEn())
            .description(profile.getDescription())
            .descriptionEn(profile.getDescriptionEn())
            .features(features)
            .planCount(planCount)
            .createdAt(profile.getCreatedAt())
            .updatedAt(profile.getUpdatedAt())
            .build();
    }
}
