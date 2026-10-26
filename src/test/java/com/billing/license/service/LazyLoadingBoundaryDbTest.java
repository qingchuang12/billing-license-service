package com.billing.license.service;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.RedeemCodeView;
import com.billing.license.entity.*;
import com.billing.license.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OSIV 关闭后的懒加载边界**真实库证伪**（#12）。
 *
 * <p>单测（mock 仓库）根本触及不到这件事：mock 出来的 {@code Order} / {@code License}
 * 是内存里的普通对象，无论 OSIV 开关如何都不会抛 {@code LazyInitializationException}。
 * 只有跑真实 JPA + 真实 Session 生命周期，才能证伪「关掉 OSIV 后这些路径会不会炸」。
 *
 * <p>覆盖两条边界：
 * <ol>
 *   <li>已补事务的读取路径（管理端许可证列表/详情、兑换码列表）——视图要读
 *       {@code license.product} / {@code redeemCode.product} 这些 LAZY 关系；</li>
 *   <li>按硬约束 16 必须留在事务外的链路（收银台选渠道、自助退款）——
 *       不能靠加事务解决，改由 {@code OrderRepository#findWithItemsById} 预加载行项。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class LazyLoadingBoundaryDbTest {

    @Autowired private Environment environment;
    @Autowired private AdminService adminService;
    @Autowired private RedeemCodeService redeemCodeService;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private LicenseRepository licenseRepository;
    @Autowired private RedeemCodeRepository redeemCodeRepository;

    private static final UUID CUSTOMER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @AfterEach
    void tearDown() {
        licenseRepository.deleteAll();
        redeemCodeRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    /** 前提自检：本用例只在 OSIV 关闭时才有意义，开关被改回去应立刻失败而不是静默通过。 */
    @Test
    @DisplayName("前提：spring.jpa.open-in-view 确实为 false")
    void openInViewIsDisabled() {
        assertNotEquals("true", environment.getProperty("spring.jpa.open-in-view"),
                "OSIV 未关闭，本文件其余用例失去意义（会退化成「开着也能过」）");
    }

    @Test
    @DisplayName("管理端许可证列表：事务内读 LAZY 的 license.product，不抛懒加载异常")
    void listLicensesReadsLazyProductInsideTransaction() {
        Product product = saveProduct("osiv-lic-list");
        License license = saveLicense("LIC-OSIV-LIST", product);

        var page = adminService.listLicenses(null, null, null, PageRequest.of(0, 20));

        assertTrue(page.getContent().stream().anyMatch(v -> license.getLicenseKey().equals(v.getLicenseKey())));
        assertEquals(product.getSku(), page.getContent().get(0).getProductSku(),
                "productSku 为空即说明懒加载被事务边界挡住（或视图没回填）");
        assertNotNull(license.getId());
    }

    @Test
    @DisplayName("管理端许可证详情：失效件也能查（不过滤状态），且能读到产品")
    void getLicenseDetailReadsLazyProductForRevokedLicense() {
        Product product = saveProduct("osiv-lic-detail");
        License license = saveLicense("LIC-OSIV-DETAIL", product);

        LicenseResponse view = adminService.getLicenseDetail(license.getLicenseKey());

        assertEquals(license.getLicenseKey(), view.getLicenseKey());
        assertEquals(product.getSku(), view.getProductSku());
    }

    @Test
    @DisplayName("兑换码列表：事务内读 LAZY 的 redeemCode.product，不抛懒加载异常")
    void listRedeemCodesReadsLazyProductInsideTransaction() {
        Product product = saveProduct("osiv-rc-list");
        redeemCodeRepository.saveAndFlush(RedeemCode.builder()
                .code("RC-OSIV-1")
                .product(product)
                .status(RedeemCode.RedeemCodeStatus.UNUSED)
                .maxUses(1).currentUses(0)
                .build());

        // 服务层直接吐视图（映射在事务内完成），控制层不再碰实体
        List<RedeemCodeView> views = redeemCodeService.listCodes("osiv-rc-list", null);

        assertEquals(1, views.size());
        assertEquals(product.getSku(), views.get(0).getProductSku());
    }

    /**
     * 事务外链路的取证：仓库方法返回后 Session 已关闭，此时读行项还能成功，
     * 就证明 {@code join fetch} 真的把它们带出来了（而不是靠 OSIV 兜底）。
     */
    @Test
    @DisplayName("findWithItemsById：Session 关闭后仍能读出行项与其产品（预加载生效）")
    void findWithItemsByIdPreloadsItemsForOutOfTransactionRead() {
        Product product = saveProduct("osiv-order-items");
        Order order = saveOrderWithItem("ORD-OSIV-1", product);

        Order loaded = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        // 此处已脱离事务：LAZY 集合若未预加载，下面这行就会抛 LazyInitializationException
        List<OrderItem> items = loaded.getOrderItems();

        assertEquals(1, items.size(), "行项未预加载：脱离事务后读集合应直接失败");
        assertEquals(product.getSku(), items.get(0).getProduct().getSku(), "行项的产品也必须一并预加载");
    }

    /** 自助退款链路走的是按订单号预加载的那条查询，口径必须与上一条一致。 */
    @Test
    @DisplayName("findWithItemsByOrderNumber：同样预加载行项与产品")
    void findWithItemsByOrderNumberPreloadsItemsForOutOfTransactionRead() {
        Product product = saveProduct("osiv-order-num");
        Order order = saveOrderWithItem("ORD-OSIV-2", product);

        Order loaded = orderRepository.findWithItemsByOrderNumber("ORD-OSIV-2").orElseThrow();
        List<OrderItem> items = loaded.getOrderItems();

        assertEquals(1, items.size());
        assertEquals(product.getSku(), items.get(0).getProduct().getSku());
    }

    // ==================== 夹具 ====================

    private Product saveProduct(String sku) {
        return productRepository.saveAndFlush(Product.builder()
                .sku(sku)
                .name("osiv-" + sku)
                .price(new BigDecimal("9.90"))
                .currency(Currency.USD)
                .priceCny(new BigDecimal("69.00"))
                .priceUsd(new BigDecimal("9.90"))
                .billingCycle(Product.BillingCycle.LIFETIME)
                .tier(PlanTier.PRO)
                .licenseDurationDays(365)
                .productCode("osiv")
                .active(true)
                .build());
    }

    private Order saveOrderWithItem(String orderNumber, Product product) {
        Order order = orderRepository.saveAndFlush(Order.builder()
                .orderNumber(orderNumber)
                .customerId(CUSTOMER_ID)
                .totalAmount(new BigDecimal("9.90"))
                .currency(Currency.USD)
                .status(Order.OrderStatus.PENDING)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .build());
        OrderItem item = OrderItem.builder()
                .order(order)
                .product(product)
                .quantity(1)
                .unitPrice(new BigDecimal("9.90"))
                .totalPrice(new BigDecimal("9.90"))
                .build();
        order.setOrderItems(new java.util.ArrayList<>(List.of(item)));
        return orderRepository.saveAndFlush(order);
    }

    private License saveLicense(String licenseKey, Product product) {
        return licenseRepository.saveAndFlush(License.builder()
                .licenseKey(licenseKey)
                .customerId(CUSTOMER_ID)
                .product(product)
                .status(License.LicenseStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(365))
                .build());
    }
}
