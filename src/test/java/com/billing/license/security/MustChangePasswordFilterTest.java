package com.billing.license.security;

import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MustChangePasswordFilter} 单元测试（plan-7.0 / Q3 = 是）。
 *
 * <p>核心是两件事：① 待改密的用户<b>只能</b>走到「读自己 / 改密 / 登出」与管理员代重置；
 * ② 其余受保护 API（含 {@code /api/admin/**} 的普通管理端点）一律 {@code PASSWORD_CHANGE_REQUIRED}。
 * 未登录（匿名）请求不受本过滤器影响——那属于鉴权，不是强制改密。
 */
class MustChangePasswordFilterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final UserRepository userRepository = mock(UserRepository.class);

    private final MustChangePasswordFilter filter = new MustChangePasswordFilter(userRepository);

    @BeforeEach
    void setUp() {
        User pending = User.builder()
            .id(USER_ID).email("u@example.com").passwordHash("x")
            .role(User.UserRole.USER).status(User.UserStatus.ACTIVE)
            .mustChangePassword(true)
            .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pending));
        authenticate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 模拟 JwtAuthFilter 写入的上下文（principal = userId 字符串） */
    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(USER_ID.toString(), null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private MockHttpServletResponse invoke(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() == null) {
            // 被拦下：断言响应体带业务码
            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"),
                "拦截响应须带业务码 PASSWORD_CHANGE_REQUIRED");
        }
        return response;
    }

    @Test
    void shouldRejectProtectedApi_whenChangeRequired() throws Exception {
        invoke("GET", "/api/admin/users");
        invoke("GET", "/api/account/licenses");
        invoke("POST", "/api/account/mfa/enroll");
    }

    @Test
    void shouldAllowSelfServicePaths() throws Exception {
        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/account/me");
        me.setRequestURI("/api/account/me");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(me, new MockHttpServletResponse(), chain);
        assertEquals(me, chain.getRequest(), "读自己是渲染改密引导所必需，必须放行");

        MockHttpServletRequest change = new MockHttpServletRequest("POST", "/api/account/password/change");
        change.setRequestURI("/api/account/password/change");
        MockFilterChain changeChain = new MockFilterChain();
        filter.doFilter(change, new MockHttpServletResponse(), changeChain);
        assertEquals(change, changeChain.getRequest(), "改密是唯一的自救出口，必须放行");

        MockHttpServletRequest logout = new MockHttpServletRequest("POST", "/api/account/logout");
        logout.setRequestURI("/api/account/logout");
        MockFilterChain logoutChain = new MockFilterChain();
        filter.doFilter(logout, new MockHttpServletResponse(), logoutChain);
        assertEquals(logout, logoutChain.getRequest(), "登出必须放行");
    }

    /** 管理员代重置是「给他人解困」的处置动作，不能被自己的过滤器堵死。 */
    @Test
    void shouldAllowAdminResetPasswordEndpoint() throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/admin/users/" + UUID.randomUUID() + "/password/reset");
        request.setRequestURI("/api/admin/users/" + UUID.randomUUID() + "/password/reset");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals(request, chain.getRequest());
    }

    @Test
    void shouldPassThrough_whenFlagCleared() throws Exception {
        User cleared = User.builder()
            .id(USER_ID).email("u@example.com").passwordHash("x")
            .role(User.UserRole.USER).status(User.UserStatus.ACTIVE)
            .mustChangePassword(false)
            .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(cleared));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setRequestURI("/api/admin/users");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(request, chain.getRequest());
    }

    @Test
    void shouldPassThrough_whenAnonymous() throws Exception {
        SecurityContextHolder.clearContext(); // 未登录：401 由授权规则产出，本过滤器不介入

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setRequestURI("/api/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
        assertNull(response.getErrorMessage());
    }

    @Test
    void shouldPassThrough_forStaticAssets() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/index.html");
        request.setRequestURI("/admin/index.html");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(request, chain.getRequest(), "静态页不是 API，不应被强制改密逻辑处理");
    }
}
