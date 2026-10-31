package com.billing.license.service;

import com.billing.license.entity.Currency;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.RedeemCode;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 退款结算：撤销权益、累计多次部分退款、同退款号幂等。
 */
class RefundSettlementServiceTest {

    private LicenseRepository licenseRepository;
    private RedeemCodeRepository redeemCodeRepository;
    private PaymentLedgerRepository ledgerRepository;
    private PaymentService paymentService;
    private LicenseService licenseService;
    private RefundSettlementService service;
    private Order order;

    @BeforeEach
    void setUp() {
        licenseRepository = mock(LicenseRepository.class);
        redeemCodeRepository = mock(RedeemCodeRepository.class);
        ledgerRepository = mock(PaymentLedgerRepository.class);
        paymentService = mock(PaymentService.class);
        licenseService = mock(LicenseService.class);
        service = new RefundSettlementService(licenseRepository, redeemCodeRepository,
            ledgerRepository, paymentService, licenseService);
        order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-RS-1")
            .totalAmount(new BigDecimal("100.00")).currency(Currency.USD)
            .status(Order.OrderStatus.PAID).paymentStatus(Order.PaymentStatus.PAID).build();
        when(licenseRepository.findByOrder(order)).thenReturn(List.of());
        when(redeemCodeRepository.findByOrderIdForUpdate("ORD-RS-1")).thenReturn(List.of());
        when(ledgerRepository.existsByIdempotencyKey(any())).thenReturn(false);
    }

    @Test
    void singlePartialRefund_marksPartiallyRefunded_andRecordsAmount() {
        service.settle(order, PaymentMethod.STRIPE, "rf-1", new BigDecimal("20.00"), "USD", "test");

        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.PAID, order.getStatus());
        assertTrue(order.getMetadata().contains("\"refundAmount\":\"20.00\""));
        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.STRIPE), eq("rf-1"),
            eq(new BigDecimal("20.00")), eq(Currency.USD), isNull(), any(), anyString());
    }

    @Test
    void multiplePartialRefunds_accumulateUntilFull() {
        service.settle(order, PaymentMethod.STRIPE, "rf-1", new BigDecimal("20.00"), "USD", "p1");
        service.settle(order, PaymentMethod.STRIPE, "rf-2", new BigDecimal("30.00"), "USD", "p2");
        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());
        assertTrue(order.getMetadata().contains("\"refundAmount\":\"50.00\""));

        service.settle(order, PaymentMethod.STRIPE, "rf-3", new BigDecimal("50.00"), "USD", "p3");
        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.REFUNDED, order.getStatus());
        assertTrue(order.getMetadata().contains("\"refundAmount\":\"100.00\""));
        verify(paymentService, times(3)).recordRefund(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateRefundId_doesNotDoubleCount() {
        when(ledgerRepository.existsByIdempotencyKey("refund:rf-1")).thenReturn(true);

        service.settle(order, PaymentMethod.STRIPE, "rf-1", new BigDecimal("20.00"), "USD", "dup");

        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
        verify(paymentService, never()).recordRefund(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * 累计口径（支付宝 refund_fee）：第二笔通知报文里是「总退款额 50.00」，
     * 首笔已按 30.00 入账，本次只应入 20.00——按报文原值入账会虚增 30.00。
     */
    @Test
    void cumulativeRefund_recordsOnlyDelta() {
        service.settle(order, PaymentMethod.ALIPAY, "n-1", new BigDecimal("30.00"), "USD", "p1",
            null, true);
        service.settle(order, PaymentMethod.ALIPAY, "n-2", new BigDecimal("50.00"), "USD", "p2",
            null, true);

        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.ALIPAY), eq("n-1"),
            eq(new BigDecimal("30.00")), eq(Currency.USD), isNull(), any(), anyString());
        verify(paymentService).recordRefund(eq(order), eq(PaymentMethod.ALIPAY), eq("n-2"),
            eq(new BigDecimal("20.00")), eq(Currency.USD), isNull(), any(), anyString());
        assertTrue(order.getMetadata().contains("\"refundAmount\":\"50.00\""));
    }

    /** 累计口径下重投同一通知、或乱序后到的小额通知，都不产生新增退款，不得再记一笔。 */
    @Test
    void cumulativeRefund_skipsWhenNoNewAmount() {
        service.settle(order, PaymentMethod.ALIPAY, "n-1", new BigDecimal("50.00"), "USD", "p1",
            null, true);
        // 重投（换事件号，金额不变）
        service.settle(order, PaymentMethod.ALIPAY, "n-2", new BigDecimal("50.00"), "USD", "p1",
            null, true);
        // 乱序：先到 50.00，后到 30.00
        service.settle(order, PaymentMethod.ALIPAY, "n-3", new BigDecimal("30.00"), "USD", "p1",
            null, true);

        verify(paymentService, times(1)).recordRefund(any(), any(), any(), any(), any(), any(), any(), any());
        assertTrue(order.getMetadata().contains("\"refundAmount\":\"50.00\""));
    }

    /** 累计额达到订单总额即置全额退款并撤权。 */
    @Test
    void cumulativeRefund_reachingTotalMarksRefunded() {
        License license = License.builder().licenseKey("L-9")
            .status(License.LicenseStatus.ACTIVE).build();
        when(licenseRepository.findByOrder(order)).thenReturn(List.of(license));

        service.settle(order, PaymentMethod.ALIPAY, "n-1", new BigDecimal("60.00"), "USD", "p1",
            null, true);
        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, order.getPaymentStatus());

        service.settle(order, PaymentMethod.ALIPAY, "n-2", new BigDecimal("100.00"), "USD", "p2",
            null, true);

        assertEquals(Order.PaymentStatus.REFUNDED, order.getPaymentStatus());
        assertEquals(Order.OrderStatus.REFUNDED, order.getStatus());
        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
    }

    @Test
    void refund_revokesLicenseAndUnusedRedeemCode() {
        License license = License.builder().licenseKey("L-1")
            .status(License.LicenseStatus.ACTIVE).build();
        RedeemCode unused = RedeemCode.builder().code("RC-1")
            .status(RedeemCode.RedeemCodeStatus.UNUSED).build();
        RedeemCode used = RedeemCode.builder().code("RC-2")
            .status(RedeemCode.RedeemCodeStatus.USED).build();
        when(licenseRepository.findByOrder(order)).thenReturn(List.of(license));
        when(redeemCodeRepository.findByOrderIdForUpdate("ORD-RS-1")).thenReturn(List.of(unused, used));

        service.settle(order, PaymentMethod.STRIPE, "rf-full", new BigDecimal("100.00"), "USD", "full");

        assertEquals(License.LicenseStatus.REVOKED, license.getStatus());
        assertEquals(RedeemCode.RedeemCodeStatus.REVOKED, unused.getStatus());
        assertEquals(RedeemCode.RedeemCodeStatus.USED, used.getStatus());
        verify(licenseService).recordLicenseEvent(eq(license), any(), any(), contains("ORD-RS-1"));
    }
}
