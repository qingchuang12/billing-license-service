package com.billing.license.controller;

import com.billing.license.dto.accounting.AccountOverviewResponse;
import com.billing.license.entity.Currency;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.AccountingService;
import com.billing.license.service.AccountingServiceTest;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AccountingController 参数护栏测试（只读端点的入口校验，聚合口径由 {@link AccountingServiceTest} 锁）。
 *
 * <p>锁住四件事：① from/to 缺省补「最近 30 天」；② 时间区间与跨度的两条 400；
 * ③ 渠道 / 支付状态 / 币种三个枚举入参非法时报各自错误码（未知币种由 {@code Currency.fromCode} 兜住）；
 * ④ 分页 size 封顶 200、下限 1，page 不允许为负。
 */
class AccountingControllerTest {

    private AccountingService accountingService;
    private AccountingController controller;

    @BeforeEach
    void setUp() {
        accountingService = mock(AccountingService.class);
        controller = new AccountingController(accountingService);
    }

    private static AccountOverviewResponse emptyOverview() {
        return AccountOverviewResponse.builder()
            .from(LocalDateTime.MIN).to(LocalDateTime.MAX).totalOrderCount(0)
            .summaries(java.util.List.of()).build();
    }

    // ==================== 时间区间护栏 ====================

    @Test
    void overview_shouldDefaultToLastThirtyDays() {
        when(accountingService.getOverview(any(), any())).thenReturn(emptyOverview());

        controller.overview(null, null);

        ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(accountingService).getOverview(fromCap.capture(), toCap.capture());
        LocalDateTime from = fromCap.getValue();
        LocalDateTime to = toCap.getValue();
        assertEquals(30, Duration.between(from, to).toDays(), "双缺省应恰好覆盖最近 30 天");
    }

    @Test
    void overview_shouldOnlyFillMissingSide() {
        when(accountingService.getOverview(any(), any())).thenReturn(emptyOverview());
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);

        controller.overview(from, null);

        verify(accountingService).getOverview(eq(from), any());
    }

    @Test
    void overview_shouldRejectReversedRange() {
        BusinessException ex = assertThrows(BusinessException.class, () -> controller.overview(
            LocalDateTime.of(2026, 9, 20, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0)));
        assertEquals("INVALID_TIME_RANGE", ex.getErrorCode());
    }

    @Test
    void overview_shouldRejectRangeWiderThan366Days() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> controller.overview(from, from.plusDays(367)));
        assertEquals("TIME_RANGE_TOO_LARGE", ex.getErrorCode());

        // 边界：恰好 366 天必须放行（含闰年）
        when(accountingService.getOverview(any(), any())).thenReturn(emptyOverview());
        controller.overview(from, from.plusDays(366));
        verify(accountingService).getOverview(from, from.plusDays(366));
    }

    // ==================== 枚举入参护栏 ====================

    @Test
    void transactions_shouldRejectUnknownChannel() {
        when(accountingService.getTransactions(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());
        LocalDateTime now = LocalDateTime.now();

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.transactions(
            now.minusDays(1), now, "BOGUS_PAY", null, null, 0, 20));
        assertEquals("INVALID_CHANNEL", ex.getErrorCode());
    }

    @Test
    void transactions_shouldRejectUnknownStatus() {
        LocalDateTime now = LocalDateTime.now();

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.transactions(
            now.minusDays(1), now, null, "NOT_A_STATUS", null, 0, 20));
        assertEquals("INVALID_PAYMENT_STATUS", ex.getErrorCode());
    }

    @Test
    void transactions_shouldRejectUnsupportedCurrency() {
        LocalDateTime now = LocalDateTime.now();

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.transactions(
            now.minusDays(1), now, null, null, "XYZ", 0, 20));
        assertEquals("UNSUPPORTED_CURRENCY", ex.getErrorCode());
    }

    @Test
    void transactions_shouldNormalizeChannelStatusCurrency() {
        LocalDateTime now = LocalDateTime.now();
        when(accountingService.getTransactions(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        controller.transactions(now.minusDays(1), now, " alipay ", " refunded ", " usd ", 0, 20);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(accountingService).getTransactions(eq(now.minusDays(1)), eq(now),
            eq(PaymentMethod.ALIPAY), eq(PaymentStatus.REFUNDED), eq(Currency.USD), cap.capture());
        assertEquals(0, cap.getValue().getPageNumber());
        assertEquals(20, cap.getValue().getPageSize());
        Sort.Order order = cap.getValue().getSort().getOrderFor("createdAt");
        assertNotNull(order, "流水必须按 createdAt 排序");
        assertEquals(Sort.Direction.DESC, order.getDirection(), "默认创建时间倒序");
    }

    // ==================== 分页护栏 ====================

    @Test
    void transactions_shouldClampPageSizeToUpperBound() {
        LocalDateTime now = LocalDateTime.now();
        when(accountingService.getTransactions(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        controller.transactions(now.minusDays(1), now, null, null, null, -5, 5000);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(accountingService).getTransactions(any(), any(), any(), any(), any(), cap.capture());
        assertEquals(200, cap.getValue().getPageSize(), "size 必须封顶 200，防一次拉全量进内存");
        assertEquals(0, cap.getValue().getPageNumber(), "page 不允许为负");
    }

    @Test
    void transactions_shouldRaiseZeroSizeToOne() {
        LocalDateTime now = LocalDateTime.now();
        when(accountingService.getTransactions(any(), any(), any(), any(), any(), any()))
            .thenReturn(Page.empty());

        controller.transactions(now.minusDays(1), now, null, null, null, 0, 0);

        ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
        verify(accountingService).getTransactions(any(), any(), any(), any(), any(), cap.capture());
        assertEquals(1, cap.getValue().getPageSize());
    }

    // ==================== 其余端点的口径透传 ====================

    @Test
    void trend_shouldPassGranularityThroughAndApplyDefaultRange() {
        LocalDateTime now = LocalDateTime.now();
        when(accountingService.getTrend(any(), any(), any())).thenReturn(java.util.List.of());

        controller.trend(now.minusDays(3), now, "DAY");

        verify(accountingService).getTrend(now.minusDays(3), now, "DAY");
    }

    @Test
    void discrepancies_shouldReuseTheSameRangeGuard() {
        assertThrows(BusinessException.class, () -> controller.discrepancies(
            LocalDateTime.of(2026, 9, 20, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0)));
    }
}
