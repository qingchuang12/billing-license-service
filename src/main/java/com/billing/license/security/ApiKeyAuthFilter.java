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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API Key 鉴权过滤器（B3）。
 *
 * 仅当请求携带合法的管理端 API Key（X-API-Key ∈ 配置的 admin-api-keys）时，
 * 才在 SecurityContext 中写入 ROLE_ADMIN 身份；其余请求保持匿名。
 * 是否允许访问由 SecurityConfig 的 authorizeHttpRequests 决定，本过滤器不做授权判断。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String headerName;
    private final Set<String> keyHashes;

    public ApiKeyAuthFilter(String headerName, String adminApiKeys) {
        this.headerName = headerName;
        // W26：启动时计算各密钥 SHA-256 哈希并缓存，避免运行时明文比较（与 AdminController 收敛）
        this.keyHashes = (adminApiKeys == null || adminApiKeys.isBlank())
                ? Set.of()
                : Arrays.stream(adminApiKeys.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(ApiKeyAuthFilter::sha256Base64).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(headerName);
        if (key != null && matchKey(key)) {
            var auth = new UsernamePasswordAuthenticationToken(
                "api-key", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    /** W26：常量时间比较密钥哈希，避免明文 List.contains 的时序侧信道 */
    private boolean matchKey(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        String hash = sha256Base64(presented);
        for (String stored : keyHashes) {
            if (MessageDigest.isEqual(
                    hash.getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static String sha256Base64(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }
}
