package com.billing.license.security;

import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户令牌鉴权过滤器（plan v2.10 / A2），与 {@link ApiKeyAuthFilter} <b>并列</b>而非互斥：
 * 两过滤器各认各的凭证，任一命中即写入对应身份。用户令牌按 {@code users.role} 授予（plan-6.0）：
 * 消费者为 {@code ROLE_USER}；管理员为 {@code ROLE_USER + ROLE_ADMIN}。角色不写进 JWT，每请求现查。
 *
 * <p><b>只认 {@code Authorization: Bearer <token>}</b>，且仅此一处读取令牌。
 *
 * <p>校验顺序（plan 8.2）：签名 → 过期 → 用户存在且 {@code ACTIVE} → {@code ver == users.token_version}。
 * 任一步不满足即<b>不写身份</b>（保持匿名），由 {@code SecurityConfig} 的授权规则产出 401；
 * 本过滤器<b>不抛异常</b>，避免把「令牌无效」这类客户端错误变成 500。
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtTokenService tokenService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtTokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length()).trim();
            if (!token.isEmpty()) {
                authenticate(token);
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = tokenService.parse(token);
            UUID userId = tokenService.extractUserId(claims);
            if (userId == null) {
                return;
            }
            Optional<User> found = userRepository.findById(userId);
            if (found.isEmpty()) {
                log.debug("令牌对应用户不存在：userId={}", userId);
                return;
            }
            User user = found.get();
            if (user.getStatus() != User.UserStatus.ACTIVE) {
                log.debug("用户非 ACTIVE，拒绝令牌：userId={}, status={}", userId, user.getStatus());
                return;
            }
            if (user.getTokenVersion() != tokenService.extractTokenVersion(claims)) {
                log.debug("令牌版本失效（已登出/改密）：userId={}", userId);
                return;
            }
            // A3（plan-6.0）：按 DB 现查的角色授予权限（角色不写进 JWT，降权即时生效）。
            // ADMIN 同时授予 ROLE_USER —— 否则管理员调不了 /api/account/** 下的登出与改密，
            // 那两个端点要求 ROLE_USER，只会 ROLE_ADMIN 的管理员连自助登出都做不到。
            var authorities = user.getRole() == User.UserRole.ADMIN
                ? AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")
                : AuthorityUtils.createAuthorityList("ROLE_USER");
            var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (ExpiredJwtException e) {
            log.debug("令牌已过期");
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不符 / 格式错误：客户端问题，记 debug 即可，不回显细节
            log.debug("令牌非法：{}", e.getMessage());
        }
    }
}
