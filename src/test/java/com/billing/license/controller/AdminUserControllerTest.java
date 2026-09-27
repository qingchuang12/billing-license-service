package com.billing.license.controller;

import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.JwtTokenService;
import com.billing.license.security.MfaSecretCipher;
import com.billing.license.security.TotpService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端用户接口契约 / 鉴权测试（plan-7.0 账户基础功能）。
 *
 * <p>用真实安全过滤器链（非 standalone MockMvc）验证两件事：
 * <ol>
 *   <li>新增端点沿用既有 {@code /api/admin/**} 规则——普通用户（ROLE_USER）访问返回 403，未登录返回 401；</li>
 *   <li>管理员可用：列表分页返回统一壳，代重置成功响应<b>不回显任何密码</b>且旧令牌失效、强制改密置位。</li>
 * </ol>
 * 本主题不新增 URL 安全规则，故这里断言的是既有规则对新端点同样生效。
 *
 * <p><b>为什么手动装配 MockMvc</b>：本机离线仓库缺 {@code spring-boot-webmvc-test}
 * （Boot 4 把 {@code @AutoConfigureMockMvc} 迁出 spring-boot-test-autoconfigure），
 * 故用 {@code webAppContextSetup + 真实安全过滤器链} 达成同样的「走完整过滤器链」效果。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminUserControllerTest {

    private static final String ADMIN_EMAIL = "admin-contract@example.com";
    private static final String USER_EMAIL = "user-contract@example.com";
    private static final String NEW_PASSWORD = "NewPassw0rd";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MfaSecretCipher mfaSecretCipher;
    @Autowired private TotpService totpService;

    private MockMvc mockMvc;
    private User admin;
    private User normalUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
        userRepository.deleteAll();
        admin = userRepository.save(User.builder()
            .email(ADMIN_EMAIL).passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE).build());
        normalUser = userRepository.save(User.builder()
            .email(USER_EMAIL).passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.USER).status(User.UserStatus.ACTIVE).build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    private String tokenFor(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        return jwtTokenService.issue(fresh.getId(), fresh.getTokenVersion());
    }

    @Test
    void listUsers_nonAdmin_shouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + tokenFor(normalUser)))
            .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_unauthenticated_shouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_nonAdmin_shouldBeForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + normalUser.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(normalUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_admin_shouldReturnPagedEnvelope() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                .param("email", "contract")
                .param("role", "USER")
                .param("status", "ACTIVE")
                .param("page", "0")
                .param("size", "20")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("SUCCESS"))
            .andExpect(jsonPath("$.data.content[0].email").value(USER_EMAIL))
            .andExpect(jsonPath("$.data.content[0].role").value("USER"))
            // 安全红线：视图不得出现密码哈希、令牌版本与 MFA 密钥
            .andExpect(jsonPath("$.data.content[0].passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].tokenVersion").doesNotExist())
            .andExpect(jsonPath("$.data.content[0].mfaSecret").doesNotExist())
            .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void listUsers_invalidRole_shouldReturnBusinessCode() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                .param("role", "BOSS")
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("INVALID_USER_ROLE"));
    }

    /** 代重置：响应体绝不能出现密码，且同一事务内完成「换哈希 + 令牌失效 + 强制改密」。 */
    @Test
    void resetPassword_admin_shouldSucceedWithoutEchoingPassword() throws Exception {
        String oldHash = normalUser.getPasswordHash();

        // 成功态无响应体（既无一次性密码可回显，也没有任何需要回传的业务对象）
        String body = mockMvc.perform(post("/api/admin/users/" + normalUser.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(NEW_PASSWORD), "响应体不得回显任何密码");

        User reset = userRepository.findById(normalUser.getId()).orElseThrow();
        assertTrue(reset.isMustChangePassword(), "代重置后须强制改密");
        assertEquals(1, reset.getTokenVersion(), "旧会话须立即失效");
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, reset.getPasswordHash()), "新密码须已生效");
        assertFalse(oldHash.equals(reset.getPasswordHash()), "密码哈希必须已更换");
    }

    /** 管理员不能对自己代重置（体验层禁用只是提示，服务端才是安全边界）。 */
    @Test
    void resetPassword_self_shouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SELF_OPERATION_NOT_ALLOWED"));
    }

    @Test
    void resetPassword_unknownUser_shouldReturnUserNotFound() throws Exception {
        UUID unknown = UUID.fromString("99999999-9999-9999-9999-999999999999");
        mockMvc.perform(post("/api/admin/users/" + unknown + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    /** P1 详情：脱敏资料 + 计数聚合；视图不得出现密码哈希 / 令牌版本。 */
    @Test
    void getUserDetail_admin_shouldReturnProfileAndCounts() throws Exception {
        mockMvc.perform(get("/api/admin/users/" + normalUser.getId())
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.user.email").value(USER_EMAIL))
            .andExpect(jsonPath("$.data.user.role").value("USER"))
            .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.user.tokenVersion").doesNotExist())
            .andExpect(jsonPath("$.data.licenseCount").value(0))
            .andExpect(jsonPath("$.data.orderCount").value(0));
    }

    @Test
    void getUserDetail_nonAdmin_shouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/users/" + admin.getId())
                .header("Authorization", "Bearer " + tokenFor(normalUser)))
            .andExpect(status().isForbidden());
    }

    @Test
    void getUserDetail_unknownUser_shouldReturnUserNotFound() throws Exception {
        UUID unknown = UUID.fromString("88888888-8888-8888-8888-888888888888");
        mockMvc.perform(get("/api/admin/users/" + unknown)
                .header("Authorization", "Bearer " + tokenFor(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    /**
     * P2 step-up 集成：操作者已开启 MFA 时，敏感动作不带 X-Step-Up-Token 必须被拒
     * （服务端约束，前端弹窗只是引导）。走真实过滤器链与真实 AdminStepUpService。
     */
    @Test
    void resetPassword_mfaAdmin_withoutStepUpToken_shouldRequireStepUp() throws Exception {
        User mfaAdmin = userRepository.save(User.builder()
            .email("mfa-admin@example.com").passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE).build());
        mfaAdmin.setMfaEnabled(true);
        mfaAdmin.setMfaSecretCipher(mfaSecretCipher.encrypt(totpService.generateSecret()));
        userRepository.save(mfaAdmin);

        mockMvc.perform(post("/api/admin/users/" + normalUser.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(mfaAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));

        User after = userRepository.findById(normalUser.getId()).orElseThrow();
        assertEquals(0, after.getTokenVersion(), "被拒后目标用户不得有任何变更");
    }
}
