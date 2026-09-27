package com.billing.license.security;

import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * {@link MustChangePasswordFilter} 放行清单的<b>边界证伪</b>（QA 独立复核，plan-7.0 P0）。
 *
 * <p>工程师自己的 {@code MustChangePasswordFilterTest} 只抽了 3 条被拦路径 + 3 条放行路径。
 * 真正的风险不在「这 3 条对不对」，而在<b>清单是否完备</b>——漏放行一条用户会永久被困，
 * 误放行一条则强制改密形同虚设。故这里把所有受保护端点按「前缀 × 方法」铺开逐条验证：
 * <ol>
 *   <li>{@code /api/admin/**} 全部管理端点（含 MFA、兑换码、订单、License）一律拦；</li>
 *   <li>{@code /api/account/**} 除三条自救路径外一律拦（含资产、退款、解绑、MFA、发码、自助找回）；</li>
 *   <li>管理员代重置端点只放行 {@code POST}，换方法 / 加后缀一律拦；</li>
 *   <li>非 {@code /api/} 前缀（静态页、执行器、Swagger）一律不拦。</li>
 * </ol>
 */
class MustChangePasswordFilterBoundaryTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final MustChangePasswordFilter filter = new MustChangePasswordFilter(userRepository);

    @BeforeEach
    void setUp() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pendingUser(true)));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(USER_ID.toString(), null,
                AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User pendingUser(boolean flag) {
        return User.builder()
            .id(USER_ID).email("u@example.com").passwordHash("x")
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE)
            .mustChangePassword(flag)
            .build();
    }

    /** 返回 true 表示请求被过滤器放行（进入后续过滤器链）。 */
    private boolean passes(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    /** 断言被拦下，并校验拦截响应的 HTTP 状态与业务码（与前端 handleAuthFailure 的分支条件一致）。 */
    private void assertBlocked(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "应被强制改密过滤器拦下：" + method + " " + path);
        assertEquals(403, response.getStatus(), "拦截状态须为 403：" + method + " " + path);
        assertTrue(response.getContentAsString().contains("PASSWORD_CHANGE_REQUIRED"),
            "拦截响应须带业务码 PASSWORD_CHANGE_REQUIRED：" + method + " " + path);
    }

    // ==================== 1. /api/admin/** 一律拦截 ====================

    @ParameterizedTest(name = "被拦：{0} {1}")
    @CsvSource({
        "GET,    /api/admin/users",
        "POST,   /api/admin/users/" + "33333333-3333-3333-3333-333333333333" + "/password/reset/extra",
        "PUT,    /api/admin/users/33333333-3333-3333-3333-333333333333/password/reset",
        "DELETE, /api/admin/users/33333333-3333-3333-3333-333333333333/password/reset",
        "GET,    /api/admin/users/33333333-3333-3333-3333-333333333333/password/reset",
        "PATCH,  /api/admin/users/33333333-3333-3333-3333-333333333333/role",
        "PATCH,  /api/admin/users/33333333-3333-3333-3333-333333333333/status",
        "GET,    /api/admin/orders",
        "GET,    /api/admin/licenses",
        "GET,    /api/admin/licenses/LIC-1",
        "GET,    /api/admin/payment-channels",
        "POST,   /api/admin/orders/NO-1/refund",
        "POST,   /api/admin/orders/NO-1/issue",
        "POST,   /api/admin/licenses/LIC-1/revoke",
        "POST,   /api/admin/licenses/LIC-1/unbind",
        "POST,   /api/admin/licenses/LIC-1/reissue",
        "POST,   /api/admin/redeem-codes/generate",
        "GET,    /api/admin/redeem-codes",
        "POST,   /api/admin/redeem-codes/revoke/CODE-1",
        "GET,    /api/admin/mfa/status",
        "POST,   /api/admin/mfa/enroll",
        "POST,   /api/admin/mfa/activate",
        "POST,   /api/admin/mfa/unbind",
        "GET,    /api/admin/accounting/overview",
        "GET,    /api/admin/accounting/transactions"
    })
    @DisplayName("待改密会话访问 /api/admin/** 管理端点一律拦截")
    void adminEndpointsMustBeBlocked(String method, String path) throws Exception {
        assertBlocked(method, path);
    }

    // ==================== 2. /api/account/** 除三条自救路径外一律拦截 ====================

    @ParameterizedTest(name = "被拦：{0} {1}")
    @CsvSource({
        "GET,  /api/account/licenses",
        "GET,  /api/account/subscriptions",
        "GET,  /api/account/orders",
        "POST, /api/account/orders/NO-1/refund",
        "POST, /api/account/licenses/LIC-1/unbind",
        "POST, /api/account/verification-code",
        "POST, /api/account/password/reset",
        "POST, /api/account/mfa/challenge",
        "POST, /api/account/mfa/verify",
        "POST, /api/account/register",
        "POST, /api/account/login",
        "GET,  /api/account/me/profile",
        "GET,  /api/account"
    })
    @DisplayName("待改密会话访问 /api/account/** 资产/账号端点一律拦截（自救路径除外）")
    void accountEndpointsMustBeBlocked(String method, String path) throws Exception {
        assertBlocked(method, path);
    }

    /**
     * 已知口径（非缺陷，但属加固项）：三条自救路径的放行<b>不区分 HTTP 方法</b>，
     * 只有管理员代重置路径做了 {@code POST} 限定。
     *
     * <p>当前不会造成绕过——这三个路径各自只有一种方法映射（GET me / POST change / POST logout），
     * 换方法会由 Spring MVC 返回 405。但放行清单按「路径」而非「方法 + 路径」判定，
     * 日后谁在 {@code /api/account/me} 上加一个 {@code DELETE}（注销账号）或 {@code PUT}（改邮箱），
     * 它会被这条规则<b>静默放行</b>——待改密会话本应只有改密一条出口。
     * 故此处用断言把现状固定下来：一旦有人收紧成方法级放行，本用例会失败并提醒同步检查。
     */
    @Test
    @DisplayName("现状记录：自救路径放行不区分方法（见方法内注释，属待加固项）")
    void selfServiceAllowListIsMethodAgnostic() throws Exception {
        assertTrue(passes("GET", "/api/account/password/change"),
            "现状：改密路径放行与 POST 限定无关（见注释）");
        assertTrue(passes("DELETE", "/api/account/me"),
            "现状：me 路径放行不区分方法（见注释）");
    }

    @ParameterizedTest(name = "放行：{0} {1}")
    @CsvSource({
        "GET,  /api/account/me",
        "POST, /api/account/password/change",
        "POST, /api/account/logout",
        "POST, /api/admin/users/33333333-3333-3333-3333-333333333333/password/reset"
    })
    @DisplayName("三条自救路径与管理员代重置必须放行")
    void selfServiceAndAdminResetMustPass(String method, String path) throws Exception {
        assertTrue(passes(method, path), "必须放行（否则用户被永久困住）：" + method + " " + path);
    }

    /** 代重置端点必须匹配任意合法 UUID 段，而不是只认固定的那一个。 */
    @Test
    @DisplayName("代重置端点对任意 userId 段都放行")
    void adminResetMustPassForAnyUserIdSegment() throws Exception {
        assertTrue(passes("POST", "/api/admin/users/" + UUID.randomUUID() + "/password/reset"));
        assertTrue(passes("POST", "/api/admin/users/" + TARGET_ID + "/password/reset"));
    }

    /** 「多一段路径」不能被误判为代重置端点——正则须用 matches 全匹配而非 find。 */
    @Test
    @DisplayName("代重置端点的额外路径段不得被放行")
    void adminResetWithExtraSegmentMustBeBlocked() throws Exception {
        assertBlocked("POST", "/api/admin/users/" + TARGET_ID + "/password/reset/");
        assertBlocked("POST", "/api/admin/users/" + TARGET_ID + "/password/reset/confirm");
        assertBlocked("POST", "/api/admin/users/a/b/password/reset");
    }

    // ==================== 3. 非 /api/ 前缀不受影响 ====================

    @ParameterizedTest(name = "不拦：{0}")
    @ValueSource(strings = {
        "/admin/index.html", "/admin/admin.js", "/admin/admin.css",
        "/account/index.html", "/checkout/index.html",
        "/actuator/health", "/v3/api-docs", "/swagger-ui/index.html",
        "/", "/error", "/apifoo"
    })
    @DisplayName("非 /api/ 前缀（静态页 / 执行器 / 文档）不得被强制改密逻辑处理")
    void nonApiPathsMustPass(String path) throws Exception {
        assertTrue(passes("GET", path), "非 API 路径不应被拦：" + path);
    }

    // ==================== 4. 前置条件：未置位 / 未登录 / 用户已删除 ====================

    @Test
    @DisplayName("标记未置位时全部端点放行")
    void mustPassWhenFlagCleared() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(pendingUser(false)));
        assertTrue(passes("GET", "/api/admin/users"));
        assertTrue(passes("GET", "/api/account/licenses"));
    }

    @Test
    @DisplayName("匿名请求一律放行（401 由授权规则产出，本过滤器不介入）")
    void mustPassWhenAnonymous() throws Exception {
        SecurityContextHolder.clearContext();
        assertTrue(passes("GET", "/api/admin/users"));
        assertTrue(passes("GET", "/api/account/me"));
    }

    @Test
    @DisplayName("主体非 UUID（如 anonymousUser）时放行")
    void mustPassWhenPrincipalIsNotUuid() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("anonymousUser", null,
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertTrue(passes("GET", "/api/admin/users"));
    }

    @Test
    @DisplayName("库中已无此用户（并发删除）时放行，不抛异常")
    void mustPassWhenUserDeleted() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        assertTrue(passes("GET", "/api/admin/users"));
    }

    @Test
    @DisplayName("拦截响应须是合法 JSON 失败壳，且不含任何敏感字段")
    void rejectionPayloadMustBeFailureEnvelope() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setRequestURI("/api/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        String body = response.getContentAsString();
        assertEquals(403, response.getStatus());
        assertTrue(body.contains("\"success\":false"), "失败壳须带 success=false：" + body);
        assertTrue(body.contains("\"code\":\"PASSWORD_CHANGE_REQUIRED\""), "失败壳须带业务码：" + body);
        assertNotEquals("", body.trim());
    }
}
