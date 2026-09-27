package com.billing.license.controller;

import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 强制改密的<b>端到端证伪</b>（QA 独立复核，plan-7.0 P0 / T01）。
 *
 * <p>与 {@code MustChangePasswordFilterBoundaryTest}（纯过滤器单元级）互补：这里走
 * <b>完整 Spring Security 过滤器链 + 真实控制器 + 真实 H2</b>，验证的不是「过滤器是否调用了 reject」，
 * 而是「用户实际打过去是什么结果」——包括前置的 JwtAuthFilter 是否真的把身份写进去了、
 * 后续的授权规则是否还活着、静态资源是否照常可达。
 *
 * <p>最关键的一条是<b>自救闭环</b>：被代重置的用户必须能靠「me → 改密 → 自由访问」走出困局，
 * 否则服务端约束就是把人锁死而不是保护。
 */
@SpringBootTest
@ActiveProfiles("test")
class MustChangePasswordEnforcementTest {

    private static final String ADMIN_EMAIL = "qa-mcp-admin@example.com";
    private static final String USER_EMAIL = "qa-mcp-user@example.com";
    private static final String ADMIN_SET_PASSWORD = "AdminSet0rd";
    private static final String USER_SELF_PASSWORD = "UserSelf0rd";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    private User newUser(String email, User.UserRole role, boolean mustChangePassword) {
        return userRepository.save(User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(ADMIN_SET_PASSWORD))
            .role(role).status(User.UserStatus.ACTIVE)
            .mustChangePassword(mustChangePassword)
            .build());
    }

    private String tokenFor(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        return jwtTokenService.issue(fresh.getId(), fresh.getTokenVersion());
    }

    private void assertPasswordChangeRequired(String method, String url, String token) throws Exception {
        var builder = "GET".equals(method) ? get(url) : post(url);
        mockMvc.perform(builder.header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // ==================== 1. 拦截：受保护的账号端点 ====================

    @Test
    @DisplayName("置位用户访问 /api/account 资产端点被拦")
    void accountAssetEndpointsAreBlocked() throws Exception {
        User user = newUser(USER_EMAIL, User.UserRole.USER, true);
        String token = tokenFor(user);

        assertPasswordChangeRequired("GET", "/api/account/licenses", token);
        assertPasswordChangeRequired("GET", "/api/account/subscriptions", token);
        assertPasswordChangeRequired("GET", "/api/account/orders", token);
    }

    @Test
    @DisplayName("置位管理员访问 /api/admin 管理端点被拦（含 MFA 与兑换码）")
    void adminEndpointsAreBlocked() throws Exception {
        User admin = newUser(ADMIN_EMAIL, User.UserRole.ADMIN, true);
        String token = tokenFor(admin);

        assertPasswordChangeRequired("GET", "/api/admin/users", token);
        assertPasswordChangeRequired("GET", "/api/admin/mfa/status", token);
        assertPasswordChangeRequired("GET", "/api/admin/orders", token);
    }

    // ==================== 2. 放行：三条自救路径 ====================

    @Test
    @DisplayName("置位用户可读自己 / 可登出")
    void selfServiceMeAndLogoutAreAllowed() throws Exception {
        User user = newUser(USER_EMAIL, User.UserRole.USER, true);
        String token = tokenFor(user);

        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mustChangePassword").value(true))
            .andExpect(jsonPath("$.data.email").value(USER_EMAIL));

        mockMvc.perform(post("/api/account/logout").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("置位管理员仍可为他人代重置（管理台不能失去账号运营能力）")
    void adminResetByPendindAdminIsAllowed() throws Exception {
        User admin = newUser(ADMIN_EMAIL, User.UserRole.ADMIN, true);
        User target = newUser(USER_EMAIL, User.UserRole.USER, false);

        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + ADMIN_SET_PASSWORD + "\"}"))
            .andExpect(status().isOk());

        assertTrue(userRepository.findById(target.getId()).orElseThrow().isMustChangePassword());
    }

    // ==================== 3. 自救闭环（最关键） ====================

    @Test
    @DisplayName("自救闭环：被拦 → 改密 → 标记清除 → 恢复自由访问")
    void userCanEscapeByChangingPassword() throws Exception {
        User user = newUser(USER_EMAIL, User.UserRole.USER, true);
        String token = tokenFor(user);

        // 1) 改密前：资产端点被拦
        assertPasswordChangeRequired("GET", "/api/account/licenses", token);

        // 2) 改密：旧密码用管理员代设的那个
        mockMvc.perform(post("/api/account/password/change")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"" + ADMIN_SET_PASSWORD + "\","
                    + "\"newPassword\":\"" + USER_SELF_PASSWORD + "\"}"))
            .andExpect(status().isOk());

        assertFalse(userRepository.findById(user.getId()).orElseThrow().isMustChangePassword(),
            "改密后必须清除强制改密标记，否则用户仍被困");

        // 3) 改密令旧令牌失效，须重新登录；新令牌可自由访问
        mockMvc.perform(get("/api/account/licenses").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());

        String freshToken = tokenFor(user);
        mockMvc.perform(get("/api/account/licenses").header("Authorization", "Bearer " + freshToken))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + freshToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    // ==================== 4. 不误伤：匿名与静态资源 ====================

    @Test
    @DisplayName("匿名请求不受影响：返回 401 而非 PASSWORD_CHANGE_REQUIRED")
    void anonymousRequestStillUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/account/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("静态页与执行器端点照常可达（含置位用户带令牌访问）")
    void staticResourcesAreNotAffected() throws Exception {
        User user = newUser(USER_EMAIL, User.UserRole.USER, true);
        String token = tokenFor(user);

        mockMvc.perform(get("/admin/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/admin.js").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());

        // 执行器端点不是本过滤器的管辖范围：这里只断言「没被强制改密逻辑拦下」，
        // 不断言健康状态（测试环境下健康聚合可能 DOWN，与强制改密无关）。
        String healthBody = mockMvc.perform(get("/actuator/health"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(healthBody.contains("PASSWORD_CHANGE_REQUIRED"),
            "非 API 路径不得被强制改密逻辑处理：" + healthBody);
    }

    @Test
    @DisplayName("标记未置位的用户一切正常")
    void userWithoutFlagIsUnaffected() throws Exception {
        User user = newUser(USER_EMAIL, User.UserRole.USER, false);
        String token = tokenFor(user);

        mockMvc.perform(get("/api/account/licenses").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    @Test
    @DisplayName("不存在的 userId 不得被误放行（过滤器只认库里的标记）")
    void unknownUserTokenHasNoIdentity() throws Exception {
        String tokenForGhost = jwtTokenService.issue(
            UUID.fromString("99999999-9999-9999-9999-999999999999"), 0);
        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + tokenForGhost))
            .andExpect(status().isUnauthorized());
    }
}
