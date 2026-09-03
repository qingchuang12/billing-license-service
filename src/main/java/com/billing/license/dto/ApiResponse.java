package com.billing.license.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * M3 统一响应体。
 * 成功态用 {@link #ok(Object)}；异常态由 {@link com.billing.license.exception.GlobalExceptionHandler}
 * 返回结构一致的 Map（含 traceId）。新增接口建议直接返回 {@code ApiResponse<T>}，由
 * {@code ApiResponseAdvice} 自动包裹普通返回值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
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
}
