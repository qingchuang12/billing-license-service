package com.billing.license.controller;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ProductPublicDto;
import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import com.billing.license.entity.ProductProfile;
import com.billing.license.repository.ProductProfileRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductController 单元测试（K16 + V10 产品维度）—— 覆盖公开目录端点：仅返回 active 产品、
 * 字段映射正确、product 参数按产品码过滤（缺省/空白保持全量，向后兼容）、零鉴权由 SecurityConfig 保障。
 */
class ProductControllerTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductProfileRepository productProfileRepository = mock(ProductProfileRepository.class);
    private final PaymentServiceFactory paymentServiceFactory = mock(PaymentServiceFactory.class);
    private final BillingProperties billingProperties = new BillingProperties();
    private final ProductController controller =
        new ProductController(productRepository, productProfileRepository, billingProperties, paymentServiceFactory);

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
        // 权益属于产品档案（按 product_code 读取）
        when(productProfileRepository.findByProductCodeIgnoreCase("ai-tools"))
            .thenReturn(Optional.of(ProductProfile.builder()
                .productCode("ai-tools").name("AI-Tools")
                .features("[\"OFFLINE\",\"MULTI_DEVICE\"]").build()));
        // 渠道能力矩阵：目录只展示支持该套餐周期的已启用渠道（这里固定给 STRIPE）
        when(paymentServiceFactory.getEnabledMethods(any())).thenReturn(List.of(PaymentMethod.STRIPE));
    }

    private Product sample(boolean active) {
        return Product.builder()
                .sku(active ? "pro-buyout" : "pro-archived")
                .productCode("ai-tools")
                .name(active ? "Pro 买断" : "已下架 Pro")
                .description("demo")
                .priceCny(new BigDecimal("712.80"))
                .priceUsd(new BigDecimal("99.00"))
                .billingCycle(Product.BillingCycle.LIFETIME)
                .tier(PlanTier.PRO)
                .licenseDurationDays(365)
                .active(active)
                .build();
    }

    @Test
    void list_returnsOnlyActiveProducts() {
        when(productRepository.findByActiveTrue()).thenReturn(List.of(sample(true)));

        ResponseEntity<List<ProductPublicDto>> resp = controller.list(null);

        assertEquals(200, resp.getStatusCode().value());
        List<ProductPublicDto> body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        ProductPublicDto dto = body.get(0);
        assertEquals("pro-buyout", dto.getSku());
        assertEquals("ai-tools", dto.getProductCode());
        assertEquals(new BigDecimal("712.80"), dto.getPriceCny());
        assertEquals(new BigDecimal("99.00"), dto.getPriceUsd());
        assertEquals(Product.BillingCycle.LIFETIME, dto.getBillingCycle());
        assertEquals(PlanTier.PRO, dto.getTier());
        assertEquals("[\"OFFLINE\",\"MULTI_DEVICE\"]", dto.getFeatures());
        // 权益与推荐位来自产品档案 / 套餐配置
        assertNotNull(dto.getRecommended());
        // 权益显示名配置化：原始键经 BillingProperties.featureLabels 关联成中英文视图
        assertNotNull(dto.getFeatureViews());
        assertEquals(2, dto.getFeatureViews().size());
        assertEquals("OFFLINE", dto.getFeatureViews().get(0).getKey());
        assertEquals("离线可用", dto.getFeatureViews().get(0).getLabelZh());
        assertEquals("多设备授权", dto.getFeatureViews().get(1).getLabelZh());
        // 可售渠道来自渠道能力矩阵（已启用且支持该套餐周期）
        assertNotNull(dto.getProviders());
        assertEquals(1, dto.getProviders().size());
        assertEquals("STRIPE", dto.getProviders().get(0).method());
        assertTrue(dto.getProviders().get(0).supportsRecurring());
    }

    @Test
    void list_shouldSortRecommendedFirstThenSkuAndExposeRecurring() {
        Product monthly = Product.builder().sku("a-monthly").productCode("ai-tools").name("月付")
            .priceCny(new BigDecimal("19.00")).priceUsd(new BigDecimal("3.00"))
            .billingCycle(Product.BillingCycle.MONTHLY).tier(PlanTier.PRO)
            .licenseDurationDays(31).active(true).recommended(false).build();
        Product recommended = Product.builder().sku("z-buyout").productCode("ai-tools").name("推荐买断")
            .priceCny(new BigDecimal("712.80")).priceUsd(new BigDecimal("99.00"))
            .billingCycle(Product.BillingCycle.LIFETIME).tier(PlanTier.PRO)
            .licenseDurationDays(365).active(true).recommended(true).build();
        when(productRepository.findByActiveTrue()).thenReturn(List.of(monthly, recommended));

        List<ProductPublicDto> body = controller.list(null).getBody();

        assertNotNull(body);
        // 推荐位优先，其余按 SKU 升序——接入方每次拿到相同顺序
        assertEquals(List.of("z-buyout", "a-monthly"),
            body.stream().map(ProductPublicDto::getSku).toList());
        assertTrue(body.get(0).getRecommended());
        assertFalse(body.get(0).getRecurring(), "买断套餐不应标记为自动续费");
        assertTrue(body.get(1).getRecurring(), "月付套餐应标记为自动续费");
    }

    @Test
    void list_emptyWhenNoActive() {
        when(productRepository.findByActiveTrue()).thenReturn(List.of());
        ResponseEntity<List<ProductPublicDto>> resp = controller.list(null);
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isEmpty());
    }

    @Test
    void list_filtersByProductCodeWhenProvided() {
        when(productRepository.findByActiveTrueAndProductCodeIgnoreCase("ai-tools"))
                .thenReturn(List.of(sample(true)));

        // 参数带首尾空白应先 trim 再查（大小写归一化由 IgnoreCase 查询负责）
        ResponseEntity<List<ProductPublicDto>> resp = controller.list("  ai-tools  ");

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().size());
        assertEquals("ai-tools", resp.getBody().get(0).getProductCode());
        verify(productRepository).findByActiveTrueAndProductCodeIgnoreCase("ai-tools");
        verify(productRepository, never()).findByActiveTrue();
    }

    @Test
    void list_emptyWhenProductCodeUnknown() {
        // 未知产品码：后端如实返回空数组——「回退全量」是收银台前端职责，不在服务端做
        when(productRepository.findByActiveTrueAndProductCodeIgnoreCase("no-such-code"))
                .thenReturn(List.of());

        ResponseEntity<List<ProductPublicDto>> resp = controller.list("no-such-code");

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isEmpty());
    }

    @Test
    void list_blankParamKeepsLegacyFullCatalogBehavior() {
        when(productRepository.findByActiveTrue()).thenReturn(List.of(sample(true)));

        ResponseEntity<List<ProductPublicDto>> resp = controller.list("   ");

        assertEquals(1, resp.getBody().size());
        verify(productRepository).findByActiveTrue();
        verify(productRepository, never()).findByActiveTrueAndProductCodeIgnoreCase(anyString());
    }
}
