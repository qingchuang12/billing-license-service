package com.billing.license.common.web;

import com.billing.license.dto.ApiResponse;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
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
 * - 异常处理器（{@code .exception.} 包）与 Webhook 控制器（{@code .webhook.} 包）的返回值；
 * - **springdoc / Swagger UI 端点**（{@code org.springdoc} 包 或 {@code /v3/api-docs}、{@code /swagger-ui} 路径）——
 *   否则 {@code /v3/api-docs/swagger-config} 被包成业务壳，Swagger UI 读不到顶层 {@code url}
 *   而回退到内置的 petstore 示例地址。
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        var declaring = returnType.getContainingClass();
        if (declaring.getPackage() != null) {
            String pkg = declaring.getPackage().getName();
            // springdoc 自带端点（@Tag 文档、swagger-config 等）必须原样返回，不能包裹
            if (pkg.startsWith("org.springdoc")) {
                return false;
            }
            // N2：`.exception.` 这条判断从未生效——包名为 com.billing.license.exception，
            // 结尾没有多余的点，contains(".exception.") 恒为 false，故异常处理器的返回值曾被二次包壳。
            // 改为 endsWith/contains 双写，并保留 beforeBodyWrite 里的 instanceof 兜底。
            if (pkg.endsWith(".exception") || pkg.contains(".exception.")
                    || pkg.endsWith(".webhook") || pkg.contains(".webhook.")) {
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
        // 兜底：按路径排除 springdoc / Swagger UI 资源（防同名包或包装器的其他来源）
        String path = request.getURI().getPath();
        if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
            return body;
        }
        if (body == null) {
            return null;
        }
        // 已是统一壳 / 原始类型则不重复包裹。
        // 注：此处 body 是响应体，永远不会是 ResponseEntity 本身（ResponseEntity 由框架解包后传入 body），
        // 原先的 `body instanceof ResponseEntity` 分支不可达，已按 H-C3 清理。
        if (body instanceof ApiResponse
                || body instanceof String
                || body instanceof byte[]) {
            return body;
        }
        String traceId = MDC.get("traceId");
        return ApiResponse.ok(body, traceId);
    }
}
