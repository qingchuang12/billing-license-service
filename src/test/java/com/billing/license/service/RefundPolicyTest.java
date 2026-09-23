package com.billing.license.service;

import com.billing.license.entity.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RefundPolicy 折算内核测试（plan-4.1）：全周期线性折算 + 资格判定矩阵。
 *
 * <p>口径：可退额 = 实付额 × 剩余天数 ÷ 总天数；可退窗口即 License 有效期。
 */
class RefundPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 10, 0);
    private static final BigDecimal MIN = new BigDecimal("1.00");

    private Order order(String amount, Order.PaymentStatus paymentStatus, Product product) {
        Order order = Order.builder()
            .id(UUID.randomUUID())
            .orderNumber("ORD-RP-1")
            .totalAmount(new BigDecimal(amount))
            .currency(Currency.USD)
            .status(Order.OrderStatus.PAID)
            .paymentStatus(paymentStatus)
            .build();
        if (product != null) {
            order.setOrderItems(List.of(OrderItem.builder().product(product).quantity(1).build()));
        }
        return order;
    }

    private Product product(Product.BillingCycle cycle) {
        Product product = new Product();
        product.setBillingCycle(cycle);
        product.setLicenseDurationDays(365);
        return product;
    }

    private License license(LocalDateTime issuedAt, LocalDateTime expiresAt) {
        return license(issuedAt, expiresAt, License.LicenseStatus.ACTIVE);
    }

    private License license(LocalDateTime issuedAt, LocalDateTime expiresAt, License.LicenseStatus status) {
        License license = new License();
        license.setStatus(status);
        license.setIssuedAt(issuedAt);
        license.setExpiresAt(expiresAt);
        return license;
    }

    private Optional<RefundPolicy.Quote> quote(Order order, List<License> licenses) {
        return RefundPolicy.quote(order, licenses, NOW, MIN);
    }

    @Test
    void quote_proratesLinearlyByRemainingDays() {
        // 总 365 天、已用 100 天、剩 265 天 → 365.00 × 265/365 = 265.00
        Optional<RefundPolicy.Quote> result = quote(
            order("365.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(100), NOW.plusDays(265))));

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("265.00"), result.get().amount());
        assertFalse(result.get().fullRefund(), "未用完整个周期应为部分退款");
    }

    @Test
    void quote_roundsHalfUpToCurrencyScale() {
        // 总 3 天、剩 1 天 → 100.00 / 3 = 33.3333… → 33.33
        Optional<RefundPolicy.Quote> result = quote(
            order("100.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.ONE_TIME)),
            List.of(license(NOW.minusDays(2), NOW.plusDays(1))));

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("33.33"), result.get().amount());
    }

    @Test
    void quote_returnsFull_whenNothingConsumedYet() {
        // 签发即申请：剩余 >= 总天数 → 全额路径（不产生部分退款态）
        Optional<RefundPolicy.Quote> result = quote(
            order("365.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW, NOW.plusDays(365))));

        assertTrue(result.isPresent());
        assertTrue(result.get().fullRefund());
        assertEquals(new BigDecimal("365.00"), result.get().amount());
    }

    @Test
    void quote_aggregatesAcrossMultipleLicenses() {
        // 一单可签发多张 License：总天数取 min(issuedAt)~max(expiresAt)
        Optional<RefundPolicy.Quote> result = quote(
            order("365.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(
                license(NOW.minusDays(100), NOW.plusDays(200)),
                license(NOW.minusDays(90), NOW.plusDays(265))));

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("265.00"), result.get().amount());
    }

    @Test
    void quote_returnsEmpty_whenOrderNotPaid() {
        assertTrue(quote(order("100.00", Order.PaymentStatus.UNPAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(1), NOW.plusDays(30)))).isEmpty());
        assertTrue(quote(order("100.00", Order.PaymentStatus.REFUNDED, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(1), NOW.plusDays(30)))).isEmpty());
    }

    @Test
    void quote_returnsEmpty_whenNoLicenseIssued() {
        assertTrue(quote(order("100.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of()).isEmpty());
        assertTrue(quote(order("100.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            null).isEmpty());
    }

    @Test
    void quote_returnsEmpty_whenAllLicensesRevoked() {
        assertTrue(quote(order("100.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(1), NOW.plusDays(30), License.LicenseStatus.REVOKED))).isEmpty());
    }

    @Test
    void quote_returnsEmpty_whenSubscriptionOrder() {
        // 渠道侧无「取消订阅」能力，退款不取消订阅会持续扣款 → 订阅订单不开放自助退款
        assertTrue(quote(order("19.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.MONTHLY)),
            List.of(license(NOW.minusDays(1), NOW.plusDays(30)))).isEmpty());
        assertTrue(quote(order("19.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.YEARLY)),
            List.of(license(NOW.minusDays(1), NOW.plusDays(365)))).isEmpty());
    }

    @Test
    void quote_returnsEmpty_whenLicenseExpired() {
        assertTrue(quote(order("100.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(366), NOW.minusDays(1)))).isEmpty());
    }

    @Test
    void quote_returnsEmpty_whenProratedBelowMinAmount() {
        // 剩 1 天 / 共 365 天 → 1.00，低于下限 2.00 时不开放自助退款
        Optional<RefundPolicy.Quote> result = RefundPolicy.quote(
            order("365.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(364), NOW.plusDays(1))), NOW, new BigDecimal("2.00"));

        assertTrue(result.isEmpty());
    }

    @Test
    void quote_acceptsProratedEqualsMinAmount() {
        // 边界：正好等于下限应放行（仅「低于」才拦）
        Optional<RefundPolicy.Quote> result = RefundPolicy.quote(
            order("365.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(364), NOW.plusDays(1))), NOW, new BigDecimal("1.00"));

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("1.00"), result.get().amount());
    }

    @Test
    void quote_toleratesNullMinAmount() {
        Optional<RefundPolicy.Quote> result = RefundPolicy.quote(
            order("365.00", Order.PaymentStatus.PAID, product(Product.BillingCycle.LIFETIME)),
            List.of(license(NOW.minusDays(364), NOW.plusDays(1))), NOW, null);

        assertTrue(result.isPresent());
    }
}
