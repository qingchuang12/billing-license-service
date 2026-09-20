package com.billing.license.security;

import com.billing.license.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * N3（2026-09-20）：已认证但权限不足（如普通用户访问 /api/admin/**、被 denyAll 拦下的路径）时的 403 响应体。
 *
 * <p>原因同 {@link JsonAuthenticationEntryPoint}：默认实现返回空 body，调用方拿不到任何可读提示。
 */
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(),
            ApiResponse.fail("ACCESS_DENIED", "无权访问该资源", traceId));
    }
}
