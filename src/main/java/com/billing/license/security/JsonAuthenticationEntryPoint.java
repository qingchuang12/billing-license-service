package com.billing.license.security;

import com.billing.license.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.util.UUID;

/**
 * N3（2026-09-20）：未携带/携带无效凭证访问受保护端点时的 401 响应体。
 *
 * <p>此前用 {@code HttpStatusEntryPoint} 只回状态码、body 为空，页面端取不到任何提示，
 * 只能落兜底文案「操作失败，请稍后重试」。此处写与业务错误**同壳同构**的 JSON
 * （{@code success:false, code, message, traceId}），前端 {@code buildApiError} 可直接消费。
 *
 * <p>脱敏：不回显异常原因（可能含令牌片段、内部路径），只给固定文案 + traceId 便于服务端对照日志。
 *
 * <p>序列化用本类自带的 mapper（与 {@link com.billing.license.service.payment.PaymentService} 同款做法）：
 * 这里绕过了 Spring 的消息转换器，容器里的 ObjectMapper 并不作为 Bean 暴露，无法注入。
 */
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(),
            ApiResponse.fail("UNAUTHORIZED", "登录状态已失效或未提供凭证，请重新登录", traceId));
    }
}
