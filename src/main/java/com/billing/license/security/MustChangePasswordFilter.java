package com.billing.license.security;

import com.billing.license.dto.ApiResponse;
import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 强制改密过滤器（plan-7.0 / Q3 = 是）。
 *
 * <p>挂在 {@link JwtAuthFilter} <b>之后</b>（此时 {@code SecurityContext} 已按 DB 现查的角色写入），
 * 当当前用户 {@code users.must_change_password = true} 时，只放行「读自己 / 改密 / 登出」三条
 * 最小自救路径，其余受保护 API 一律返回 {@code PASSWORD_CHANGE_REQUIRED}。
 *
 * <p><b>为什么必须是服务端约束</b>：管理员代重置后若不强制改密，代重置的密码会长期有效，
 * 而知道该密码的人不止用户本人（管理员在设置时必然见过）。前端弹窗只是引导，可被直接调 API 绕过。
 *
 * <p><b>放行清单的完备性</b>：
 * <ul>
 *   <li>{@code /api/account/me}——前端要靠它识别「当前是谁」并渲染改密引导；</li>
 *   <li>{@code /api/account/password/change}——唯一的自救出口，否则用户被永久困住；</li>
 *   <li>{@code /api/account/logout}——允许退出当前半受限会话；</li>
 *   <li>{@code POST /api/admin/users/{userId}/password/reset}——管理员代重置本身。
 *       它是「给他人解困」的处置动作：同事被代重置后若无法完成改密，仍须能由另一名管理员
 *       再代重置一次，否则管理台会失去账号运营能力。该端点本身禁止操作自身，不构成绕过。</li>
 * </ul>
 *
 * <p>未登录（匿名）请求一律放行：认证由 {@code SecurityConfig} 的授权规则产出 401，
 * 本过滤器不处理「有没有身份」，只处理「已有身份但必须先改密」。
 */
@Slf4j
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 最小自救路径：读自己 / 改密 / 登出 */
    private static final Set<String> ALLOWED_PATHS = Set.of(
        "/api/account/me",
        "/api/account/password/change",
        "/api/account/logout");

    /** 管理员代重置：给他人解困的处置动作，不得被自己的过滤器堵死 */
    private static final Pattern ADMIN_RESET_PASSWORD_PATH =
        Pattern.compile("/api/admin/users/[^/]+/password/reset");

    private static final String API_PREFIX = "/api/";
    private static final List<String> ALLOWED_METHODS_FOR_RESET = List.of("POST");

    private final UserRepository userRepository;

    public MustChangePasswordFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isRestricted(request)) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId = CurrentUserResolver.currentUserIdOrNull();
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isMustChangePassword()) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("强制改密拦截：userId={}, method={}, path={}",
            userId, request.getMethod(), request.getRequestURI());
        reject(response);
    }

    /** 是否属于「已登录且必须先改密」时应被拦下的请求。 */
    private boolean isRestricted(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(API_PREFIX)) {
            return false; // 静态页与非 API 路径不拦
        }
        if (ALLOWED_PATHS.contains(path)) {
            return false;
        }
        return !(ADMIN_RESET_PASSWORD_PATH.matcher(path).matches()
            && ALLOWED_METHODS_FOR_RESET.contains(request.getMethod()));
    }

    /** 与 {@link JsonAccessDeniedHandler} 同构的 JSON 包壳，便于前端按顶层 code 分支。 */
    private void reject(HttpServletResponse response) throws IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getWriter(),
            ApiResponse.fail("PASSWORD_CHANGE_REQUIRED", "请先修改密码后再使用其他功能", traceId));
    }
}
