package com.billing.license.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * M3 统一响应体。
 * 成功态用 {@link #ok(Object)}；异常态由 {@link com.billing.license.exception.GlobalExceptionHandler}
 * 返回结构一致的 Map（含 traceId）。新增接口建议直接返回 {@code ApiResponse<T>}，由
 * {@code ApiResponseAdvice} 自动包裹普通返回值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "统一响应壳；除支付回调（/api/webhooks/**）外，所有端点响应均由 ApiResponseAdvice 包裹为本结构")
public record ApiResponse<T>(
        @Schema(description = "是否成功：true=成功，false=失败", example = "true")
        boolean success,

        @Schema(description = "业务码：成功固定为 SUCCESS，失败为业务错误码", example = "SUCCESS")
        String code,

        @Schema(description = "错误描述；成功时为 null（NON_NULL 序列化下不出现）",
                example = "收银台不存在")
        String message,

        @Schema(description = "业务数据；失败时为 null（NON_NULL 序列化下不出现）")
        T data,

        @Schema(description = "请求追踪 ID，与响应头 X-Trace-Id 一致",
                example = "8f3c1d2e-9a4b-4c6d-8e1f-2b3a4c5d6e7f")
        String traceId,

        @Schema(description = "响应时间（ISO-8601，无时区）", example = "2026-09-14T22:49:37")
        LocalDateTime timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "SUCCESS", null, data, null, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, "SUCCESS", null, data, traceId, LocalDateTime.now());
    }

    public static ApiResponse<Void> fail(String code, String message) {
        return new ApiResponse<>(false, code, message, null, null, LocalDateTime.now());
    }

    /**
     * 带 traceId 的失败响应（N2，2026-09-20）。
     * 供 {@code GlobalExceptionHandler} 与鉴权入口点/拒绝处理器使用：它们自行生成 traceId，
     * 不走 MDC，必须显式带入，否则故障排查时拿不到关联 ID。
     */
    public static ApiResponse<Void> fail(String code, String message, String traceId) {
        return new ApiResponse<>(false, code, message, null, traceId, LocalDateTime.now());
    }
}
