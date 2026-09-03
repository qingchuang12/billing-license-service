package com.billing.license.entity;

import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * w11：兑换码场景 License.order=null 必须可持久化（实体 order_id 已改 nullable=true，与 V1 迁移一致）。
 * 曾因 @JoinColumn(nullable=false) 在 ddl-auto 建表下报错；现应成功。
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseNullOrderTest {

    @Autowired
    private LicenseRepository licenseRepository;
    @Autowired
    private ProductRepository productRepository;

    @Test
    void licenseWithNullOrder_shouldPersist() {
        Product product = productRepository.saveAndFlush(Product.builder()
            .sku("null-order-sku")
            .name("null-order-product")
            .price(new BigDecimal("9.90"))
            .currency("USD")
            .licenseDurationDays(365)
            .billingCycle(Product.BillingCycle.ONE_TIME)
            .tier(com.billing.license.entity.PlanTier.PRO)
            .active(true)
            .build());

        License license = License.builder()
            .licenseKey("null-order-key-" + UUID.randomUUID())
            .customerId(UUID.randomUUID())
            .order(null) // 兑换码场景：无关联订单
            .product(product)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(LocalDateTime.now())
            .build();

        assertDoesNotThrow(() -> {
            License saved = licenseRepository.saveAndFlush(license);
            assertNotNull(saved.getId());
        });
    }
}
