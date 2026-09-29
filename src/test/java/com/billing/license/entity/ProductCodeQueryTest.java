package com.billing.license.entity;

import com.billing.license.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V10 产品维度：验证派生查询 findByActiveTrueAndProductCodeIgnoreCase 在真实 JPA/H2 下的语义
 * （大小写不敏感、仅 active、product_code 为 NULL 的行不参与产品码过滤）。
 * 测试 profile 下 schema 由实体生成（flyway.enabled=false），本用例同时兜底实体列映射可建表。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductCodeQueryTest {

    @Autowired
    private ProductRepository productRepository;

    private Product product(String sku, String productCode, boolean active) {
        return Product.builder()
                .sku(sku)
                .name("qtest-" + sku)
                .price(new BigDecimal("9.90"))
                .currency(Currency.USD)
                .priceCny(new BigDecimal("69.00"))
                .priceUsd(new BigDecimal("9.90"))
                .billingCycle(Product.BillingCycle.LIFETIME)
                .tier(PlanTier.PRO)
                .licenseDurationDays(365)
                .productCode(productCode)
                .active(active)
                .build();
    }

    @Test
    void findByActiveTrueAndProductCodeIgnoreCase_matchesIgnoringCaseAndSkipsInactiveAndNull() {
        String code = "QTest-V10";
        Product upper = productRepository.saveAndFlush(product("qtest-v10-a", code, true));
        Product lower = productRepository.saveAndFlush(product("qtest-v10-b", code.toLowerCase(), true));
        productRepository.saveAndFlush(product("qtest-v10-c", code, false));
        productRepository.saveAndFlush(product("qtest-v10-d", null, true));

        List<Product> hit = productRepository.findByActiveTrueAndProductCodeIgnoreCase("qtest-v10");

        assertEquals(2, hit.size());
        assertTrue(hit.stream().anyMatch(p -> p.getSku().equals(upper.getSku())));
        assertTrue(hit.stream().anyMatch(p -> p.getSku().equals(lower.getSku())));
        // 未收录产品码：返回空数组（回退全量是收银台前端职责，后端如实返回空）
        assertTrue(productRepository.findByActiveTrueAndProductCodeIgnoreCase("qtest-not-exist").isEmpty());
        // 无参全量口径不受影响：未归类（product_code=NULL）的 active 商品仍在目录里
        assertTrue(productRepository.findByActiveTrue().stream()
                .anyMatch(p -> p.getSku().equals("qtest-v10-d") && p.getProductCode() == null));
    }
}
