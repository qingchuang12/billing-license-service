package com.billing.license.service;

import com.billing.license.dto.accounting.*;
import com.billing.license.entity.ChannelStatementLine;
import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.ChannelStatementLineRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 渠道账单导入与对账：把渠道后台导出的账单行按 channel_txn_id 与本地复式账本匹配。
 *
 * <p>账单行中的手续费会自动补记一笔 FEE 分录（幂等键 fee:provider:txn），
 * 这样「渠道账单手续费」与本地账本使用同一数据源，避免只在报表里算而不入账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelReconciliationService {

    private final ChannelStatementLineRepository statementRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final PaymentService paymentService;
    private final OrderRepository orderRepository;

    @Transactional
    public List<ChannelStatementLineView> importLines(List<ChannelStatementLineRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException("EMPTY_STATEMENT", "账单行为空");
        }
        List<ChannelStatementLineView> result = new ArrayList<>();
        for (ChannelStatementLineRequest request : requests) {
            result.add(importOne(request));
        }
        return result;
    }

    private ChannelStatementLineView importOne(ChannelStatementLineRequest req) {
        PaymentMethod provider = parseProvider(req.getProvider());
        PaymentLedgerEntry.EntryType type = parseType(req.getEntryType());
        Currency currency = parseCurrency(req.getCurrency());
        if (req.getChannelTxnId() == null || req.getChannelTxnId().isBlank()) {
            throw new BusinessException("STATEMENT_TXN_REQUIRED", "账单行缺少 channelTxnId");
        }
        if (req.getStatementDate() == null) {
            throw new BusinessException("STATEMENT_DATE_REQUIRED", "账单行缺少 statementDate");
        }
        if (req.getAmount() == null || req.getAmount().signum() <= 0) {
            throw new BusinessException("STATEMENT_AMOUNT_INVALID", "账单行金额必须大于 0");
        }
        BigDecimal fee = type == PaymentLedgerEntry.EntryType.FEE
            ? req.getAmount() : (req.getFee() != null ? req.getFee() : BigDecimal.ZERO);
        if (fee.signum() < 0) {
            throw new BusinessException("STATEMENT_FEE_INVALID", "手续费不能为负");
        }

        ChannelStatementLine line = statementRepository
            .findByProviderAndChannelTxnIdAndEntryType(provider, req.getChannelTxnId(), type)
            .orElseGet(ChannelStatementLine::new);
        line.setProvider(provider);
        line.setStatementDate(req.getStatementDate());
        line.setChannelTxnId(req.getChannelTxnId());
        line.setEntryType(type);
        line.setAmount(req.getAmount());
        line.setFee(fee);
        line.setCurrency(currency);
        line.setOrderNumber(req.getOrderNumber());
        line.setRawLine(req.getRawLine());

        // 手机费/退款/手续费的账单行：手续费 > 0 且本地没有 FEE 分录时自动补记
        if (fee.signum() > 0) {
            bookFeeIfMissing(provider, currency, req.getChannelTxnId(), req.getOrderNumber(), fee);
        }

        List<PaymentLedgerEntry> ledgerEntries = ledgerRepository
            .findByChannelTxnIdOrderByOccurredAtDesc(req.getChannelTxnId());
        PaymentLedgerEntry matched = ledgerEntries.stream()
            .filter(e -> e.getEntryType() == type)
            .findFirst()
            .orElse(null);

        if (matched == null) {
            line.setMatchStatus("MISSING_LEDGER");
            line.setMatchedLedgerId(null);
        } else {
            line.setMatchedLedgerId(matched.getId());
            boolean sameAmount = matched.getAmount() != null
                && matched.getAmount().compareTo(req.getAmount()) == 0;
            boolean sameCurrency = matched.getCurrency() == currency;
            line.setMatchStatus(sameAmount && sameCurrency ? "MATCHED" : "AMOUNT_MISMATCH");
        }
        return ChannelStatementLineView.from(statementRepository.save(line));
    }

    private void bookFeeIfMissing(PaymentMethod provider, Currency currency, String channelTxnId,
                                  String orderNumber, BigDecimal fee) {
        boolean exists = ledgerRepository.findByChannelTxnIdOrderByOccurredAtDesc(channelTxnId).stream()
            .anyMatch(e -> e.getEntryType() == PaymentLedgerEntry.EntryType.FEE);
        if (exists) {
            return;
        }
        Order order = orderNumber == null ? null
            : orderRepository.findByOrderNumber(orderNumber).orElse(null);
        paymentService.recordFee(order != null ? order.getId().toString() : null,
            orderNumber, provider, fee, currency, channelTxnId,
            "fee:" + provider + ":" + channelTxnId, "channel-statement:" + channelTxnId);
    }

    @Transactional(readOnly = true)
    public ChannelReconciliationResponse reconcile(PaymentMethod provider, LocalDate from, LocalDate to) {
        if (provider == null || from == null || to == null) {
            throw new BusinessException("INVALID_RECONCILIATION_RANGE", "provider/from/to 不能为空");
        }
        List<ChannelStatementLine> lines = statementRepository
            .findByProviderAndStatementDateBetweenOrderByStatementDateAscIdAsc(provider, from, to);
        Map<String, Long> counts = lines.stream()
            .collect(Collectors.groupingBy(ChannelStatementLine::getMatchStatus, Collectors.counting()));

        Set<String> knownTxns = lines.stream()
            .map(ChannelStatementLine::getChannelTxnId)
            .collect(Collectors.toSet());
        List<PaymentLedgerEntry> ledger = ledgerRepository
            .findByOccurredAtBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<String> ledgerWithoutStatement = ledger.stream()
            .filter(e -> e.getProvider() == provider)
            .filter(e -> e.getChannelTxnId() != null && !e.getChannelTxnId().isBlank())
            .filter(e -> !knownTxns.contains(e.getChannelTxnId()))
            .limit(200)
            .map(e -> e.getChannelTxnId() + "|" + e.getEntryType() + "|"
                + e.getAmount() + " " + e.getCurrency())
            .toList();

        return ChannelReconciliationResponse.builder()
            .provider(provider)
            .from(from)
            .to(to)
            .statusCounts(counts)
            .lines(lines.stream().map(ChannelStatementLineView::from).toList())
            .ledgerWithoutStatement(ledgerWithoutStatement)
            .build();
    }

    @Transactional
    public LedgerEntryView reverseEntry(Long ledgerId, String reason) {
        if (ledgerId == null) {
            throw new BusinessException("LEDGER_ENTRY_NOT_FOUND", "缺少分账 ID");
        }
        return LedgerEntryView.from(paymentService.recordReversal(ledgerId, reason, "reversal:" + ledgerId));
    }

    private static PaymentMethod parseProvider(String value) {
        try {
            return PaymentMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException("INVALID_PROVIDER", "未知支付渠道：" + value);
        }
    }

    private static PaymentLedgerEntry.EntryType parseType(String value) {
        try {
            PaymentLedgerEntry.EntryType type = PaymentLedgerEntry.EntryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (type == PaymentLedgerEntry.EntryType.REVERSAL) {
                throw new BusinessException("INVALID_STATEMENT_TYPE", "账单行不能导入冲正分录，请使用冲正接口");
            }
            return type;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("INVALID_STATEMENT_TYPE", "未知账单分录类型：" + value);
        }
    }

    private static Currency parseCurrency(String value) {
        try {
            return Currency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException("INVALID_CURRENCY", "未知币种：" + value);
        }
    }
}
