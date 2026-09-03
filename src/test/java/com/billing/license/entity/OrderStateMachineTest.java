package com.billing.license.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Order 双状态字段收敛测试（H15 状态机唯一出口 + H14 发货前校验）。
 * 确保 status 与 paymentStatus 永不一致，且 canFulfill 正确阻止重复发货。
 */
class OrderStateMachineTest {

    private Order fresh() {
        return Order.builder()
                .id(UUID.randomUUID())
                .orderNumber("ORD-SM-1")
                .totalAmount(new BigDecimal("10.00"))
                .currency("USD")
                .status(Order.OrderStatus.PENDING)
                .paymentStatus(Order.PaymentStatus.UNPAID)
                .build();
    }

    @Test
    void canFulfill_shouldBeTrue_forFreshOrder() {
        assertTrue(fresh().canFulfill());
    }

    @Test
    void markPaid_convergesBothStatusFields_andSetsPaidAt() {
        Order o = fresh();
        o.markPaid();
        assertEquals(Order.OrderStatus.PAID, o.getStatus());
        assertEquals(Order.PaymentStatus.PAID, o.getPaymentStatus());
        assertNotNull(o.getPaidAt());
        // 已支付订单不可再次发货
        assertFalse(o.canFulfill());
    }

    @Test
    void markRefunded_convergesBothStatusFields() {
        Order o = fresh();
        o.markPaid();
        o.markRefunded();
        assertEquals(Order.OrderStatus.REFUNDED, o.getStatus());
        assertEquals(Order.PaymentStatus.REFUNDED, o.getPaymentStatus());
        assertFalse(o.canFulfill());
    }

    @Test
    void markRefundFailed_keepsPaid_andNeverMarksRefunded() {
        Order o = fresh();
        o.markPaid();
        o.markRefundFailed();
        // H4：退款渠道失败，内部置失败态，但支付状态保持 PAID（钱未退）
        assertEquals(Order.OrderStatus.REFUND_FAILED, o.getStatus());
        assertEquals(Order.PaymentStatus.PAID, o.getPaymentStatus());
        assertFalse(o.canFulfill());
    }

    @Test
    void canFulfill_shouldBlockRefundedCancelledAndRefundFailed() {
        Order r = fresh(); r.markRefunded();
        Order c = fresh(); c.setStatus(Order.OrderStatus.CANCELLED);
        Order f = fresh(); f.markRefundFailed();
        assertAll(
                () -> assertFalse(r.canFulfill()),
                () -> assertFalse(c.canFulfill()),
                () -> assertFalse(f.canFulfill())
        );
    }
}
