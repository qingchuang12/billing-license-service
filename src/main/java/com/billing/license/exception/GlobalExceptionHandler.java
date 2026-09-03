package com.billing.license.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全局异常处理器
 * 统一处理 Controller 层抛出的各类异常，返回标准化的错误响应
 *
 * H2 安全约束：绝不直接把异常原始消息（可能含 SQL、堆栈、内部路径）回显给客户端。
 * 通用异常统一返回脱敏文案并记录完整异常服务端日志（含关联 ID 便于排查）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常（消息为应用可控文案，可回显，但需服务端留痕）
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 业务异常：errorCode={}, message={}", traceId, ex.getErrorCode(), ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("traceId", traceId);
        body.put("success", false);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 处理通用异常：脱敏，不明确回显内部细节；完整异常记录到服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        String traceId = newTraceId();
        // 服务端记录完整异常与堆栈，便于排查；绝不外泄
        log.error("[{}] 未预期异常：", traceId, ex);
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("errorCode", "INTERNAL_ERROR");
        // H2：脱敏文案，不回显 ex.getMessage()（防 SQL / 堆栈 / 路径泄漏）
        body.put("message", "系统内部错误，请稍后重试或联系支持（参考 traceId：" + traceId + "）");
        body.put("traceId", traceId);
        body.put("success", false);
        return ResponseEntity.internalServerError().body(body);
    }

    /**
     * 处理管理后台鉴权失败
     */
    @ExceptionHandler(AdminUnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleAdminUnauthorized(AdminUnauthorizedException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 管理后台鉴权失败：{}", traceId, ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("errorCode", "ADMIN_UNAUTHORIZED");
        body.put("message", "未授权：管理员密钥无效或缺失");
        body.put("traceId", traceId);
        body.put("success", false);
        return ResponseEntity.status(401).body(body);
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
