package com.billing.license.service;

import com.billing.license.dto.accounting.*;
import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 平台账务聚合服务（仅查询，不改写数据）。
 *
 * <p><b>金额口径（全局固化，所有方法一致）</b>：
 * <ul>
 *   <li>已支付类（计入实收）：{@code OrderStatus ∈ {PAID, REFUNDED, REFUND_FAILED}}</li>
 *   <li>已退款：取订单 metadata 的 {@code refundAmount}（全/部分退款都按**实际退还金额**扣减）；
 *       历史已退款单无该字段时按订单总额退</li>
 *   <li>净收入 = 实收 − 已退款</li>
 * </ul>
 * <b>多币种</b>：所有金额统计一律按 {@link Currency} 分组，绝对禁止跨币种求和；
 * 跨币种只统计「笔数」等无量纲指标。
 *
 * <p><b>数据源</b>：收入/渠道/产品/趋势以 {@code Order} 为主口径（金额、币种、渠道、产品明细都在订单侧）；
 * 交易流水以 {@code Payment} 为主（每条支付记录 = 一次资金变动）。
 * 管理端/渠道退款成功后会写入一条 REFUNDED 支付记录，故退款在交易流水中可见；
 * 收入/渠道/产品/趋势的「已退款」金额取订单侧实际退款额（metadata.refundAmount），
 * 不重复累加 Payment 退款行。
 *
 * <p><b>聚合方式</b>：低频运营查询，先按时间范围取列表再用 Stream 分组聚合，优先保证口径清晰与可维护；
 * 数据量极大时可改为数据库聚合（届时替换本类内部实现即可，对外契约不变）。
 */
