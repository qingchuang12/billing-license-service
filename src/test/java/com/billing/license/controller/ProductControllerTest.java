package com.billing.license.controller;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ProductPublicDto;
import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import com.billing.license.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProductController 单元测试（K16）—— 覆盖公开目录端点：仅返回 active 产品、字段映射正确、零鉴权由 SecurityConfig 保障。
 */
class ProductControllerTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final BillingProperties billingProperties = new BillingProperties();
    private final ProductController controller = new ProductController(productRepository, billingProperties);

    @BeforeEach
    void setUp() {
        Map<String, BillingProperties.FeatureLabel> labels = new HashMap<>();
        BillingProperties.FeatureLabel offline = new BillingProperties.FeatureLabel();
        offline.setZh("离线可用");
        offline.setEn("Offline capable");
        BillingProperties.FeatureLabel multi = new BillingProperties.FeatureLabel();
        multi.setZh("多设备授权");
        multi.setEn("Multi-device");
        labels.put("OFFLINE", offline);
        labels.put("MULTI_DEVICE", multi);
        billingProperties.setFeatureLabels(labels);
    }

    private Product sample(boolean active) {
        return Product.builder()
                .sku(active ? "pro-buyout" : "pro-archived")
                .name(active ? "Pro 买断" : "已下架 Pro")
                .description("demo")
                .priceCny(new BigDecimal("712.80"))
                .priceUsd(new BigDecimal("99.00"))
                .billingCycle(Product.BillingCycle.LIFETIME)
                .tier(PlanTier.PRO)
                .features("[\"OFFLINE\",\"MULTI_DEVICE\"]")
                .licenseDurationDays(365)
                .active(active)
                .build();
    }

    @Test
    void list_returnsOnlyActiveProducts() {
        when(productRepository.findByActiveTrue()).thenReturn(List.of(sample(true)));

        ResponseEntity<List<ProductPublicDto>> resp = controller.list();

        assertEquals(200, resp.getStatusCode().value());
        List<ProductPublicDto> body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        ProductPublicDto dto = body.get(0);
        assertEquals("pro-buyout", dto.getSku());
        assertEquals(new BigDecimal("712.80"), dto.getPriceCny());
        assertEquals(new BigDecimal("99.00"), dto.getPriceUsd());
        assertEquals(Product.BillingCycle.LIFETIME, dto.getBillingCycle());
        assertEquals(PlanTier.PRO, dto.getTier());
        assertEquals("[\"OFFLINE\",\"MULTI_DEVICE\"]", dto.getFeatures());
        // 权益显示名配置化：原始键经 BillingProperties.featureLabels 关联成中英文视图
        assertNotNull(dto.getFeatureViews());
        assertEquals(2, dto.getFeatureViews().size());
        assertEquals("OFFLINE", dto.getFeatureViews().get(0).getKey());
        assertEquals("离线可用", dto.getFeatureViews().get(0).getLabelZh());
        assertEquals("多设备授权", dto.getFeatureViews().get(1).getLabelZh());
    }

    @Test
    void list_emptyWhenNoActive() {
        when(productRepository.findByActiveTrue()).thenReturn(List.of());
        ResponseEntity<List<ProductPublicDto>> resp = controller.list();
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isEmpty());
    }
}
