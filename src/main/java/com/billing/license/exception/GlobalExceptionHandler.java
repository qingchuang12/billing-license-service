package com.billing.license.exception;

import com.billing.license.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理 Controller 层抛出的各类异常，返回标准化的错误响应
 *
 * H2 安全约束：绝不直接把异常原始消息（可能含 SQL、堆栈、内部路径）回显给客户端。
 * 通用异常统一返回脱敏文案并记录完整异常服务端日志（含关联 ID 便于排查）。
 *
 * N2（2026-09-20）：此前各 handler 返回自拼 {@code Map<String,Object>}，被 {@code ApiResponseAdvice}
 * 当成普通业务数据**再包一层成功壳**（HTTP 状态仍是 400，但响应体 {\@code success:true}、真提示沉到
 * {@code data.message}），前端读顶层 {@code message} 恒为 null → 一律显示兜底文案「操作失败，请稍后重试」。
 * 现统一返回 {@code ResponseEntity<ApiResponse<Void>>}：{@code ApiResponseAdvice} 对
 * {@code body instanceof ApiResponse} 原样放行，错误结构与成功响应同壳同构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常缺码时的兜底码，避免响应里出现 code=null */
    private static final String DEFAULT_BIZ_CODE = "BUSINESS_ERROR";

    /**
     * 处理业务异常（消息为应用可控文案，可回显，但需服务端留痕）
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 业务异常：errorCode={}, message={}", traceId, ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.badRequest()
            .body(ApiResponse.fail(codeOf(ex.getErrorCode()), ex.getMessage(), traceId));
    }

    /**
     * 处理支付渠道未启用：消息为应用可控文案（仅含渠道名），可回显。
     * 返回 400 而非 500——这是调用方选错渠道的客户端错误，不是服务端故障。
     */
    @ExceptionHandler(ChannelDisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleChannelDisabled(ChannelDisabledException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 支付渠道未启用：method={}, message={}", traceId, ex.getPaymentMethod(), ex.getMessage());
        return ResponseEntity.badRequest()
            .body(ApiResponse.fail(codeOf(ex.getErrorCode()), ex.getMessage(), traceId));
    }

    /**
     * 处理请求体不可解析（畸形 JSON / 空 body / 类型不匹配）。
     *
     * <p>2026-09-18（K6）：这类错误此前无 handler，会落到通用异常分支被当成服务端故障返回 **500**；
     * 实际是调用方的客户端错误，应为 400。公开兑换端点（{@code POST /api/redeem/redeem}）尤其需要，
     * 否则客户端拿到 500 会误判为服务端故障。
     *
     * <p>不回声解析细节：Jackson 的原始消息会带上字段路径与原始片段（可能含客户邮箱等输入内容），
     * 只记异常类型到服务端日志，响应只给固定文案。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        String traceId = newTraceId();
        // 只记类型，不记消息原文：解析失败消息会回显请求体片段（可能含邮箱/兑换码等 PII）
        log.warn("[{}] 请求体不可解析：type={}, cause={}", traceId, ex.getClass().getSimpleName(),
            ex.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.badRequest()
            .body(ApiResponse.fail("INVALID_REQUEST_BODY", "请求体格式不正确（须为合法 JSON）", traceId));
    }

    /**
     * 处理缺少必填请求参数（缺 query/path 参数）。
     *
     * <p>K6-follow（2026-09-18）：Spring MVC 本会用 {@code DefaultHandlerExceptionResolver} 返回 400，
     * 但本类的 {@code @ExceptionHandler(Exception.class)} 优先级更高，会把这类客户端错误吞成 500。
     * 故显式声明，只回参数名（不含请求内容）。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 缺少必填请求参数：{}", traceId, ex.getParameterName());
        return ResponseEntity.badRequest()
            .body(ApiResponse.fail("MISSING_PARAMETER", "缺少必填参数：" + ex.getParameterName(), traceId));
    }

    /**
     * 处理请求方法不支持（如把 POST 端点用 GET 访问）→ 405，同样避免被通用分支吞成 500。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 请求方法不支持：method={}", traceId, ex.getMethod());
        return ResponseEntity.status(405)
            .body(ApiResponse.fail("METHOD_NOT_ALLOWED", "请求方法不支持", traceId));
    }

    /**
     * 处理通用异常：脱敏，不明确回显内部细节；完整异常记录到服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        String traceId = newTraceId();
        // 服务端记录完整异常与堆栈，便于排查；绝不外泄
        log.error("[{}] 未预期异常：", traceId, ex);
        // H2：脱敏文案，不回显 ex.getMessage()（防 SQL / 堆栈 / 路径泄漏）
        String message = "系统内部错误，请稍后重试或联系支持（参考 traceId：" + traceId + "）";
        return ResponseEntity.internalServerError()
            .body(ApiResponse.fail("INTERNAL_ERROR", message, traceId));
    }

    /**
     * 处理请求体参数校验失败（{@code @Valid}）。
     *
     * <p>仅回显我们自己写在注解上的中文提示，不回显字段名以外的内部信息；
     * 缺省文案兜底，避免注解漏写 message 时把默认英文串（如 "must not be blank"）暴露出去。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String traceId = newTraceId();
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getDefaultMessage())
            .filter(m -> m != null && !m.isEmpty())
            .distinct()
            .collect(Collectors.joining("；"));
        if (message.isEmpty()) {
            message = "请求参数校验失败";
        }
        log.warn("[{}] 请求参数校验失败：{}", traceId, message);
        return ResponseEntity.badRequest().body(ApiResponse.fail("VALIDATION_ERROR", message, traceId));
    }

    /**
     * 处理管理后台鉴权失败
     */
    @ExceptionHandler(AdminUnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAdminUnauthorized(AdminUnauthorizedException ex) {
        String traceId = newTraceId();
        log.warn("[{}] 管理后台鉴权失败：{}", traceId, ex.getMessage());
        return ResponseEntity.status(401)
            .body(ApiResponse.fail("ADMIN_UNAUTHORIZED", "未授权：管理员密钥无效或缺失", traceId));
    }

    private static String codeOf(String errorCode) {
        return (errorCode == null || errorCode.isBlank()) ? DEFAULT_BIZ_CODE : errorCode;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
