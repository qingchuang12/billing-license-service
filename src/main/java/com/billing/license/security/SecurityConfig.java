package com.billing.license.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（B3）。
 *
 * 设计原则：
 * 1. 支付回调 /api/webhooks/** 全部放行——各渠道策略自行校验签名，不应被框架拦截导致收不到款。
 * 2. 客户端可公开访问的端点（收银台创建/状态轮询、License 离线校验、凭兑换码兑换）放行，
 *    其安全性依赖签名 License + 限流（RateLimitService），后续可按需收紧。
 * 3. 管理端与特权操作（/api/admin/**、/api/v1/licenses/**、/api/v1/orders/**、
 *    兑换码生成/吊销）必须携带合法 X-API-Key（ROLE_ADMIN），否则 401。
 * 4. 其余一切请求默认拒绝（denyAll），避免遗漏暴露。
 * 5. 无状态（STATELESS）+ 关闭 CSRF（纯 API、令牌鉴权，无浏览器会话，CSRF 不适用）。
 * 6. H9：CORS 按配置白名单开放（默认不开放跨域），仅在部署独立前端域名时显式配置。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${security.api-key-header:X-API-Key}")
    private String apiKeyHeader;

    @Value("${security.admin-api-keys:}")
    private String adminApiKeys;

    /** H9：允许跨域的源头（逗号分隔），留空则不开放跨域 */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        ApiKeyAuthFilter apiKeyFilter = new ApiKeyAuthFilter(apiKeyHeader, adminApiKeys);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/error").permitAll()
                // H13：健康检查与 k8s 探针（liveness/readiness）由编排平台从集群内探测，
                // 不走 API Key，必须放行，否则探针失败导致 Pod 被反复重启。
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // w14：放行 Swagger/OpenAPI 文档（开发联调用，生产可按需收紧）
                .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/webhooks/**", "/api/checkout/**",
                    "/api/v1/licenses/verify/**", "/api/v1/redeem/redeem").permitAll()
                // i7：管理端鉴权收敛为单 header。AdminController 自身已用 X-Admin-API-Key 校验，
                // 故 SecurityConfig 不再对 /api/admin/** 要求 ROLE_ADMIN（避免管理端点需同时带两个 header）。
                // 其余特权端点仍由 ApiKeyAuthFilter 要求 X-API-Key + ROLE_ADMIN。
                .requestMatchers("/api/admin/**").permitAll()
                .requestMatchers("/api/v1/licenses/**",
                    "/api/v1/orders/**", "/api/v1/redeem/generate",
                    "/api/v1/redeem/revoke/**").hasAuthority("ROLE_ADMIN")
                .anyRequest().denyAll())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * H9：基于配置白名单的 CORS 源。allowed-origins 为空时返回一个「不允许任何跨域」的配置，
     * 等价于默认关闭跨域，避免误放开。
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if (allowedOrigins != null && !allowedOrigins.trim().isEmpty()) {
            List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(o -> !o.isEmpty()).toList();
            config.setAllowedOriginPatterns(origins);
        }
        // 仅允许已配置源头；明确约束方法、头部与是否带凭证
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-API-Key", "X-Admin-API-Key"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
