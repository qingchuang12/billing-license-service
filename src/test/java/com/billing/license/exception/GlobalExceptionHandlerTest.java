package com.billing.license.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
}
