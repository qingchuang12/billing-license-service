package com.billing.license.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局异常处理器脱敏测试（H2）。
 * 通用异常绝不可把原始异常消息（可能含 SQL / 路径 / 堆栈）回显给客户端。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleGenericException_shouldNotLeakInternalDetail() {
        RuntimeException ex = new RuntimeException("致命：SELECT * FROM users WHERE password='secret'");
        ResponseEntity<Map<String, Object>> resp = handler.handleGenericException(ex);

        assertEquals(500, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);

        String message = (String) body.get("message");
        // H2：绝不包含异常原始文本
        assertFalse(message.contains("SELECT"), "响应不得泄漏 SQL 片段");
        assertFalse(message.contains("secret"), "响应不得泄漏敏感字段");
        assertTrue(message.contains("系统内部错误"), "应使用脱敏文案");
        assertNotNull(body.get("traceId"), "应返回可追溯的 traceId");
    }

    @Test
    void handleBusinessException_shouldPreserveControlledMessage() {
        BusinessException ex = new BusinessException("ORDER_NOT_FOUND", "订单不存在");
        ResponseEntity<Map<String, Object>> resp = handler.handleBusinessException(ex);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertEquals("ORDER_NOT_FOUND", body.get("errorCode"));
        assertEquals("订单不存在", body.get("message"));
        assertNotNull(body.get("traceId"));
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

        ResponseEntity<Map<String, Object>> resp = handler.handleUnreadableBody(ex);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("INVALID_REQUEST_BODY", body.get("errorCode"));
        String message = (String) body.get("message");
        assertFalse(message.contains("buyer@example.com"), "响应不得泄漏请求体片段");
        assertFalse(message.contains("内部解析器细节"), "响应不得泄漏解析器内部信息");
        assertNotNull(body.get("traceId"));
    }

    /**
     * K6-follow（2026-09-18）：缺必填参数 / 方法不支持本应由 Spring MVC 返回 400 / 405，
     * 但被 {@code @ExceptionHandler(Exception.class)} 抢先吞成 500；补显式 handler 后回归正确状态码。
     */
    @Test
    void handleMissingParameter_shouldReturn400() {
        var ex = new MissingServletRequestParameterException("customerEmail", "String");

        ResponseEntity<Map<String, Object>> resp = handler.handleMissingParameter(ex);

        assertEquals(400, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("MISSING_PARAMETER", body.get("errorCode"));
        assertTrue(((String) body.get("message")).contains("customerEmail"));
        assertNotNull(body.get("traceId"));
    }

    @Test
    void handleMethodNotSupported_shouldReturn405() {
        var ex = new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<Map<String, Object>> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("METHOD_NOT_ALLOWED", body.get("errorCode"));
        assertNotNull(body.get("traceId"));
    }
}
