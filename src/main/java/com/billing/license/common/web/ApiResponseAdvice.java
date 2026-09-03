package com.billing.license.common.web;

import com.billing.license.dto.ApiResponse;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * M3：统一响应体建议。
 * 将 Controller 直接返回的普通数据（实体 / DTO / Map）自动包裹为 {@link ApiResponse}，
 * 保持对外响应结构一致（success/code/message/data/traceId/timestamp）。
 *
 * 安全排除，避免破坏既有契约：
 * - {@code ResponseEntity}（已有显式状态码与结构）；
 * - 已是 {@link ApiResponse}；
 * - 原始 {@code String}（如 Webhook 回执文本、text/plain）；
 * - {@code byte[]}（文件/原始流）；
 * - 异常处理器（{@code .exception.} 包）与 Webhook 控制器（{@code .webhook.} 包）的返回值。
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        var declaring = returnType.getContainingClass();
        if (declaring.getPackage() != null) {
            String pkg = declaring.getPackage().getName();
            if (pkg.contains(".exception.") || pkg.contains(".webhook.")) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  org.springframework.http.server.ServerHttpRequest request,
                                  org.springframework.http.server.ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        if (body instanceof ResponseEntity
                || body instanceof ApiResponse
                || body instanceof String
                || body instanceof byte[]) {
            return body;
        }
        String traceId = MDC.get("traceId");
        return ApiResponse.ok(body, traceId);
    }
}
