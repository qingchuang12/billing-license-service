package com.billing.license.service.payment;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentAttempt;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentAttemptRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * #55：尝试可多行、资金账不可变且幂等。
 */
class PaymentServiceTest {

    private PaymentServiceFactory factory;
    private PaymentAttemptRepository attemptRepository;
    private PaymentLedgerRepository ledgerRepository;
    private OrderRepository orderRepository;
    private PaymentService service;
    private Order order;

    @BeforeEach
    void setUp() {
        factory = mock(PaymentServiceFactory.class);
        attemptRepository = mock(PaymentAttemptRepository.class);
        ledgerRepository = mock(PaymentLedgerRepository.class);
        orderRepository = mock(OrderRepository.class);
        service = new PaymentService(factory, attemptRepository, ledgerRepository, orderRepository);
        order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-PAY-1")
            .totalAmount(new BigDecimal("99.00")).currency(Currency.USD)
            .status(Order.OrderStatus.PENDING).paymentStatus(Order.PaymentStatus.UNPAID).build();
        when(attemptRepository.save(any(PaymentAttempt.class))).thenAnswer(i -> i.getArgument(0));
        when(ledgerRepository.save(any(PaymentLedgerEntry.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void twoDifferentAttempts_areKeptSeparately() {
        PaymentResponse alipay = new PaymentResponse("alipay_ORD-PAY-1", "PENDING");
        PaymentResponse stripe = new PaymentResponse("cs_test_1", "PENDING");
        when(attemptRepository.findByPaymentId(anyString())).thenReturn(Optional.empty());

        service.recordAttempt(order, PaymentMethod.ALIPAY, alipay);
        service.recordAttempt(order, PaymentMethod.STRIPE, stripe);

        ArgumentCaptor<PaymentAttempt> captor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(attemptRepository, times(2)).save(captor.capture());
        assertEquals(PaymentMethod.ALIPAY, captor.getAllValues().get(0).getMethod());
        assertEquals(PaymentMethod.STRIPE, captor.getAllValues().get(1).getMethod());
        assertEquals("cs_test_1", captor.getAllValues().get(1).getPaymentId());
    }

    @Test
    void recordCharge_writesImmutableLedgerAndSetsOrderProvider() {
        PaymentAttempt attempt = PaymentAttempt.builder().id(7L)
            .paymentId("cs_test_1").method(PaymentMethod.STRIPE).build();
        when(ledgerRepository.findByIdempotencyKey("charge:cs_test_1")).thenReturn(Optional.empty());

        PaymentLedgerEntry entry = service.recordCharge(order, attempt, PaymentMethod.STRIPE,
            new BigDecimal("99.00"), Currency.USD, "txn_1", "charge:cs_test_1", "webhook");

        assertEquals(PaymentLedgerEntry.EntryType.CHARGE, entry.getEntryType());
        assertEquals(new BigDecimal("99.00"), entry.getAmount());
        assertEquals(PaymentMethod.STRIPE, entry.getProvider());
        assertEquals("txn_1", entry.getChannelTxnId());
        assertEquals(7L, entry.getAttemptId());
        assertEquals("CHANNEL_CLEARING", entry.getDebitAccount());
        assertEquals("REVENUE", entry.getCreditAccount());
        assertEquals(PaymentMethod.STRIPE, order.getPaymentProvider());
        assertEquals(PaymentStatus.SUCCESS, attempt.getStatus());
        verify(ledgerRepository).save(any(PaymentLedgerEntry.class));
    }

    @Test
    void recordCharge_isIdempotentByKey() {
        PaymentLedgerEntry existing = PaymentLedgerEntry.builder().idempotencyKey("charge:cs_test_1").build();
        when(ledgerRepository.findByIdempotencyKey("charge:cs_test_1")).thenReturn(Optional.of(existing));

        PaymentLedgerEntry entry = service.recordCharge(order, null, PaymentMethod.STRIPE,
            new BigDecimal("99.00"), Currency.USD, "txn_1", "charge:cs_test_1", "dup");

        assertSame(existing, entry);
        verify(ledgerRepository, never()).save(any());
        assertNull(order.getPaymentProvider());
    }

    @Test
    void recordRefund_usesRefundKey_andNeverRewritesCharge() {
        when(ledgerRepository.findByIdempotencyKey("refund:rf-1")).thenReturn(Optional.empty());

        PaymentLedgerEntry entry = service.recordRefund(order, PaymentMethod.STRIPE, "rf-1",
            new BigDecimal("20.00"), Currency.USD, 7L, "txn_1", "refund");

        assertEquals(PaymentLedgerEntry.EntryType.REFUND, entry.getEntryType());
        assertEquals("refund:rf-1", entry.getIdempotencyKey());
        assertEquals(new BigDecimal("20.00"), entry.getAmount());
        assertEquals(7L, entry.getAttemptId());
        assertEquals("REVENUE_REFUND", entry.getDebitAccount());
        assertEquals("CHANNEL_CLEARING", entry.getCreditAccount());
    }

    @Test
    void recordFee_writesBalancedFeeEntry() {
        when(ledgerRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        PaymentLedgerEntry entry = service.recordFee(order.getId().toString(), order.getOrderNumber(),
            PaymentMethod.ALIPAY, new BigDecimal("0.50"), Currency.USD, "txn_fee_1",
            "fee:ALIPAY:txn_fee_1", "statement");

        assertEquals(PaymentLedgerEntry.EntryType.FEE, entry.getEntryType());
        assertEquals("FEE_EXPENSE", entry.getDebitAccount());
        assertEquals("CHANNEL_CLEARING", entry.getCreditAccount());
        assertEquals(new BigDecimal("0.50"), entry.getAmount());
    }

    @Test
    void recordReversal_swapsAccounts_andIsIdempotent() {
        PaymentLedgerEntry original = PaymentLedgerEntry.builder().id(7L)
            .orderIdStr(order.getId().toString()).orderNumber(order.getOrderNumber())
            .entryType(PaymentLedgerEntry.EntryType.CHARGE).amount(new BigDecimal("99.00"))
            .currency(Currency.USD).provider(PaymentMethod.STRIPE).channelTxnId("txn_1")
            .debitAccount("CHANNEL_CLEARING").creditAccount("REVENUE").build();
        when(ledgerRepository.findById(7L)).thenReturn(Optional.of(original));
        when(ledgerRepository.findByIdempotencyKey("reversal:7")).thenReturn(Optional.empty());

        PaymentLedgerEntry reversal = service.recordReversal(7L, "correction", null);

        assertEquals(PaymentLedgerEntry.EntryType.REVERSAL, reversal.getEntryType());
        assertEquals("REVENUE", reversal.getDebitAccount());
        assertEquals("CHANNEL_CLEARING", reversal.getCreditAccount());
        assertEquals(7L, reversal.getReversalOf());
        assertEquals(new BigDecimal("99.00"), reversal.getAmount());
    }

    @Test
    void lockLatestSuccessfulAttempt_returnsLatestLockedRow() {
        PaymentAttempt paid = PaymentAttempt.builder().id(9L).status(PaymentStatus.SUCCESS).build();
        when(attemptRepository.findForUpdateByOrderAndStatus(order.getId().toString(), PaymentStatus.SUCCESS))
            .thenReturn(List.of(paid));

        assertSame(paid, service.lockLatestSuccessfulAttempt(order.getId().toString()));
    }
}
