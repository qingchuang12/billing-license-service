package com.billing.license.controller;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ProductPublicDto;
import com.billing.license.repository.ProductRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 公开产品目录（K16）。
 * 收银台页面用它取 SKU / 双档价格 / 档位 / 周期 / 权益，零鉴权（SecurityConfig 已 permitAll）。
 */
@Tag(name = "产品目录", description = "公开产品目录（无需鉴权），供收银台页面拉取档位与价格")
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductRepository productRepository;
    private final BillingProperties billingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "列出在售产品",
            description = "返回全部 active=true 的产品（SKU / 双档价格 / 档位 / 周期 / 权益）。公开端点，无需鉴权")
    @GetMapping
    public ResponseEntity<List<ProductPublicDto>> list() {
        Map<String, BillingProperties.FeatureLabel> labels = billingProperties.getFeatureLabels();
        return ResponseEntity.ok(productRepository.findByActiveTrue().stream()
                .map(p -> {
                    ProductPublicDto dto = ProductPublicDto.from(p);
                    dto.setFeatureViews(buildFeatureViews(p.getFeatures(), labels));
                    return dto;
                })
                .toList());
    }

    /**
     * 把 products.features 的原始键（如 OFFLINE）关联成带中英文显示名的视图。
     * 部分功能集天然支持：数组只含产品拥有的键，缺失即不渲染；
     * 解析失败或配置缺失时降级为原始键（前端显示原始键并告警），不抛异常避免整页 500。
     */
    private List<ProductPublicDto.FeatureView> buildFeatureViews(
            String featuresJson, Map<String, BillingProperties.FeatureLabel> labels) {
        List<ProductPublicDto.FeatureView> views = new ArrayList<>();
        if (featuresJson == null || featuresJson.isBlank()) return views;
        try {
            String[] keys = objectMapper.readValue(featuresJson, String[].class);
            for (String key : keys) {
                BillingProperties.FeatureLabel label = labels != null ? labels.get(key) : null;
                String zh = (label != null && label.getZh() != null) ? label.getZh() : key;
                String en = (label != null && label.getEn() != null) ? label.getEn() : key;
                views.add(new ProductPublicDto.FeatureView(key, zh, en));
            }
        } catch (JsonProcessingException e) {
            log.warn("解析产品权益 JSON 失败：{}", featuresJson, e);
        }
        return views;
    }
}
