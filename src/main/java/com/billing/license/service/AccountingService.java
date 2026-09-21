package com.billing.license.service;

import com.billing.license.dto.accounting.*;
import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
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
 *   <li>已退款：{@code OrderStatus = REFUNDED}（其 paymentStatus 同为 REFUNDED）</li>
 *   <li>净收入 = 实收 − 已退款</li>
 * </ul>
 * <b>多币种</b>：所有金额统计一律按 {@link Currency} 分组，绝对禁止跨币种求和；
 * 跨币种只统计「笔数」等无量纲指标。
 *
 * <p><b>数据源</b>：收入/渠道/产品/趋势以 {@code Order} 为主口径（金额、币种、渠道、产品明细都在订单侧）；
 * 交易流水以 {@code Payment} 为主（每条支付记录 = 一次资金变动）。
 * 注意：当前退款仅变更订单状态、不写 REFUNDED 支付记录，故退款金额只能从订单侧体现。
 *
 * <p><b>聚合方式</b>：低频运营查询，先按时间范围取列表再用 Stream 分组聚合，优先保证口径清晰与可维护；
 * 数据量极大时可改为数据库聚合（届时替换本类内部实现即可，对外契约不变）。
 */
@Service
@RequiredArgsConstructor
public class AccountingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    // ==================== 收入总览 ====================

    @Transactional(readOnly = true)
    public AccountOverviewResponse getOverview(LocalDateTime from, LocalDateTime to) {
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        Map<Currency, MoneyBucket> byCurrency = orders.stream()
                .collect(Collectors.toMap(
                        Order::getCurrency,
                        o -> accumulate(new MoneyBucket(), o),
                        MoneyBucket::merge));

        List<AccountCurrencySummary> summaries = byCurrency.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .map(e -> toSummary(e.getKey(), e.getValue()))
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
                if (o.getStatus() == Order.OrderStatus.REFUNDED) {
                    b.refunded = b.refunded.add(o.getTotalAmount());
                }
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
        List<Order> orders = orderRepository.findByCreatedAtBetween(from, to);
        Map<ProdKey, MoneyBucket> map = new java.util.LinkedHashMap<>();
        Map<ProdKey, Product> meta = new java.util.LinkedHashMap<>();

        for (Order o : orders) {
            if (!isPaidClass(o.getStatus()) || o.getOrderItems() == null) {
                continue;
            }
            boolean refunded = o.getStatus() == Order.OrderStatus.REFUNDED;
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
                if (refunded) {
                    b.refunded = b.refunded.add(item.getTotalPrice());
                }
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
        Specification<Payment> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            // get(String) 返回 Path<Object>，需转为具体类型以满足 between 的 Comparable 约束
            jakarta.persistence.criteria.Path<LocalDateTime> createdAt = root.get("createdAt");
            ps.add(cb.between(createdAt, from, to));
            if (channel != null) {
                // 与 toView 展示口径对齐：展示取 channel，channel 为空时回退 method。
                // 故过滤须匹配 channel = 目标，或（channel 为空且 method = 目标）的历史记录，
                // 否则只有 method、channel 为空的存量支付会被漏掉却仍在列表按该渠道显示。
                ps.add(cb.or(
                        cb.equal(root.get("channel"), channel),
                        cb.and(cb.isNull(root.get("channel")), cb.equal(root.get("method"), channel))));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (currency != null) {
                ps.add(cb.equal(root.get("currency"), currency));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return paymentRepository.findAll(spec, pageable)
                .map(AccountingService::toView);
    }

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
                if (o.getStatus() == Order.OrderStatus.REFUNDED) {
                    b.refunded = b.refunded.add(o.getTotalAmount());
                }
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
        List<Payment> payments = paymentRepository.findByCreatedAtBetween(from, to);

        Map<String, Order> orderById = orders.stream()
                .collect(Collectors.toMap(o -> o.getId().toString(), o -> o, (a, b) -> a));
        Map<String, List<Payment>> paymentsByOrder = payments.stream()
                .collect(Collectors.groupingBy(Payment::getOrderIdStr));

        List<AccountDiscrepancy> result = new ArrayList<>();

        for (Order o : orders) {
            List<Payment> ps = paymentsByOrder.getOrDefault(o.getId().toString(), List.of());
            List<Payment> success = ps.stream()
                    .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                    .toList();

            if (isPaidClass(o.getStatus())) {
                if (success.isEmpty()) {
                    result.add(discrepancy(o, "PAID_BUT_NO_SUCCESS_PAYMENT",
                            "订单为已支付类（" + o.getStatus() + "）但无 SUCCESS 支付记录", ZERO));
                } else {
                    BigDecimal matchedSum = success.stream()
                            .filter(p -> Objects.equals(p.getCurrency(), o.getCurrency()))
                            .map(Payment::getAmount)
                            .reduce(ZERO, BigDecimal::add);
                    long currencyMismatch = success.stream()
                            .filter(p -> !Objects.equals(p.getCurrency(), o.getCurrency()))
                            .count();
                    if (currencyMismatch > 0 || matchedSum.compareTo(o.getTotalAmount()) != 0) {
                        result.add(discrepancy(o, "AMOUNT_MISMATCH",
                                "成功支付金额 " + matchedSum + " " + o.getCurrency()
                                        + (currencyMismatch > 0 ? "（含" + currencyMismatch + "笔币种不一致）" : "")
                                        + " 与订单金额 " + o.getTotalAmount() + " " + o.getCurrency() + " 不一致",
                                matchedSum));
                    }
                }
            }

            if (o.getStatus() == Order.OrderStatus.REFUNDED && success.isEmpty()) {
                result.add(discrepancy(o, "REFUNDED_BUT_NO_PAYMENT",
                        "订单已退款但找不到任何 SUCCESS 支付记录（历史退款可能未留支付明细）", ZERO));
            }
        }

        // 反向：成功支付记录找不到对应已支付类订单
        for (Payment p : payments) {
            if (p.getStatus() != PaymentStatus.SUCCESS) {
                continue;
            }
            Order o = orderById.get(p.getOrderIdStr());
            if (o == null) {
                result.add(AccountDiscrepancy.builder()
                        .orderId(p.getOrderIdStr())
                        .orderNumber(null)
                        .type("SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID")
                        .description("SUCCESS 支付记录找不到对应订单")
                        .orderAmount(null)
                        .orderCurrency(p.getCurrency())
                        .orderStatus(null)
                        .orderPaymentStatus(null)
                        .paidAmount(p.getAmount())
                        .foundAt(p.getCreatedAt())
                        .build());
            } else if (!isPaidClass(o.getStatus())) {
                result.add(discrepancy(o, "SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID",
                        "存在 SUCCESS 支付记录，但订单状态为 " + o.getStatus() + "（非已支付类）", p.getAmount()));
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
            if (o.getStatus() == Order.OrderStatus.REFUNDED) {
                b.refunded = b.refunded.add(o.getTotalAmount());
            }
        }
        return b;
    }

    private AccountCurrencySummary toSummary(Currency currency, MoneyBucket b) {
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
                .build();
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

    private static AccountTransactionView toView(Payment p) {
        PaymentMethod ch = p.getChannel() != null ? p.getChannel() : p.getMethod();
        return AccountTransactionView.builder()
                .paymentId(p.getPaymentId())
                .orderId(p.getOrderIdStr())
                .transactionId(p.getTransactionId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .channel(ch)
                .channelName(ch != null ? ch.getName() : "未知渠道")
                .status(p.getStatus())
                .statusDesc(p.getStatus() != null ? p.getStatus().getDescription() : null)
                .createdAt(p.getCreatedAt())
                .paidAt(p.getPaidAt())
                .build();
    }

    /** 币种级金额累加桶（GMV / 实收 / 已退款 / 笔数） */
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