@Service
@RequiredArgsConstructor
public class AccountingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final OrderRepository orderRepository;
    private final PaymentLedgerRepository paymentLedgerRepository;

    // ==================== 收入总览 ====================

    @Transactional(readOnly = true)
    public AccountOverviewResponse getOverview(LocalDateTime from, LocalDateTime to) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        Map<Currency, MoneyBucket> byCurrency = orders.stream()
                .collect(Collectors.toMap(
                        Order::getCurrency,
                        o -> accumulate(new MoneyBucket(), o),
                        MoneyBucket::merge));

        Map<Currency, LedgerBucket> ledgerByCurrency = ledgerBuckets(from, to);
        java.util.Set<Currency> currencies = new java.util.TreeSet<>(Comparator.comparing(Enum::name));
        currencies.addAll(byCurrency.keySet());
        currencies.addAll(ledgerByCurrency.keySet());
        List<AccountCurrencySummary> summaries = currencies.stream()
                .map(currency -> toSummary(currency,
                        byCurrency.getOrDefault(currency, new MoneyBucket()),
                        ledgerByCurrency.getOrDefault(currency, new LedgerBucket())))
                .toList();

        return AccountOverviewResponse.builder()
                .from(from)
                .to(to)
                .totalOrderCount(orders.size())
                .summaries(summaries)
                .build();
    }

    // ==================== 分渠道 ====================

    @Transactional(readOnly = true)
    public List<AccountChannelSummary> getByChannel(LocalDateTime from, LocalDateTime to) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        Map<ChanKey, MoneyBucket> map = new java.util.LinkedHashMap<>();

        for (Order o : orders) {
            PaymentMethod ch = o.getPaymentProvider();
            ChanKey key = new ChanKey(ch, o.getCurrency());
            MoneyBucket b = map.computeIfAbsent(key, k -> new MoneyBucket());
            b.orderCount++;                       // 渠道笔数含全部状态
            if (isPaidClass(o.getStatus())) {
                b.received = b.received.add(o.getTotalAmount());
                b.paidCount++;
                b.refunded = b.refunded.add(refundedAmountOf(o));
            }
        }

        return map.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().bucketSortKey()))
                .map(e -> {
                    ChanKey k = e.getKey();
                    MoneyBucket b = e.getValue();
                    return AccountChannelSummary.builder()
                            .channel(k.channel())
                            .channelName(k.channel() != null ? k.channel().getName() : "未知渠道")
                            .currency(k.currency())
                            .grossReceived(b.received)
                            .refunded(b.refunded)
                            .net(b.received.subtract(b.refunded))
                            .orderCount(b.orderCount)
                            .build();
                })
                .toList();
    }

    // ==================== 分产品 ====================

    @Transactional(readOnly = true)
    public List<AccountProductSummary> getByProduct(LocalDateTime from, LocalDateTime to) {
        // 本方法要读行项与产品，必须预加载：否则每张订单一次行项查询、每个行项再一次产品查询
        List<Order> orders = orderRepository.findByCreatedAtBetweenWithItems(from, to);
        Map<ProdKey, MoneyBucket> map = new java.util.LinkedHashMap<>();
        Map<ProdKey, Product> meta = new java.util.LinkedHashMap<>();

        for (Order o : orders) {
            if (!isPaidClass(o.getStatus()) || o.getOrderItems() == null) {
                continue;
            }
            BigDecimal orderRefund = refundedAmountOf(o);
            for (var item : o.getOrderItems()) {
                Product p = item.getProduct();
                if (p == null) {
                    continue;
                }
                ProdKey key = new ProdKey(p.getId(), o.getCurrency());
                MoneyBucket b = map.computeIfAbsent(key, k -> new MoneyBucket());
                b.orderCount++;                  // 每张订单对该产品计一次
                b.received = b.received.add(item.getTotalPrice());
                b.paidCount++;
                b.refunded = b.refunded.add(allocateRefund(o, item.getTotalPrice(), orderRefund));
                meta.putIfAbsent(key, p);
            }
        }

        return map.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().bucketSortKey()))
                .map(e -> {
                    ProdKey k = e.getKey();
                    MoneyBucket b = e.getValue();
                    Product p = meta.get(k);
                    return AccountProductSummary.builder()
                            .productId(k.productId())
                            .sku(p != null ? p.getSku() : null)
                            .name(p != null ? p.getName() : null)
                            .tier(p != null ? p.getTier() : null)
                            .billingCycle(p != null ? p.getBillingCycle() : null)
                            .currency(k.currency())
                            .grossReceived(b.received)
                            .refunded(b.refunded)
                            .net(b.received.subtract(b.refunded))
                            .orderCount(b.orderCount)
                            .build();
                })
                .toList();
    }

    // ==================== 交易流水明细 ====================

    @Transactional(readOnly = true)
    public Page<AccountTransactionView> getTransactions(LocalDateTime from, LocalDateTime to,
                                                       PaymentMethod channel, PaymentStatus status,
                                                       Currency currency, Pageable pageable) {
        // 交易流水以不可变资金账为准：SUCCESS=收款分录，REFUNDED=退款分录
        PaymentLedgerEntry.EntryType type = null;
        if (status == PaymentStatus.SUCCESS) {
            type = PaymentLedgerEntry.EntryType.CHARGE;
        } else if (status == PaymentStatus.REFUNDED) {
            type = PaymentLedgerEntry.EntryType.REFUND;
        } else if (status != null) {
            return Page.empty(pageable);
        }
        return paymentLedgerRepository.search(from, to, channel, type, currency, pageable)
                .map(AccountingService::toView);
    }

    // ==================== 复式分账 / 手续费 ====================

    /** 复式分账明细：每行含借贷科目，供财务核对与冲正定位。 */
    @Transactional(readOnly = true)
    public Page<LedgerEntryView> getLedger(LocalDateTime from, LocalDateTime to,
                                           PaymentMethod channel, PaymentLedgerEntry.EntryType type,
                                           Currency currency, Pageable pageable) {
        return paymentLedgerRepository.search(from, to, channel, type, currency, pageable)
                .map(LedgerEntryView::from);
    }

    /** 手续费汇总（仅 FEE 分录），按渠道 × 币种分组。 */
    @Transactional(readOnly = true)
    public List<FeeSummaryView> getFeeSummary(LocalDateTime from, LocalDateTime to) {
        Map<FeeKey, MoneyBucket> map = new LinkedHashMap<>();
        for (PaymentLedgerEntry e : paymentLedgerRepository.findByOccurredAtBetween(from, to)) {
            if (e.getEntryType() != PaymentLedgerEntry.EntryType.FEE) {
                continue;
            }
            FeeKey key = new FeeKey(e.getProvider(), e.getCurrency());
            MoneyBucket b = map.computeIfAbsent(key, k -> new MoneyBucket());
            b.received = b.received.add(e.getAmount() != null ? e.getAmount() : ZERO);
            b.paidCount++;
        }
        return map.entrySet().stream()
                .sorted(Comparator.comparing(e -> String.valueOf(e.getKey().provider())
                        + "|" + String.valueOf(e.getKey().currency())))
                .map(e -> FeeSummaryView.builder()
                        .provider(e.getKey().provider())
                        .currency(e.getKey().currency())
                        .feeAmount(e.getValue().received)
                        .entryCount(e.getValue().paidCount)
                        .build())
                .toList();
    }

    private record FeeKey(PaymentMethod provider, Currency currency) {}

    // ==================== 时间趋势 ====================

    @Transactional(readOnly = true)
    public List<AccountTrendPoint> getTrend(LocalDateTime from, LocalDateTime to, String granularity) {
        String g = Optional.ofNullable(granularity).orElse("MONTH").toUpperCase();
        if (!g.equals("DAY") && !g.equals("MONTH")) {
            throw new BusinessException("INVALID_GRANULARITY", "粒度仅支持 DAY 或 MONTH，收到：" + granularity);
        }
        DateTimeFormatter fmt = g.equals("DAY") ? DAY_FMT : MONTH_FMT;

        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        Map<TrendKey, MoneyBucket> map = new java.util.LinkedHashMap<>();

        for (Order o : orders) {
            String bucket = o.getCreatedAt() != null ? o.getCreatedAt().format(fmt) : "unknown";
            TrendKey key = new TrendKey(bucket, o.getCurrency());
            MoneyBucket b = map.computeIfAbsent(key, k -> new MoneyBucket());
            b.orderCount++;
            if (isPaidClass(o.getStatus())) {
                b.received = b.received.add(o.getTotalAmount());
                b.paidCount++;
                b.refunded = b.refunded.add(refundedAmountOf(o));
            }
        }

        return map.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().bucket() + "|" + e.getKey().currency().name()))
                .map(e -> {
                    TrendKey k = e.getKey();
                    MoneyBucket b = e.getValue();
                    return AccountTrendPoint.builder()
                            .bucket(k.bucket())
                            .granularity(g)
                            .currency(k.currency())
                            .grossReceived(b.received)
                            .refunded(b.refunded)
                            .net(b.received.subtract(b.refunded))
                            .orderCount(b.orderCount)
                            .build();
                })
                .toList();
    }

    // ==================== 对账差异 ====================

    @Transactional(readOnly = true)
    public List<AccountDiscrepancy> getDiscrepancies(LocalDateTime from, LocalDateTime to) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        List<PaymentLedgerEntry> entries = paymentLedgerRepository.findByOccurredAtBetween(from, to);

        Map<String, Order> orderById = orders.stream()
                .collect(Collectors.toMap(o -> o.getId().toString(), o -> o, (a, b) -> a));
        Map<String, List<PaymentLedgerEntry>> entriesByOrder = entries.stream()
                .collect(Collectors.groupingBy(PaymentLedgerEntry::getOrderIdStr));

        List<AccountDiscrepancy> result = new ArrayList<>();

        for (Order o : orders) {
            List<PaymentLedgerEntry> ps = entriesByOrder.getOrDefault(o.getId().toString(), List.of());
            List<PaymentLedgerEntry> charges = ps.stream()
                    .filter(e -> e.getEntryType() == PaymentLedgerEntry.EntryType.CHARGE)
                    .toList();

            if (isPaidClass(o.getStatus())) {
                if (charges.isEmpty()) {
                    result.add(discrepancy(o, "PAID_BUT_NO_SUCCESS_PAYMENT",
                            "订单为已支付类（" + o.getStatus() + "）但无可核对收款分录", ZERO));
                } else {
                    BigDecimal matchedSum = charges.stream()
                            .filter(e -> Objects.equals(e.getCurrency(), o.getCurrency()))
                            .map(PaymentLedgerEntry::getAmount)
                            .reduce(ZERO, BigDecimal::add);
                    long currencyMismatch = charges.stream()
                            .filter(e -> !Objects.equals(e.getCurrency(), o.getCurrency()))
                            .count();
                    if (currencyMismatch > 0 || matchedSum.compareTo(o.getTotalAmount()) != 0) {
                        result.add(discrepancy(o, "AMOUNT_MISMATCH",
                                "收款分录合计 " + matchedSum + " " + o.getCurrency()
                                        + (currencyMismatch > 0 ? "（含" + currencyMismatch + "笔币种不一致）" : "")
                                        + " 与订单金额 " + o.getTotalAmount() + " " + o.getCurrency() + " 不一致",
                                matchedSum));
                    }
                }
            }

            if (o.getStatus() == Order.OrderStatus.REFUNDED && charges.isEmpty()) {
                result.add(discrepancy(o, "REFUNDED_BUT_NO_PAYMENT",
                        "订单已退款但找不到任何收款分录", ZERO));
            }
        }

        // 反向：收款分录找不到对应已支付类订单
        for (PaymentLedgerEntry e : entries) {
            if (e.getEntryType() != PaymentLedgerEntry.EntryType.CHARGE) {
                continue;
            }
            Order o = orderById.get(e.getOrderIdStr());
            if (o == null) {
                result.add(AccountDiscrepancy.builder()
                        .orderId(e.getOrderIdStr())
                        .orderNumber(e.getOrderNumber())
                        .type("SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID")
                        .description("收款分录找不到对应订单")
                        .orderAmount(null)
                        .orderCurrency(e.getCurrency())
                        .orderStatus(null)
                        .orderPaymentStatus(null)
                        .paidAmount(e.getAmount())
                        .foundAt(e.getOccurredAt())
                        .build());
            } else if (!isPaidClass(o.getStatus())) {
                result.add(discrepancy(o, "SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID",
                        "存在收款分录，但订单状态为 " + o.getStatus() + "（非已支付类）", e.getAmount()));
            }
        }

        result.sort(Comparator.comparing(
                d -> d.getFoundAt() != null ? d.getFoundAt() : LocalDateTime.MIN, Comparator.reverseOrder()));
        return result;
    }

    // ==================== 内部工具 ====================

    /** 已支付类（计入实收）：PAID / REFUNDED / REFUND_FAILED */
    private boolean isPaidClass(Order.OrderStatus s) {
        return s == Order.OrderStatus.PAID
                || s == Order.OrderStatus.REFUNDED
                || s == Order.OrderStatus.REFUND_FAILED;
    }

    /** 把订单累加进一个币种桶（用于总览，含 GMV / 实收 / 已退款） */
    private MoneyBucket accumulate(MoneyBucket b, Order o) {
        b.gmv = b.gmv.add(o.getTotalAmount());
        b.orderCount++;
        if (isPaidClass(o.getStatus())) {
            b.received = b.received.add(o.getTotalAmount());
            b.paidCount++;
            b.refunded = b.refunded.add(refundedAmountOf(o));
        }
        return b;
    }

    /**
     * 订单实际已退金额：优先 metadata.refundAmount（人工/渠道退款都会写），
     * 历史全额退款单无该字段时按订单总额；部分退款单缺字段时按 0（宁少扣不虚增）。
     */
    private static BigDecimal refundedAmountOf(Order o) {
        BigDecimal total = o.getTotalAmount();
        BigDecimal recorded = null;
        String metadata = o.getMetadata();
        if (metadata != null && !metadata.isBlank()) {
            try {
                var node = METADATA_MAPPER.readTree(metadata);
                if (node.hasNonNull("refundAmount")) {
                    recorded = new BigDecimal(node.get("refundAmount").asText());
                }
            } catch (Exception ignored) {
                // 脏 metadata 不阻断账务；按状态兜底
            }
        }
        if (recorded != null && recorded.signum() > 0) {
            return total != null && recorded.compareTo(total) > 0 ? total : recorded;
        }
        if (o.getStatus() == Order.OrderStatus.REFUNDED) {
            return total != null ? total : ZERO;
        }
        return ZERO;
    }

    /** 多行订单按明细金额比例分摊退款额（单行订单即全额）。 */
    private static BigDecimal allocateRefund(Order o, BigDecimal itemTotal, BigDecimal orderRefund) {
        if (orderRefund == null || orderRefund.signum() <= 0) {
            return ZERO;
        }
        BigDecimal total = o.getTotalAmount();
        if (total == null || total.signum() <= 0) {
            return ZERO;
        }
        if (itemTotal.compareTo(total) == 0) {
            return orderRefund;
        }
        return orderRefund.multiply(itemTotal).divide(total, 2, RoundingMode.HALF_UP);
    }

    private AccountCurrencySummary toSummary(Currency currency, MoneyBucket b, LedgerBucket l) {
        BigDecimal net = b.received.subtract(b.refunded);
        BigDecimal aov = b.paidCount > 0
                ? b.received.divide(BigDecimal.valueOf(b.paidCount), 2, RoundingMode.HALF_UP)
                : ZERO;
        return AccountCurrencySummary.builder()
                .currency(currency)
                .gmv(b.gmv)
                .grossReceived(b.received)
                .refunded(b.refunded)
                .net(net)
                .orderCount(b.orderCount)
                .paidOrderCount(b.paidCount)
                .avgOrderValue(aov)
                .ledgerGrossReceived(l.gross)
                .ledgerRefunded(l.refunded)
                .ledgerFee(l.fee)
                .ledgerNet(l.gross.subtract(l.refunded).subtract(l.fee))
                .build();
    }

    private Map<Currency, LedgerBucket> ledgerBuckets(LocalDateTime from, LocalDateTime to) {
        Map<Currency, LedgerBucket> result = new java.util.LinkedHashMap<>();
        for (PaymentLedgerEntry e : paymentLedgerRepository.findByOccurredAtBetween(from, to)) {
            if (e.getCurrency() == null) {
                continue;
            }
            LedgerBucket b = result.computeIfAbsent(e.getCurrency(), k -> new LedgerBucket());
            BigDecimal amount = e.getAmount() != null ? e.getAmount() : ZERO;
            switch (e.getEntryType()) {
                case CHARGE -> b.gross = b.gross.add(amount);
                case REFUND -> b.refunded = b.refunded.add(amount);
                case FEE -> b.fee = b.fee.add(amount);
                case REVERSAL -> {
                    String debit = e.getDebitAccount();
                    if ("REVENUE".equals(debit)) {
                        b.gross = b.gross.subtract(amount);
                    } else if ("FEE_EXPENSE".equals(debit)) {
                        b.fee = b.fee.subtract(amount);
                    } else if ("CHANNEL_CLEARING".equals(debit)) {
                        b.refunded = b.refunded.subtract(amount);
                    }
                }
            }
        }
        return result;
    }

    private AccountDiscrepancy discrepancy(Order o, String type, String desc, BigDecimal paidAmount) {
        return AccountDiscrepancy.builder()
                .orderNumber(o.getOrderNumber())
                .orderId(o.getId().toString())
                .type(type)
                .description(desc)
                .orderAmount(o.getTotalAmount())
                .orderCurrency(o.getCurrency())
                .orderStatus(o.getStatus().name())
                .orderPaymentStatus(o.getPaymentStatus().name())
                .paidAmount(paidAmount)
                .foundAt(o.getCreatedAt())
                .build();
    }

    private static AccountTransactionView toView(PaymentLedgerEntry e) {
        PaymentMethod ch = e.getProvider();
        PaymentStatus status = e.getEntryType() == PaymentLedgerEntry.EntryType.CHARGE
            ? PaymentStatus.SUCCESS : PaymentStatus.REFUNDED;
        return AccountTransactionView.builder()
                .paymentId(e.getIdempotencyKey())
                .orderId(e.getOrderIdStr())
                .transactionId(e.getChannelTxnId())
                .amount(e.getAmount())
                .currency(e.getCurrency())
                .channel(ch)
                .channelName(ch != null ? ch.getName() : "未知渠道")
                .status(status)
                .statusDesc(status.getDescription())
                .createdAt(e.getOccurredAt())
                .paidAt(e.getOccurredAt())
                .build();
    }

    /** 币种级金额累加桶（GMV / 实收 / 已退款 / 笔数） */
    private static final class LedgerBucket {
        BigDecimal gross = ZERO;
        BigDecimal refunded = ZERO;
        BigDecimal fee = ZERO;
    }

    private static final class MoneyBucket {
        BigDecimal gmv = ZERO;
        BigDecimal received = ZERO;
        BigDecimal refunded = ZERO;
        long orderCount = 0;
        long paidCount = 0;

        MoneyBucket merge(MoneyBucket other) {
            this.gmv = this.gmv.add(other.gmv);
            this.received = this.received.add(other.received);
            this.refunded = this.refunded.add(other.refunded);
            this.orderCount += other.orderCount;
            this.paidCount += other.paidCount;
            return this;
        }
    }

    private record ChanKey(PaymentMethod channel, Currency currency) {
        String bucketSortKey() {
            return (channel != null ? channel.name() : "ZZ_UNKNOWN") + "|" + currency.name();
        }
    }

    private record ProdKey(java.util.UUID productId, Currency currency) {
        String bucketSortKey() {
            return (productId != null ? productId.toString() : "ZZ") + "|" + currency.name();
        }
    }

    private record TrendKey(String bucket, Currency currency) {
    }
}
