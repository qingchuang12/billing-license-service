package com.billing.license.service.payment.util;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AmountValidator 单元测试 - 校验 Webhook 回调金额是否与订单一致
 */
class AmountValidatorTest {

    private final AmountValidator validator = new AmountValidator();

    private Order buildOrder(String currency, String amount) {
        Order order = new Order();
        order.setCurrency(currency);
        order.setTotalAmount(new BigDecimal(amount));
        return order;
    }

    @Test
    void validateAmount_shouldPass_whenAmountsMatch() {
        Order order = buildOrder("USD", "14.99");
        WebhookPayload payload = new WebhookPayload();
        payload.setCurrency("USD");
        payload.setAmount(new BigDecimal("14.99"));

        assertTrue(validator.validateAmount(order, payload));
    }

    @Test
    void validateAmount_shouldPass_withinTolerance() {
        Order order = buildOrder("USD", "14.99");
        WebhookPayload payload = new WebhookPayload();
        payload.setCurrency("USD");
        payload.setAmount(new BigDecimal("14.9901")); // 差 0.0001 < 0.01 容忍

        assertTrue(validator.validateAmount(order, payload));
    }

    @Test
    void validateAmount_shouldFail_whenAmountDiffers() {
        Order order = buildOrder("USD", "14.99");
        WebhookPayload payload = new WebhookPayload();
        payload.setCurrency("USD");
        payload.setAmount(new BigDecimal("9.99"));

        assertFalse(validator.validateAmount(order, payload));
    }

    @Test
    void validateAmount_shouldFail_whenCurrencyMismatch() {
        Order order = buildOrder("USD", "14.99");
        WebhookPayload payload = new WebhookPayload();
        payload.setCurrency("CNY");
        payload.setAmount(new BigDecimal("14.99"));

        assertFalse(validator.validateAmount(order, payload));
    }

    @Test
    void validateAmount_shouldAcceptCnyRmbAlias() {
        Order order = buildOrder("CNY", "99.00");
        WebhookPayload payload = new WebhookPayload();
        payload.setCurrency("RMB");
        payload.setAmount(new BigDecimal("99.00"));

        assertTrue(validator.validateAmount(order, payload));
    }

    @Test
    void validateAmount_shouldFail_whenPayloadNull() {
        assertFalse(validator.validateAmount(buildOrder("USD", "1.00"), null));
    }

    @Test
    void toMinorUnit_shouldConvertToCents() {
        assertEquals(1499L, validator.toMinorUnit(new BigDecimal("14.99"), "USD"));
        assertEquals(9900L, validator.toMinorUnit(new BigDecimal("99.00"), "CNY"));
    }

    @Test
    void toMinorUnit_shouldHandleZeroDecimalCurrency() {
        assertEquals(1500L, validator.toMinorUnit(new BigDecimal("1500"), "JPY"));
    }
}
