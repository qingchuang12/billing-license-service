package com.billing.license.exception;

import com.billing.license.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局异常处理器脱敏测试（H2）+ N2 响应壳契约测试。
 *
 * <p>通用异常绝不可把原始异常消息（可能含 SQL / 路径 / 堆栈）回显给客户端。
 *
 * <p>N2（2026-09-20）：各 handler 由自拼 {@code Map} 改为 {@code ApiResponse}，
 * 顶层即 {@code success=false / code / message / traceId}——此前真提示被 ApiResponseAdvice
 * 二次包壳沉到 {@code data.message}，前端读顶层 message 恒为 null，只能显示兜底文案。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleGenericException_shouldNotLeakInternalDetail() {
        RuntimeException ex = new RuntimeException("致命：SELECT * FROM users WHERE password='secret'");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleGenericException(ex);

        assertEquals(500, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);

        assertFalse(body.success(), "失败响应顶层 success 必须为 false（不得被包成成功壳）");
        assertEquals("INTERNAL_ERROR", body.code());
        String message = body.message();
        // H2：绝不包含异常原始文本
        assertFalse(message.contains("SELECT"), "响应不得泄漏 SQL 片段");
        assertFalse(message.contains("secret"), "响应不得泄漏敏感字段");
        assertTrue(message.contains("系统内部错误"), "应使用脱敏文案");
        assertNotNull(body.traceId(), "应返回可追溯的 traceId");
    }

    @Test
    void handleBusinessException_shouldPreserveControlledMessage() {
        BusinessException ex = new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(ex);

        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.success());
        assertEquals("ORDER_NOT_FOUND", body.code(), "错误码必须在顶层 code，而非 errorCode / data.code");
        assertEquals("订单不存在", body.message());
        assertNotNull(body.traceId());
    }

    /** 业务异常缺码时兜底，避免顶层出现 code=null */
    @Test
    void handleBusinessException_nullCode_fallsBackToDefault() {
        BusinessException ex = new BusinessException(null, "无码业务异常");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(ex);

        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals("BUSINESS_ERROR", body.code());
    }

    /**
     * K6（2026-09-18）：畸形 JSON / 空请求体此前无 handler，会落到通用异常分支返回 500；
     * 这是调用方的客户端错误，应为 400，且不得回显解析细节（可能含请求体片段 = PII）。
     */
    @Test
    void handleUnreadableBody_shouldReturn400WithoutLeakingParseDetail() {
        var ex = new HttpMessageNotReadableException(
            "JSON parse error: Unexpected character ('b'): expected a valid value (email: buyer@example.com)",
            new IllegalStateException("内部解析器细节"), null);

        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnreadableBody(ex);

        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals("INVALID_REQUEST_BODY", body.code());
        String message = body.message();
        assertFalse(message.contains("buyer@example.com"), "响应不得泄漏请求体片段");
        assertFalse(message.contains("内部解析器细节"), "响应不得泄漏解析器内部信息");
        assertNotNull(body.traceId());
    }

    /**
     * K6-follow（2026-09-18）：缺必填参数 / 方法不支持本应由 Spring MVC 返回 400 / 405，
     * 但被 {@code @ExceptionHandler(Exception.class)} 抢先吞成 500；补显式 handler 后回归正确状态码。
     */
    @Test
    void handleMissingParameter_shouldReturn400() {
        var ex = new MissingServletRequestParameterException("customerEmail", "String");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleMissingParameter(ex);

        assertEquals(400, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals("MISSING_PARAMETER", body.code());
        assertTrue(body.message().contains("customerEmail"));
        assertNotNull(body.traceId());
    }

    @Test
    void handleMethodNotSupported_shouldReturn405() {
        var ex = new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals("METHOD_NOT_ALLOWED", body.code());
        assertNotNull(body.traceId());
    }

    @Test
    void handleAdminUnauthorized_shouldReturn401ApiEnvelope() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleAdminUnauthorized(new AdminUnauthorizedException());

        assertEquals(401, resp.getStatusCode().value());
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.success());
        assertEquals("ADMIN_UNAUTHORIZED", body.code());
        assertNotNull(body.traceId());
    }
}
