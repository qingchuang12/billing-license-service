package com.billing.license.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一处理 Controller 层抛出的各类异常，返回标准化的错误响应
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 处理业务异常
     * @param ex 业务异常对象
     * @return 包含错误信息的 ResponseEntity
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("errorCode", ex.getErrorCode());
        body.put("message", ex.getMessage());
        body.put("success", false);
        return ResponseEntity.badRequest().body(body);
    }
    
    /**
     * 处理通用异常
     * @param ex 通用异常对象
     * @return 包含错误信息的 ResponseEntity
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("errorCode", "INTERNAL_ERROR");
        body.put("message", ex.getMessage());
        body.put("success", false);
        return ResponseEntity.internalServerError().body(body);
    }
}
