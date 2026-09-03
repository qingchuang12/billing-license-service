package com.billing.license.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * API Key 鉴权过滤器（B3）。
 *
 * 仅当请求携带合法的管理端 API Key（X-API-Key ∈ 配置的 admin-api-keys）时，
 * 才在 SecurityContext 中写入 ROLE_ADMIN 身份；其余请求保持匿名。
 * 是否允许访问由 SecurityConfig 的 authorizeHttpRequests 决定，本过滤器不做授权判断。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String headerName;
    private final List<String> validKeys;

    public ApiKeyAuthFilter(String headerName, String adminApiKeys) {
        this.headerName = headerName;
        this.validKeys = (adminApiKeys == null || adminApiKeys.isBlank())
            ? List.of()
            : Arrays.stream(adminApiKeys.split(",")).map(String::trim).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(headerName);
        if (key != null && validKeys.contains(key.trim())) {
            var auth = new UsernamePasswordAuthenticationToken(
                "api-key", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
