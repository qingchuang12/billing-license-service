package com.billing.license.controller;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ProductPublicDto;
import com.billing.license.dto.ProviderCapability;
import com.billing.license.entity.Product;
import com.billing.license.entity.ProductProfile;
import com.billing.license.repository.ProductProfileRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    /** 目录顺序：推荐位优先，其余按 SKU 升序——保证接入方每次拿到相同顺序，不依赖数据库返回序。 */
    private static final Comparator<Product> CATALOG_ORDER = Comparator
        .comparing((Product p) -> Boolean.TRUE.equals(p.getRecommended())).reversed()
        .thenComparing(Product::getSku);

    private final ProductRepository productRepository;
    private final ProductProfileRepository productProfileRepository;
    private final BillingProperties billingProperties;
    private final PaymentServiceFactory paymentServiceFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "列出在售产品",
            description = "返回全部 active=true 的产品（SKU / 产品码 / 双档价格 / 档位 / 周期 / 权益）。"
                    + "可选 product 参数按产品码（products.product_code，大小写不敏感）过滤目录，缺省或空白返回全量。公开端点，无需鉴权")
    @GetMapping
    public ResponseEntity<List<ProductPublicDto>> list(
            @RequestParam(name = "product", required = false) String product) {
        // 产品维度（V10）：product 仅作目录过滤条件；空白 = 全量，保持无参调用行为不变
        String productCode = product == null ? "" : product.trim();
        Map<String, BillingProperties.FeatureLabel> labels = billingProperties.getFeatureLabels();
        List<Product> catalog = productCode.isEmpty()
                ? productRepository.findByActiveTrue()
                : productRepository.findByActiveTrueAndProductCodeIgnoreCase(productCode);
        catalog = catalog.stream().sorted(CATALOG_ORDER).toList();
        Map<String, String> profileFeatures = loadProfileFeatures(catalog);
        return ResponseEntity.ok(catalog.stream()
                .map(p -> {
                    String features = profileFeatures.getOrDefault(normalizeCode(p.getProductCode()), null);
                    ProductPublicDto dto = ProductPublicDto.from(p, features);
                    dto.setFeatureViews(buildFeatureViews(features, labels));
                    dto.setProviders(providerCapabilities(p.getBillingCycle()));
                    return dto;
                })
                .toList());
    }

    /** 一次性载入本批 SKU 对应的产品档案权益，避免逐行查库。 */
    private Map<String, String> loadProfileFeatures(List<Product> catalog) {
        Map<String, String> features = new HashMap<>();
        for (Product product : catalog) {
            String code = normalizeCode(product.getProductCode());
            if (code == null || features.containsKey(code)) {
                continue;
            }
            ProductProfile profile = productProfileRepository.findByProductCodeIgnoreCase(code).orElse(null);
            features.put(code, profile != null ? profile.getFeatures() : null);
        }
        return features;
    }

    private static String normalizeCode(String productCode) {
        return productCode == null || productCode.isBlank()
            ? null
            : productCode.trim().toLowerCase();
    }

    /** 当前部署下可售该套餐的渠道：按渠道能力矩阵过滤（未启用渠道不展示）。 */
    private List<ProviderCapability> providerCapabilities(Product.BillingCycle cycle) {
        return paymentServiceFactory.getEnabledMethods(cycle).stream()
            .map(ProviderCapability::from)
            .toList();
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
