package com.billing.license.service.payment.impl;

import com.billing.license.entity.Order;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H5 退款能力契约测试。
 * 核心不变量：任何渠道在未配置/不可达时，refundPayment 必须返回 false 且不得抛异常——
 * 绝不允许"谎报退款成功"导致账实不符（与 H4 资损防护对齐）。
 */
class RefundStrategyContractTest {

    private final AlipayStrategy alipay = new AlipayStrategy();
    private final WechatPayStrategy wechat = new WechatPayStrategy();
    private final StripeStrategy stripe = new StripeStrategy();
    private final PaddleStrategy paddle = new PaddleStrategy();
    private final PayPalStrategy paypal = new PayPalStrategy();

    private Order sampleOrder() {
        return Order.builder()
                .orderNumber("ORD-TEST-1")
                .totalAmount(new BigDecimal("10.00"))
                .currency("USD")
                .build();
    }

    @Test
    void refund_returnsFalse_whenAlipayUnconfigured() {
        assertFalse(alipay.refundPayment(sampleOrder(), "alipay_ORD-TEST-1", new BigDecimal("10.00")));
    }

    @Test
    void refund_returnsFalse_whenWechatUnconfigured() {
        assertFalse(wechat.refundPayment(sampleOrder(), "wechat_ORD-TEST-1", new BigDecimal("10.00")));
    }

    @Test
    void refund_returnsFalse_whenStripeUnconfigured() {
        assertFalse(stripe.refundPayment(sampleOrder(), "cs_test_xxx", new BigDecimal("10.00")));
    }

    @Test
    void refund_returnsFalse_whenPaddleUnconfigured() {
        assertFalse(paddle.refundPayment(sampleOrder(), "txn_xxx", new BigDecimal("10.00")));
    }

    @Test
    void refund_returnsFalse_whenPaypalUnconfigured() {
        assertFalse(paypal.refundPayment(sampleOrder(), "paypal_xxx", new BigDecimal("10.00")));
    }

    @Test
    void refund_doesNotThrow_whenUnconfigured() {
        Order order = sampleOrder();
        assertDoesNotThrow(() -> {
            alipay.refundPayment(order, "alipay_ORD-TEST-1", new BigDecimal("10.00"));
            wechat.refundPayment(order, "wechat_ORD-TEST-1", new BigDecimal("10.00"));
            stripe.refundPayment(order, "cs_test_xxx", new BigDecimal("10.00"));
            paddle.refundPayment(order, "txn_xxx", new BigDecimal("10.00"));
            paypal.refundPayment(order, "paypal_xxx", new BigDecimal("10.00"));
        });
    }

    @Test
    void getPaymentMethod_contractHolds() {
        assertEquals(PaymentMethod.ALIPAY, alipay.getPaymentMethod());
        assertEquals(PaymentMethod.WECHAT_PAY, wechat.getPaymentMethod());
        assertEquals(PaymentMethod.STRIPE, stripe.getPaymentMethod());
        assertEquals(PaymentMethod.PADDLE, paddle.getPaymentMethod());
        assertEquals(PaymentMethod.PAYPAL, paypal.getPaymentMethod());
    }
}
