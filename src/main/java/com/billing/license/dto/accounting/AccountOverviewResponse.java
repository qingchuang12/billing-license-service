package com.billing.license.dto.accounting;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台账务收入总览响应。
 *
 * <p>按币种分组返回（{@link AccountCurrencySummary}），顶层再给一个全币种订单总数供速览；
 * 金额不跨币种合并。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "平台账务收入总览（按币种分组）")
public class AccountOverviewResponse {

    @Schema(description = "统计起始时间（ISO-8601，无时区）", example = "2026-09-01T00:00:00")
    private LocalDateTime from;

    @Schema(description = "统计结束时间（ISO-8601，无时区）", example = "2026-09-20T23:59:59")
    private LocalDateTime to;

    @Schema(description = "区间内订单总数（全部状态，跨币种合计的「笔数」）", example = "20")
    private long totalOrderCount;

    @Schema(description = "各币种汇总清单（金额不跨币种求和）")
    private List<AccountCurrencySummary> summaries;
}
