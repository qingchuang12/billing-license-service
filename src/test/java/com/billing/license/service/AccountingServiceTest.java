package com.billing.license.service;

import com.billing.license.dto.accounting.*;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AccountingService 账务聚合口径测试。
 *
 * <p>锁住三处最容易改错、且管理台首页直接依赖的口径：
 * ① 金额一律按币种分桶，<b>绝不跨币种求和</b>；
 * ② 「已支付类」（PAID / REFUNDED / REFUND_FAILED）才计入实收，退款额仅取 {@code REFUNDED}；
 * ③ 对账差异的四类疑点判定与交易流水的渠道回退（channel 为空回退 method）。
 */
class AccountingServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 31, 23, 59, 59);

    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private AccountingService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        service = new AccountingService(orderRepository, paymentRepository);
    }

    /** 金额按口径比较（compareTo），避免 scale 差异造成假失败 */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertNotNullish(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
            () -> "期望 " + expected + " 实际 " + actual);
    }

    private static void assertNotNullish(BigDecimal v) {
        assertTrue(v != null, "金额字段不应为 null");
    }

    private static Order order(String amount, Currency currency, Order.OrderStatus status,
                               PaymentMethod provider, LocalDateTime createdAt) {
        return Order.builder()
            .id(UUID.randomUUID())
            .orderNumber("ORD-ACCT-" + status.name() + "-" + amount)
            .totalAmount(new BigDecimal(amount))
            .currency(currency)
            .status(status)
            .paymentStatus(status == Order.OrderStatus.REFUNDED
                ? Order.PaymentStatus.REFUNDED : Order.PaymentStatus.PAID)
            .paymentProvider(provider)
            .createdAt(createdAt)
            .build();
    }

    private static Product product(String sku) {
        return Product.builder().id(UUID.randomUUID()).sku(sku).name(sku).build();
    }

    private static Payment payment(String paymentId, UUID orderId, String amount, Currency currency,
                                   PaymentStatus status, PaymentMethod channel, PaymentMethod legacyMethod,
                                   LocalDateTime createdAt) {
        return Payment.builder()
            .paymentId(paymentId)
            .orderIdStr(orderId.toString())
            .amount(new BigDecimal(amount))
            .currency(currency)
            .status(status)
            .channel(channel)
            .method(legacyMethod)
            .createdAt(createdAt)
            .build();
    }

    // ==================== 收入总览 ====================

    @Test
    void overview_shouldNeverSumAcrossCurrencies() {
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(
            order("100.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM),
            order("50.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.STRIPE, FROM),
            order("30.00", Currency.USD, Order.OrderStatus.REFUNDED, PaymentMethod.STRIPE, FROM),
            order("999.00", Currency.USD, Order.OrderStatus.PENDING, PaymentMethod.STRIPE, FROM),
            order("712.80", Currency.CNY, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM)));

        AccountOverviewResponse resp = service.getOverview(FROM, TO);

        assertEquals(5, resp.getTotalOrderCount(), "订单总数是无量纲指标，可跨币种计数");
        List<AccountCurrencySummary> summaries = resp.getSummaries();
        assertEquals(2, summaries.size(), "必须按币种分成两行，不得合并");
        // 币种名升序：CNY 在前、USD 在后
        assertEquals(Currency.CNY, summaries.get(0).getCurrency());
        AccountCurrencySummary usd = summaries.get(1);
        assertAmount("1179.00", usd.getGmv());          // 含 PENDING，GMV 是下单口径
        assertAmount("180.00", usd.getGrossReceived()); // 不含 CNY 的 712.80，也不含 PENDING
        assertAmount("30.00", usd.getRefunded());
        assertAmount("150.00", usd.getNet());
        assertEquals(4, usd.getOrderCount());
        assertEquals(3, usd.getPaidOrderCount());
        assertAmount("60.00", usd.getAvgOrderValue());  // 180.00 / 3 单已支付
        assertAmount("712.80", summaries.get(0).getGrossReceived());
        assertEquals(0, usd.getGrossReceived().add(summaries.get(0).getGrossReceived())
            .compareTo(new BigDecimal("892.80")), "两币种各自成立，未被强行折成一行");
    }

    @Test
    void overview_refundFailedCountsWithoutBeingRefunded() {
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(
            order("200.00", Currency.USD, Order.OrderStatus.REFUND_FAILED, PaymentMethod.ALIPAY, FROM)));

        AccountOverviewResponse resp = service.getOverview(FROM, TO);

        AccountCurrencySummary usd = resp.getSummaries().get(0);
        assertAmount("200.00", usd.getGrossReceived());
        assertAmount("0", usd.getRefunded());
        assertAmount("200.00", usd.getNet());
    }

    // ==================== 分渠道 ====================

    @Test
    void byChannel_shouldBucketByChannelAndCurrencyAndKeepUnknownChannel() {
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(
            order("10.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM),
            order("5.00", Currency.USD, Order.OrderStatus.PAID, null, FROM),
            order("7.00", Currency.USD, Order.OrderStatus.PENDING, PaymentMethod.STRIPE, FROM)));

        List<AccountChannelSummary> rows = service.getByChannel(FROM, TO);

        assertEquals(3, rows.size(), "渠道 × 币种各一行，未知渠道独立成行");
        assertEquals(PaymentMethod.ALIPAY, rows.get(0).getChannel());
        assertAmount("10.00", rows.get(0).getGrossReceived());
        // 排序键把未知渠道放到最后（ZZ_UNKNOWN）
        AccountChannelSummary unknown = rows.get(rows.size() - 1);
        assertNull(unknown.getChannel());
        assertEquals("未知渠道", unknown.getChannelName());
        assertAmount("5.00", unknown.getGrossReceived());
        // PENDING 计入笔数但不计入实收
        AccountChannelSummary stripe = rows.stream()
            .filter(r -> r.getChannel() == PaymentMethod.STRIPE).findFirst().orElseThrow();
        assertEquals(1, stripe.getOrderCount());
        assertAmount("0", stripe.getGrossReceived());
    }

    // ==================== 分产品 ====================

    @Test
    void byProduct_shouldUseOrderItemAmountsAndSkipUnpaidOrders() {
        Product pro = product("pro-buyout");
        Product plus = product("pro-plus-buyout");
        Order paid = order("100.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM);
        paid.setOrderItems(List.of(
            OrderItem.builder().product(pro).totalPrice(new BigDecimal("60.00")).build(),
            OrderItem.builder().product(plus).totalPrice(new BigDecimal("40.00")).build()));
        Order refunded = order("60.00", Currency.USD, Order.OrderStatus.REFUNDED, PaymentMethod.ALIPAY, FROM);
        refunded.setOrderItems(List.of(
            OrderItem.builder().product(pro).totalPrice(new BigDecimal("60.00")).build()));
        Order pending = order("99.00", Currency.USD, Order.OrderStatus.PENDING, PaymentMethod.STRIPE, FROM);
        pending.setOrderItems(List.of(
            OrderItem.builder().product(pro).totalPrice(new BigDecimal("99.00")).build()));
        // 明细里 product 为 null：应跳过而不是抛异常
        Order dirty = order("1.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.STRIPE, FROM);
        dirty.setOrderItems(List.of(OrderItem.builder().totalPrice(new BigDecimal("1.00")).build()));
        when(orderRepository.findByCreatedAtBetween(FROM, TO))
            .thenReturn(List.of(paid, refunded, pending, dirty));

        List<AccountProductSummary> rows = service.getByProduct(FROM, TO);

        AccountProductSummary proRow = rows.stream()
            .filter(r -> "pro-buyout".equals(r.getSku())).findFirst().orElseThrow();
        assertAmount("120.00", proRow.getGrossReceived());
        assertAmount("60.00", proRow.getRefunded());
        assertAmount("60.00", proRow.getNet());
        assertEquals(2, proRow.getOrderCount(), "PENDING 订单不计入，退款订单计入一次");
        AccountProductSummary plusRow = rows.stream()
            .filter(r -> "pro-plus-buyout".equals(r.getSku())).findFirst().orElseThrow();
        assertAmount("40.00", plusRow.getNet());
        assertEquals(1, rows.stream().filter(r -> "pro-buyout".equals(r.getSku())).count(),
            "同一产品在同一币种只出一行");
    }

    @Test
    void byProduct_shouldSkipOrderWithoutItems() {
        Order noItems = order("10.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM);
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(noItems));

        assertTrue(service.getByProduct(FROM, TO).isEmpty(), "无明细订单不应产出任何产品行");
    }

    // ==================== 交易流水 ====================

    @Test
    void transactions_shouldFallBackToLegacyMethodWhenChannelMissing() {
        UUID orderId = UUID.randomUUID();
        Payment withChannel = payment("pay-1", orderId, "99.00", Currency.USD,
            PaymentStatus.SUCCESS, PaymentMethod.ALIPAY, null, FROM);
        Payment legacyOnly = payment("pay-2", orderId, "99.00", Currency.USD,
            PaymentStatus.REFUNDED, null, PaymentMethod.STRIPE, FROM);
        Page<Payment> stubbed = new PageImpl<>(List.of(withChannel, legacyOnly), PageRequest.of(0, 20), 2);
        when(paymentRepository.findAll(ArgumentMatchers.<Specification<Payment>>any(), any(Pageable.class)))
            .thenReturn(stubbed);

        Page<AccountTransactionView> page = service.getTransactions(
            FROM, TO, PaymentMethod.STRIPE, null, null, PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        AccountTransactionView first = page.getContent().get(0);
        assertEquals(PaymentMethod.ALIPAY, first.getChannel());
        assertEquals("支付宝", first.getChannelName());
        assertEquals("支付成功", first.getStatusDesc());
        AccountTransactionView second = page.getContent().get(1);
        assertEquals(PaymentMethod.STRIPE, second.getChannel(), "channel 为空须回退 method，否则流水里渠道列会空白");
        assertEquals("已退款", second.getStatusDesc());
    }

    // ==================== 时间趋势 ====================

    @Test
    void trend_shouldBucketByMonthAndDay() {
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(
            order("10.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, LocalDateTime.of(2026, 9, 1, 8, 0)),
            order("20.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, LocalDateTime.of(2026, 9, 15, 8, 0)),
            order("30.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, LocalDateTime.of(2026, 10, 1, 8, 0))));

        List<AccountTrendPoint> monthly = service.getTrend(FROM, TO, "MONTH");
        assertEquals(2, monthly.size());
        assertEquals("2026-09", monthly.get(0).getBucket());
        assertAmount("30.00", monthly.get(0).getGrossReceived());
        assertEquals("2026-10", monthly.get(1).getBucket());
        assertAmount("30.00", monthly.get(1).getNet());

        assertEquals(3, service.getTrend(FROM, TO, "day").size(), "小写粒度应归一化为 DAY");
        assertEquals(2, service.getTrend(FROM, TO, null).size(), "粒度缺省为 MONTH");
    }

    @Test
    void trend_shouldRejectUnsupportedGranularity() {
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getTrend(FROM, TO, "WEEK"));
        assertEquals("INVALID_GRANULARITY", ex.getErrorCode());
    }

    // ==================== 对账差异 ====================

    @Test
    void discrepancies_shouldFlagAllFourKinds() {
        Order paidNoPayment = order("10.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM);
        Order paidAmountMismatch = order("20.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM);
        Order refundedNoPayment = order("30.00", Currency.USD, Order.OrderStatus.REFUNDED, PaymentMethod.ALIPAY, FROM);
        Order pendingWithSuccessPayment = order("40.00", Currency.USD, Order.OrderStatus.PENDING, PaymentMethod.ALIPAY, FROM);
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(
            List.of(paidNoPayment, paidAmountMismatch, refundedNoPayment, pendingWithSuccessPayment));
        when(paymentRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(
            payment("pay-ok", paidAmountMismatch.getId(), "15.00", Currency.USD,
                PaymentStatus.SUCCESS, PaymentMethod.ALIPAY, null, FROM),
            payment("pay-orphan", UUID.randomUUID(), "66.00", Currency.USD,
                PaymentStatus.SUCCESS, PaymentMethod.STRIPE, null, FROM),
            payment("pay-pending", pendingWithSuccessPayment.getId(), "40.00", Currency.USD,
                PaymentStatus.SUCCESS, PaymentMethod.ALIPAY, null, FROM)));

        List<String> types = service.getDiscrepancies(FROM, TO).stream().map(AccountDiscrepancy::getType).toList();

        assertTrue(types.contains("PAID_BUT_NO_SUCCESS_PAYMENT"), "已支付却无成功支付：" + types);
        assertTrue(types.contains("AMOUNT_MISMATCH"), "金额不一致：" + types);
        assertTrue(types.contains("REFUNDED_BUT_NO_PAYMENT"), "已退款却无支付记录：" + types);
        assertTrue(types.contains("SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID"), "成功支付找不到已支付订单：" + types);
        // 6 条＝ paidNoPayment 1 + 金额不符 1 + 退款无支付订单兼报两条（PAID_BUT_NO_SUCCESS_PAYMENT 与 REFUNDED_BUT_NO_PAYMENT 重叠）
        //        + 孤儿支付 1 + PENDING 订单带成功支付 1；同一张退款无支付订单会同时命中两类疑点（现有行为，本用例将其钉住）
        assertEquals(6, types.size(), "疑点总数应与上述四类判定逐一对应：" + types);
    }

    @Test
    void discrepancies_shouldStayEmptyWhenOrdersAndPaymentsAgree() {
        Order paid = order("10.00", Currency.USD, Order.OrderStatus.PAID, PaymentMethod.ALIPAY, FROM);
        when(orderRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(paid));
        when(paymentRepository.findByCreatedAtBetween(FROM, TO)).thenReturn(List.of(
            payment("pay-1", paid.getId(), "10.00", Currency.USD, PaymentStatus.SUCCESS,
                PaymentMethod.ALIPAY, null, FROM)));

        assertTrue(service.getDiscrepancies(FROM, TO).isEmpty(), "口径一致时不应误报疑点");
    }
}
