package com.billing.license.service;

import com.billing.license.dto.accounting.ChannelReconciliationResponse;
import com.billing.license.dto.accounting.ChannelStatementLineRequest;
import com.billing.license.dto.accounting.ChannelStatementLineView;
import com.billing.license.dto.accounting.LedgerEntryView;
import com.billing.license.entity.ChannelStatementLine;
import com.billing.license.entity.Currency;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.repository.ChannelStatementLineRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelReconciliationServiceTest {

    private ChannelStatementLineRepository statementRepository;
    private PaymentLedgerRepository ledgerRepository;
    private PaymentService paymentService;
    private ChannelReconciliationService service;

    @BeforeEach
    void setUp() {
        statementRepository = mock(ChannelStatementLineRepository.class);
        ledgerRepository = mock(PaymentLedgerRepository.class);
        paymentService = mock(PaymentService.class);
        service = new ChannelReconciliationService(statementRepository, ledgerRepository,
            paymentService, mock(OrderRepository.class));
        when(statementRepository.save(any())).thenAnswer(i -> {
            ChannelStatementLine line = i.getArgument(0);
            if (line.getId() == null) line.setId(1L);
            return line;
        });
    }

    private ChannelStatementLineRequest request(String type, String amount, String fee) {
        ChannelStatementLineRequest req = new ChannelStatementLineRequest();
        req.setProvider("ALIPAY");
        req.setStatementDate(LocalDate.now());
        req.setChannelTxnId("txn_1");
        req.setEntryType(type);
        req.setAmount(new BigDecimal(amount));
        req.setFee(fee != null ? new BigDecimal(fee) : BigDecimal.ZERO);
        req.setCurrency("USD");
        req.setOrderNumber("ORD-1");
        return req;
    }

    private PaymentLedgerEntry entry(PaymentLedgerEntry.EntryType type, String amount) {
        return PaymentLedgerEntry.builder()
            .id(10L).entryType(type).amount(new BigDecimal(amount)).currency(Currency.USD)
            .provider(PaymentMethod.ALIPAY).channelTxnId("txn_1")
            .debitAccount("D").creditAccount("C").occurredAt(LocalDateTime.now()).build();
    }

    @Test
    void importCharge_matchesLedger() {
        when(statementRepository.findByProviderAndChannelTxnIdAndEntryType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(ledgerRepository.findByChannelTxnIdOrderByOccurredAtDesc("txn_1"))
            .thenReturn(List.of(entry(PaymentLedgerEntry.EntryType.CHARGE, "19.00")));

        ChannelStatementLineView view = service.importLines(List.of(request("CHARGE", "19.00", "0.50"))).get(0);

        assertEquals("MATCHED", view.getMatchStatus());
        assertEquals(10L, view.getMatchedLedgerId());
        verify(paymentService).recordFee(isNull(), eq("ORD-1"), eq(PaymentMethod.ALIPAY),
            eq(new BigDecimal("0.50")), eq(Currency.USD), eq("txn_1"), anyString(), anyString());
    }

    @Test
    void importCharge_amountMismatch_isReported() {
        when(statementRepository.findByProviderAndChannelTxnIdAndEntryType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(ledgerRepository.findByChannelTxnIdOrderByOccurredAtDesc("txn_1"))
            .thenReturn(List.of(entry(PaymentLedgerEntry.EntryType.CHARGE, "18.00")));

        ChannelStatementLineView view = service.importLines(List.of(request("CHARGE", "19.00", null))).get(0);

        assertEquals("AMOUNT_MISMATCH", view.getMatchStatus());
    }

    @Test
    void importCharge_missingLedger_isReported() {
        when(statementRepository.findByProviderAndChannelTxnIdAndEntryType(any(), any(), any()))
            .thenReturn(Optional.empty());
        when(ledgerRepository.findByChannelTxnIdOrderByOccurredAtDesc("txn_1")).thenReturn(List.of());

        ChannelStatementLineView view = service.importLines(List.of(request("CHARGE", "19.00", null))).get(0);

        assertEquals("MISSING_LEDGER", view.getMatchStatus());
    }

    @Test
    void importFee_booksFeeAndMatches() {
        when(statementRepository.findByProviderAndChannelTxnIdAndEntryType(any(), any(), any()))
            .thenReturn(Optional.empty());
        PaymentLedgerEntry feeEntry = entry(PaymentLedgerEntry.EntryType.FEE, "0.50");
        // 第一次查询用于判断是否已有 FEE（返回空 → 补记），第二次查询用于匹配
        when(ledgerRepository.findByChannelTxnIdOrderByOccurredAtDesc("txn_1"))
            .thenReturn(List.of(), List.of(feeEntry));

        ChannelStatementLineView view = service.importLines(List.of(request("FEE", "0.50", null))).get(0);

        assertEquals("MATCHED", view.getMatchStatus());
        verify(paymentService).recordFee(isNull(), eq("ORD-1"), eq(PaymentMethod.ALIPAY),
            eq(new BigDecimal("0.50")), eq(Currency.USD), eq("txn_1"), anyString(), anyString());
    }

    @Test
    void reconcile_returnsStatusCountsAndLedgerWithoutStatement() {
        ChannelStatementLine line = ChannelStatementLine.builder()
            .id(1L).provider(PaymentMethod.ALIPAY).statementDate(LocalDate.now())
            .channelTxnId("txn_1").entryType(PaymentLedgerEntry.EntryType.CHARGE)
            .amount(new BigDecimal("19.00")).fee(BigDecimal.ZERO).currency(Currency.USD)
            .matchStatus("MATCHED").build();
        when(statementRepository.findByProviderAndStatementDateBetweenOrderByStatementDateAscIdAsc(
            any(), any(), any())).thenReturn(List.of(line));
        when(ledgerRepository.findByOccurredAtBetween(any(), any()))
            .thenReturn(List.of(entry(PaymentLedgerEntry.EntryType.CHARGE, "19.00"),
                PaymentLedgerEntry.builder().entryType(PaymentLedgerEntry.EntryType.CHARGE)
                    .provider(PaymentMethod.ALIPAY).channelTxnId("txn_2")
                    .amount(new BigDecimal("5.00")).currency(Currency.USD).build()));

        ChannelReconciliationResponse response = service.reconcile(
            PaymentMethod.ALIPAY, LocalDate.now().minusDays(1), LocalDate.now());

        assertEquals(1L, response.getStatusCounts().get("MATCHED"));
        assertEquals(1, response.getLines().size());
        assertEquals(1, response.getLedgerWithoutStatement().size());
        assertTrue(response.getLedgerWithoutStatement().get(0).contains("txn_2"));
    }

    @Test
    void reverseEntry_delegatesToReversal() {
        PaymentLedgerEntry reversal = entry(PaymentLedgerEntry.EntryType.REVERSAL, "19.00");
        when(paymentService.recordReversal(10L, "fix", "reversal:10")).thenReturn(reversal);

        LedgerEntryView view = service.reverseEntry(10L, "fix");

        assertEquals(PaymentLedgerEntry.EntryType.REVERSAL, view.getEntryType());
        verify(paymentService).recordReversal(10L, "fix", "reversal:10");
    }
}
