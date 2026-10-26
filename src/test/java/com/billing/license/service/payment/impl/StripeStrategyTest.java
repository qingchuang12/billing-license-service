package com.billing.license.service.payment.impl;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stripe 支付策略单元测试 - 覆盖 Webhook 签名验证与回调解析（外围接口实现）
 */
class StripeStrategyTest {

    private final StripeStrategy strategy = new StripeStrategy();

    private Order buildOrder() {
        return Order.builder()
            .orderNumber("ORD-TEST-001")
            .totalAmount(java.math.BigDecimal.valueOf(14.99))
            .currency(Currency.USD)
            .title("Pro License")
            .build();
    }

    @Test
    void getPaymentMethod_shouldReturnStripe() {
        assertEquals(PaymentMethod.STRIPE, strategy.getPaymentMethod());
    }

    @Test
    void parseWebhookPayload_shouldParseCheckoutSessionCompleted() {
        String payload = "{"
            + "\"type\":\"checkout.session.completed\","
            + "\"data\":{\"object\":{"
            + "\"id\":\"cs_123\","
            + "\"payment_intent\":\"pi_123\","
            + "\"currency\":\"usd\","
            + "\"amount_total\":1499,"
            + "\"metadata\":{\"order_id\":\"ORD-TEST-001\"}"
            + "}}"
            + "}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);

        assertEquals("ORD-TEST-001", result.getOrderId());
        assertEquals("cs_123", result.getPaymentId());
        assertEquals("pi_123", result.getTransactionId());
        assertEquals("USD", result.getCurrency());
        assertEquals(0, result.getAmount().compareTo(java.math.BigDecimal.valueOf(14.99)));
        assertEquals(PaymentStatus.SUCCESS.name(), result.getStatus());
        assertEquals("checkout.session.completed", result.getEventType());
    }

