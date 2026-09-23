package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.accounting.*;
import com.billing.license.entity.Currency;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.AccountingService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台账务管理查看接口（仅查询，不写数据）。
 *
 * <p>挂载于 {@code /api/admin/accounting/**}，由 {@code SecurityConfig} 统一要求 ROLE_ADMIN（管理员 JWT）；
 * 所有端点声明式 {@link Audit} 审计（只读视图，target 统一为 "-")。
 *
 * <p>金额口径与多币种分组规则见 {@link AccountingService} 类注释（全局唯一出处）。
 */
@Tag(name = "平台账务管理",
        description = "收入总览/分渠道/分产品/交易流水/时间趋势/对账差异（仅查询，需管理员 JWT 且具 ROLE_ADMIN）")
@RestController
@RequestMapping("/api/admin/accounting")
@RequiredArgsConstructor
public class AccountingController {

    private static final int MAX_PAGE_SIZE = 200;

    /** 单次查询最大时间跨度（天）。账务为低频运营查询且聚合在内存完成，
     *  跨度过大会一次性拉全量记录进内存，故封顶一年（含闰年，366 天）。 */
    private static final long MAX_RANGE_DAYS = 366;

    private final AccountingService accountingService;

    @Operation(summary = "收入总览",
            description = "区间内的 GMV / 实收 / 已退款 / 净收入 / 订单数 / 客单价，按币种分组返回（金额不跨币种求和）。"
                    + "from/to 不传时默认最近 30 天。")
    @Audit(action = "VIEW_ACCOUNTING_OVERVIEW", target = "-")
    @GetMapping("/overview")
    public ResponseEntity<AccountOverviewResponse> overview(
            @Parameter(description = "起始时间（ISO-8601，如 2026-09-01T00:00:00）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间（ISO-8601，如 2026-09-20T23:59:59）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDateTime[] range = normalizeRange(from, to);
        return ResponseEntity.ok(accountingService.getOverview(range[0], range[1]));
    }

    @Operation(summary = "分渠道统计",
            description = "按支付渠道 × 币种返回实收 / 已退款 / 净收入 / 订单数。provider 为 null 的历史订单归入「未知渠道」。")
    @Audit(action = "VIEW_ACCOUNTING_BY_CHANNEL", target = "-")
    @GetMapping("/by-channel")
    public ResponseEntity<List<AccountChannelSummary>> byChannel(
            @Parameter(description = "起始时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDateTime[] range = normalizeRange(from, to);
        return ResponseEntity.ok(accountingService.getByChannel(range[0], range[1]));
    }

    @Operation(summary = "分产品统计",
            description = "按产品 × 币种返回实收 / 已退款 / 净收入 / 订单数；金额取自订单明细，按订单级支付状态归类。")
    @Audit(action = "VIEW_ACCOUNTING_BY_PRODUCT", target = "-")
    @GetMapping("/by-product")
    public ResponseEntity<List<AccountProductSummary>> byProduct(
            @Parameter(description = "起始时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDateTime[] range = normalizeRange(from, to);
        return ResponseEntity.ok(accountingService.getByProduct(range[0], range[1]));
    }

    @Operation(summary = "交易流水明细",
            description = "每条支付记录一行（一次资金变动）。支持按渠道 / 状态 / 币种 / 时间过滤，带分页。"
                    + "管理端退款成功后会写入一条 REFUNDED 支付记录，故退款也在此流水体现（status=REFUNDED）。")
    @Audit(action = "VIEW_ACCOUNTING_TRANSACTIONS", target = "-")
    @GetMapping("/transactions")
    public ResponseEntity<Page<AccountTransactionView>> transactions(
            @Parameter(description = "起始时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "渠道过滤：ALIPAY/WECHAT_PAY/STRIPE/PADDLE/PAYPAL")
            @RequestParam(required = false) String channel,
            @Parameter(description = "状态过滤：SUCCESS/REFUNDED/FAILED/PENDING/CANCELLED/UNKNOWN")
            @RequestParam(required = false) String status,
            @Parameter(description = "币种过滤（ISO 4217，如 USD/CNY）")
            @RequestParam(required = false) String currency,
            @Parameter(description = "页码（从 0 开始）")
            @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "每页大小（1~200，默认 20）")
            @RequestParam(required = false, defaultValue = "20") int size) {
        LocalDateTime[] range = normalizeRange(from, to);
        PaymentMethod ch = parseChannel(channel);
        PaymentStatus st = parseStatus(status);
        Currency cur = parseCurrency(currency);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(Math.max(0, page), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(accountingService.getTransactions(range[0], range[1], ch, st, cur, pageable));
    }

    @Operation(summary = "收入时间趋势",
            description = "按 DAY（yyyy-MM-dd）或 MONTH（yyyy-MM）分桶，再按币种返回实收 / 已退款 / 净收入 / 订单数。")
    @Audit(action = "VIEW_ACCOUNTING_TREND", target = "-")
    @GetMapping("/trend")
    public ResponseEntity<List<AccountTrendPoint>> trend(
            @Parameter(description = "起始时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "分桶粒度：DAY / MONTH（默认 MONTH）")
            @RequestParam(required = false, defaultValue = "MONTH") String granularity) {
        LocalDateTime[] range = normalizeRange(from, to);
        return ResponseEntity.ok(accountingService.getTrend(range[0], range[1], granularity));
    }

    @Operation(summary = "对账差异",
            description = "订单与支付记录状态/金额不一致的疑点：已支付无成功支付、金额不一致、已退款无支付记录、"
                    + "成功支付但订单未标记已支付。")
    @Audit(action = "VIEW_ACCOUNTING_DISCREPANCIES", target = "-")
    @GetMapping("/discrepancies")
    public ResponseEntity<List<AccountDiscrepancy>> discrepancies(
            @Parameter(description = "起始时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "结束时间（ISO-8601）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        LocalDateTime[] range = normalizeRange(from, to);
        return ResponseEntity.ok(accountingService.getDiscrepancies(range[0], range[1]));
    }

    // ==================== 参数处理 ====================

    /** from/to 缺省处理：均缺省 → 最近 30 天；单缺省 → 各自按 now / now-30d 兜底。 */
    private LocalDateTime[] normalizeRange(LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime f = from != null ? from : now.minusDays(30);
        LocalDateTime t = to != null ? to : now;
        if (f.isAfter(t)) {
            throw new BusinessException("INVALID_TIME_RANGE", "起始时间不能晚于结束时间");
        }
        if (java.time.Duration.between(f, t).toDays() > MAX_RANGE_DAYS) {
            throw new BusinessException("TIME_RANGE_TOO_LARGE",
                    "查询时间跨度不能超过 " + MAX_RANGE_DAYS + " 天，请缩小范围后重试");
        }
        return new LocalDateTime[]{f, t};
    }

    private PaymentMethod parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return PaymentMethod.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_CHANNEL", "非法支付渠道：" + channel);
        }
    }

    private PaymentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PaymentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_PAYMENT_STATUS", "非法支付状态：" + status);
        }
    }

    private Currency parseCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        // Currency.fromCode 对未知代码抛 BusinessException（映射 400）
        return Currency.fromCode(currency.trim());
    }
}
