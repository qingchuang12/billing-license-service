package com.billing.license.security;

import com.billing.license.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
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
 * 3. 管理端与全部管理动作（/api/admin/**：订单签发/退款、License 作废/换机、兑换码生成/撤销）
 *    必须由管理员账号登录后持 JWT（ROLE_ADMIN）访问，否则 401。
 * 4. 鉴权模型共两档（plan-6.0 / A12）：公开 / 用户（Authorization: Bearer JWT → ROLE_USER；管理员账号登录后额外获 ROLE_ADMIN）。X-API-Key 通道已于 2026-09-23 移除。
 *    账号公开端点（发码、注册、登录、找回密码、二次因子校验）逐条 permitAll 并声明在 /api/account/** 之前，
 *    其余账号端点需 ROLE_USER；管理端由同一 JwtAuthFilter 按 DB 现查角色授权（管理员账号额外获 ROLE_ADMIN），权限域严格隔离。
 *    其中「二次因子校验」两个端点属**半认证**（plan-7.0 / M3）：无访问令牌而持一次性票据，
 *    permitAll 是必需的形态，把关在方法内（票据由独立派生密钥签名，见 MfaTicketService）。
 * 5. 其余一切请求默认拒绝（denyAll），避免遗漏暴露。
 * 6. 无状态（STATELESS）+ 关闭 CSRF（纯 API、令牌鉴权，无浏览器会话，CSRF 不适用）。
 * 7. H9：CORS 按配置白名单开放（默认不开放跨域），仅在部署独立前端域名时显式配置。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;

    /** H9：允许跨域的源头（逗号分隔），留空则不开放跨域 */
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    public SecurityConfig(JwtTokenService jwtTokenService, UserRepository userRepository) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
    }

    /**
     * 密码编码器（plan v2.10 / A2）。BCrypt cost=12（plan 8.3）：
     * 单机 exe 配套服务的账号量级下，12 在安全性与登录延迟间取平衡。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtTokenService, userRepository);

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
                // 公开端点：收银台、支付回调、License 在线校验、兑换码兑换、公开产品目录
                .requestMatchers("/api/webhooks/**", "/api/checkout/**", "/api/products/**",
                    "/api/licenses/verify/**", "/api/redeem/redeem",
                    // plan-7.0 方案 A：激活端点「可选鉴权」——兑换码分支匿名可调，
                    // 许可证密钥分支的「必须登录」在 CredentialBindingService 内判定（不靠本行拦截）。
                    // 放行是必要的：若改 hasAuthority(ROLE_USER)，匿名兑换能力会被一并封掉。
                    "/api/licenses/activate",
                    // plan-7.0 / D2：客户端「自动上报绑定」——凭 signedToken 验签证明归属（E1 = ①），
                    // 无需登录，故必须放行；真伪与归属由方法内「验签 + 授权状态」把关。
                    "/api/licenses/report-binding").permitAll()
                // C8：机器码首次出现时间查询（客户端首跑 / 兑换前联网问一次，用于把试用起点回溯到
                // 服务端最早见到这台机器的时间，堵住「删档重装再领一次试用」）。
                // 只读、无 PII：机器码是硬件派生的随机串，响应只有两个时间戳。
                .requestMatchers("/api/licenses/machine/**").permitAll()
                // 收银台静态页（/checkout/index.html + css/js 资产）：客户端「在线激活」跳转的落地页，
                // 买家在支付前是匿名状态，必须与 /api/checkout/** 同批放行；页面自身无数据，仅静态资产。
                // 注意不是 /api/checkout（接口已在上行放行）——少了这条，页面会被 anyRequest().denyAll() 拦成 401。
                // B8：仅放行 GET（静态页只读）。收窄误暴露面——若将来有控制器误映射到该前缀，
                // 其 POST/PUT/DELETE 不会被这条静态放行规则意外放过（落入 anyRequest().denyAll()）。
                .requestMatchers(HttpMethod.GET, "/checkout/**").permitAll()
                // 「我的授权」静态页（/account/index.html + 资产，U2）：登录前页面自身无数据，
                // 鉴权由页面内的 /api/account/** 调用凭 JWT 完成，静态资产与 /checkout/** 同口径放行。
                // 同样注意不是 /api/account（接口已在上方按 ROLE_USER 保护，两条路径互不影响）。
                // B8：同样仅放行 GET。
                .requestMatchers(HttpMethod.GET, "/account/**").permitAll()
                // 管理统计静态页（/admin/index.html + 资产，2026-09-22）：页面自身无数据，
                // 鉴权由页面内的 /api/admin/** 调用凭管理员 JWT 完成，静态资产与 /checkout/**、
                // /account/** 同口径放行。同样不是 /api/admin（接口在下方按 ROLE_ADMIN 保护）。
                // B8：同样仅放行 GET。页面含 noindex 头，且所有数据仍需管理员 JWT 才能取到。
                .requestMatchers(HttpMethod.GET, "/admin/**").permitAll()
                // plan-7.0 / M3：二次因子的两个「半认证」端点。此刻调用者还没有访问令牌，
                // 只有一枚「密码已通过」的一次性票据，故必须放行——若要求 ROLE_USER，
                // 未过第二因子者永远拿不到令牌，形成死锁（与登录端点同坑）。
                // 真正的把关是方法内的票据校验（MfaTicketService 用独立派生密钥签名）。
                // ⚠️ 必须声明在下方 /api/account/** 规则**之前**：Spring Security 取首个匹配规则。
                .requestMatchers(HttpMethod.POST,
                    "/api/account/mfa/challenge",
                    "/api/account/mfa/verify").permitAll()
                // v2.10 账号公开端点：**必须逐条声明在 /api/account/** 之前**。
                // Spring Security 按声明顺序取首个匹配规则，若把宽松的 /api/account/** 写在前面，
                // 登录接口也会要求令牌 —— 未登录用户永远拿不到令牌，形成死锁。
                .requestMatchers(HttpMethod.POST,
                    "/api/account/verification-code",
                    "/api/account/register",
                    "/api/account/login",
                    "/api/account/password/reset").permitAll()
                // 其余账号端点（登出 / me / 改密 / 我的许可证·订单）需用户令牌。
                // 管理员因兼授 ROLE_USER（plan-6.0 / B1）同样通过本规则，即管理员可使用消费侧能力
                // （B5：能使用）。这些端点依赖「当前用户」上下文（principal 为 userId），管理员 ID
                // 本身即合法 userId，无歧义；管理端专属动作仍走下方 /api/admin/**（ROLE_ADMIN）。
                .requestMatchers("/api/account/**").hasAuthority("ROLE_USER")
                // 管理端与全部管理动作统一要求 ROLE_ADMIN；管理员账号登录后持 JWT（由 JwtAuthFilter
                // 按 DB 现查角色授权）即获此权限。X-API-Key 通道已于 plan-6.0 / A12 移除。
                .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                .anyRequest().denyAll())
            // N3：401/403 也要返回可读 JSON（原 HttpStatusEntryPoint 只回空 body，页面只能显示兜底文案）
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                .accessDeniedHandler(new JsonAccessDeniedHandler()))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
