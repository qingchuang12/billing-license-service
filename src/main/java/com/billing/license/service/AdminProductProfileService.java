package com.billing.license.service;

import com.billing.license.dto.AdminProductProfileRequest;
import com.billing.license.dto.AdminProductProfileView;
import com.billing.license.entity.ProductProfile;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.ProductProfileRepository;
import com.billing.license.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理端产品档案服务（产品 → 套餐两层中的产品层）。
 *
 * <p>权益特性属于产品：同一 product_code 下的月付 / 半年 / 年付 / 永久套餐共享本档案的权益。
 * 产品码创建后锁定（套餐以它为关联键），不提供删除——删除会让套餐失去权益来源。
 */
@Service
@RequiredArgsConstructor
public class AdminProductProfileService {

    private static final Logger log = LoggerFactory.getLogger(AdminProductProfileService.class);

    /** 权益键：大写字母开头，允许大写字母 / 数字 / 下划线 */
    private static final Pattern FEATURE_KEY = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final ProductProfileRepository productProfileRepository;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public List<AdminProductProfileView> list(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return productProfileRepository.findAll(Sort.by(Sort.Direction.ASC, "productCode")).stream()
            .filter(profile -> kw.isEmpty()
                || containsIgnoreCase(profile.getProductCode(), kw)
                || containsIgnoreCase(profile.getName(), kw)
                || containsIgnoreCase(profile.getNameEn(), kw))
            .map(this::toView)
            .toList();
    }

    @Transactional
    public AdminProductProfileView create(AdminProductProfileRequest request) {
        String code = normalizeCode(request.getProductCode());
        if (productProfileRepository.existsByProductCodeIgnoreCase(code)) {
            throw new BusinessException("PRODUCT_PROFILE_EXISTS", "产品码已存在：" + code);
        }
        ProductProfile profile = ProductProfile.builder()
            .productCode(code)
            .name(request.getName().trim())
            .nameEn(trimToNull(request.getNameEn()))
            .description(trimToNull(request.getDescription()))
            .descriptionEn(trimToNull(request.getDescriptionEn()))
            .features(normalizeFeatures(request.getFeatures()))
            .build();
        return toView(productProfileRepository.save(profile));
    }

    @Transactional
    public AdminProductProfileView update(UUID profileId, AdminProductProfileRequest request) {
        ProductProfile profile = productProfileRepository.findById(profileId)
            .orElseThrow(() -> new BusinessException("PRODUCT_PROFILE_NOT_FOUND", "产品档案不存在：" + profileId));
        String code = normalizeCode(request.getProductCode());
        if (!code.equalsIgnoreCase(profile.getProductCode())) {
            throw new BusinessException("PRODUCT_PROFILE_CODE_IMMUTABLE", "产品码创建后不可修改：" + profile.getProductCode());
        }
        profile.setName(request.getName().trim());
        profile.setNameEn(trimToNull(request.getNameEn()));
        profile.setDescription(trimToNull(request.getDescription()));
        profile.setDescriptionEn(trimToNull(request.getDescriptionEn()));
        profile.setFeatures(normalizeFeatures(request.getFeatures()));
        return toView(productProfileRepository.save(profile));
    }

    // ==================== 内部转换 ====================

    private AdminProductProfileView toView(ProductProfile profile) {
        long planCount = productRepository.countByProductCodeIgnoreCase(profile.getProductCode());
        return AdminProductProfileView.from(profile, parseFeatures(profile.getFeatures()), planCount);
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String normalizeCode(String rawCode) {
        return rawCode.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 解析产品档案权益 JSON；脏数据降级为空列表并告警，不让整个列表 500。 */
    private List<String> parseFeatures(String featuresJson) {
        if (featuresJson == null || featuresJson.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(objectMapper.readValue(featuresJson, String[].class));
        } catch (JsonProcessingException e) {
            log.warn("解析产品档案权益 JSON 失败：{}", featuresJson, e);
            return List.of();
        }
    }

    /** 归一化权益键：trim + 大写 + 去重；非法键直接拒绝。 */
    private String normalizeFeatures(List<String> features) {
        if (features == null || features.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String raw : features) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.trim().toUpperCase(Locale.ROOT);
            if (!FEATURE_KEY.matcher(key).matches()) {
                throw new BusinessException("INVALID_FEATURE_KEY", "权益键格式非法：" + raw);
            }
            keys.add(key);
        }
        if (keys.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(keys));
        } catch (JsonProcessingException e) {
            throw new BusinessException("INVALID_FEATURE_KEY", "权益清单序列化失败");
        }
    }
}