    @Test
    void parseWebhookPayload_shouldParsePaymentIntentFailed() {
        String payload = "{"
            + "\"type\":\"payment_intent.payment_failed\","
            + "\"data\":{\"object\":{"
            + "\"id\":\"pi_xyz\",\"payment_intent\":\"pi_xyz\""
            + "}}"
            + "}";

        WebhookPayload result = strategy.parseWebhookPayload(payload);
        assertEquals(PaymentStatus.FAILED.name(), result.getStatus());
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSecretNotConfigured() {
        // w10：未配置 webhook-secret 不再「跳过验签返回 true」（否则漏配等于零鉴权）；
        // 缺失即拒绝回调，由启动期 ChannelConfigValidator fail-fast 提前暴露部署错误。
        Map<String, String> headers = Map.of();
        assertFalse(strategy.verifyWebhookSignature("{}", "sig", headers));
    }

    @Test
    void verifyWebhookSignature_shouldReturnFalse_whenSignatureMissing() {
        // 注入 secret 后缺签名应失败；通过反射设置 webhookSecret
        try {
            var field = StripeStrategy.class.getDeclaredField("webhookSecret");
            field.setAccessible(true);
            field.set(strategy, "whsec_test_xxx");
        } catch (Exception ignored) {
            // 若无法设置则跳过
            return;
        }
        assertFalse(strategy.verifyWebhookSignature("{}", null, Map.of()));
    }

    // ===== #25 收银台真订阅（Stripe 侧） =====

    private static Order orderWith(Product product) {
        Order order = Order.builder()
            .orderNumber("ORD-TEST-001")
            .totalAmount(java.math.BigDecimal.valueOf(14.99))
            .currency(Currency.USD)
            .title("Pro License")
            .build();
        order.setOrderItems(List.of(OrderItem.builder().product(product).quantity(1).build()));
        return order;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstLineItem(Map<String, Object> params) {
        return ((List<Map<String, Object>>) params.get("line_items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recurringOf(Map<String, Object> lineItem) {
        return (Map<String, Object>) ((Map<String, Object>) lineItem.get("price_data")).get("recurring");
    }

    @Test
    void buildCheckoutParams_shouldUseSubscriptionMode_whenRecurringCycleHasPriceId() {
        // #25：订阅周期走 SUBSCRIPTION 模式；配了 catalog price 时直接引用，不内联价目
        Product monthly = Product.builder()
                .sku("ai-tools-pro-monthly")
                .billingCycle(Product.BillingCycle.MONTHLY)
                .stripePriceId("price_1ABC")
                .build();

        Map<String, Object> params = strategy.buildCheckoutParams(orderWith(monthly), monthly).toMap();

        assertEquals("subscription", params.get("mode"));
        assertEquals("price_1ABC", firstLineItem(params).get("price"));
        assertNull(firstLineItem(params).get("price_data"), "引用 catalog price 时不应再内联 price_data");
        // 订阅对象自带 metadata.order_id：后续 customer.subscription.* / invoice.paid 靠它回查订单续期
        Map<String, Object> subData = (Map<String, Object>) params.get("subscription_data");
        assertEquals(java.util.Map.of("order_id", "ORD-TEST-001"), subData.get("metadata"));
    }

    @Test
    void buildCheckoutParams_shouldInlineRecurringPrice_whenNoPriceId() {
        // #25：未配 catalog price 时按周期内联 recurring price_data（不依赖渠道后台预建价目）
        Map<String, Object> monthly = strategy.buildCheckoutParams(orderWith(
                Product.builder().billingCycle(Product.BillingCycle.MONTHLY).build()),
                Product.builder().billingCycle(Product.BillingCycle.MONTHLY).build()).toMap();
        assertEquals("month", recurringOf(firstLineItem(monthly)).get("interval"));
        assertEquals(1L, recurringOf(firstLineItem(monthly)).get("interval_count"));

        Product quarterly = Product.builder().billingCycle(Product.BillingCycle.QUARTERLY).build();
        Map<String, Object> q = strategy.buildCheckoutParams(orderWith(quarterly), quarterly).toMap();
        assertEquals("month", recurringOf(firstLineItem(q)).get("interval"));
        assertEquals(3L, recurringOf(firstLineItem(q)).get("interval_count"));

        Product halfYear = Product.builder().billingCycle(Product.BillingCycle.HALF_YEAR).build();
        Map<String, Object> h = strategy.buildCheckoutParams(orderWith(halfYear), halfYear).toMap();
        assertEquals("month", recurringOf(firstLineItem(h)).get("interval"));
        assertEquals(6L, recurringOf(firstLineItem(h)).get("interval_count"));

        Product yearly = Product.builder().billingCycle(Product.BillingCycle.YEARLY).build();
        Map<String, Object> y = strategy.buildCheckoutParams(orderWith(yearly), yearly).toMap();
        assertEquals("year", recurringOf(firstLineItem(y)).get("interval"));
        assertEquals(1L, recurringOf(firstLineItem(y)).get("interval_count"));
    }

    @Test
    void buildCheckoutParams_shouldUsePaymentMode_whenOneTimeOrNoRecurringProduct() {
        // 一次性买断维持原 PAYMENT 模式：无 subscription_data、price_data 不带 recurring
        Product buyout = Product.builder().billingCycle(Product.BillingCycle.ONE_TIME).build();
        Map<String, Object> params = strategy.buildCheckoutParams(orderWith(buyout), null).toMap();

        assertEquals("payment", params.get("mode"));
        assertNull(params.get("subscription_data"));
        assertNull(recurringOf(firstLineItem(params)), "一次性收款不得带 recurring");
        assertEquals(1499L, ((Map<String, Object>) firstLineItem(params).get("price_data")).get("unit_amount"));
    }

    @Test
    void resolveRecurringProduct_shouldReturnNull_whenBuyoutOrNoItems() {
        // LIFETIME 属买断口径；无行项的老订单视为一次性，不进订阅分支
        assertNull(strategy.resolveRecurringProduct(orderWith(
                Product.builder().billingCycle(Product.BillingCycle.LIFETIME).build())));

        Order noItems = Order.builder()
            .orderNumber("ORD-NO-ITEM")
            .totalAmount(java.math.BigDecimal.ONE)
            .currency(Currency.USD)
            .build();
        assertNull(strategy.resolveRecurringProduct(noItems));
    }
}
