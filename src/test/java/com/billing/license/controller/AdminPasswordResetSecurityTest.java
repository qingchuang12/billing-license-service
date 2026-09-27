package com.billing.license.controller;

import com.billing.license.entity.AuditLog;
import com.billing.license.entity.User;
import com.billing.license.repository.AuditLogRepository;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员代重置密码的<b>安全口径证伪</b>（QA 独立复核，plan-7.0 P0 / T02）。
 *
 * <p>重点不是「能不能重置成功」，而是三条不能破的红线：
 * <ol>
 *   <li><b>密码不得出现在响应体任何位置</b>——成功态、失败态（含校验错误文案）都不行；</li>
 *   <li><b>tokenVersion+1 必须真的让旧 JWT 失效</b>——不能只改了哈希而旧会话还在；</li>
 *   <li><b>审计留痕不含密码</b>——审计是长期留存物，泄漏半径比日志大得多。</li>
 * </ol>
 * 另外补齐边界：无数字 / 过短 / 超长密码必须被拒且库里不得发生任何变更。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminPasswordResetSecurityTest {

    private static final String ADMIN_EMAIL = "qa-reset-admin@example.com";
    private static final String USER_EMAIL = "qa-reset-user@example.com";
    private static final String OLD_PASSWORD = "OldPassw0rd";
    private static final String NEW_PASSWORD = "NewPassw0rd";
    /** 72 字符（BCrypt 上限内）：字母 + 数字 */
    private static final String BOUNDARY_OK_PASSWORD = repeat("a1", 36);
    /** 73 字符：越过 BCrypt 72 字节上限，必须被拒 */
    private static final String TOO_LONG_PASSWORD = repeat("a1", 36) + "b";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private User admin;
    private User target;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
        userRepository.deleteAll();
        auditLogRepository.deleteAll();
        admin = userRepository.save(User.builder()
            .email(ADMIN_EMAIL).passwordHash(passwordEncoder.encode(OLD_PASSWORD))
            .role(User.UserRole.ADMIN).status(User.UserStatus.ACTIVE).build());
        target = userRepository.save(User.builder()
            .email(USER_EMAIL).passwordHash(passwordEncoder.encode(OLD_PASSWORD))
            .role(User.UserRole.USER).status(User.UserStatus.ACTIVE).build());
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }

    private String tokenFor(User user) {
        User fresh = userRepository.findById(user.getId()).orElseThrow();
        return jwtTokenService.issue(fresh.getId(), fresh.getTokenVersion());
    }

    private String resetBody(String password) {
        return "{\"newPassword\":\"" + password + "\"}";
    }

    /** 审计是 @Async 独立事务落库，轮询等待（最多 3 秒）。 */
    private List<AuditLog> awaitAuditLogs(String action) {
        for (int i = 0; i < 60; i++) {
            List<AuditLog> logs = auditLogRepository.findAll().stream()
                .filter(l -> action.equals(l.getAction()))
                .toList();
            if (!logs.isEmpty()) {
                return logs;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return List.of();
    }

    // ==================== 1. 令牌失效与强制改密 ====================

    @Test
    @DisplayName("代重置后旧 JWT 立即失效（不是等自然过期）")
    void oldTokenIsInvalidatedImmediately() throws Exception {
        String oldToken = tokenFor(target);
        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isOk());

        assertEquals(1, userRepository.findById(target.getId()).orElseThrow().getTokenVersion());
        mockMvc.perform(get("/api/account/me").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("代重置后新密码可用、旧密码不可用、强制改密置位")
    void newPasswordTakesEffectAndFlagIsSet() throws Exception {
        String oldHash = target.getPasswordHash();
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isOk());

        User after = userRepository.findById(target.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, after.getPasswordHash()), "新密码须已生效");
        assertFalse(passwordEncoder.matches(OLD_PASSWORD, after.getPasswordHash()), "旧密码须失效");
        assertNotEquals(oldHash, after.getPasswordHash());
        assertTrue(after.isMustChangePassword(), "代重置后须强制改密");
    }

    @Test
    @DisplayName("被代重置的用户登录后立刻被拦（前端弹窗之外仍有服务端约束）")
    void resetUserIsBlockedAfterLogin() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isOk());

        String freshToken = tokenFor(target);
        mockMvc.perform(get("/api/account/licenses").header("Authorization", "Bearer " + freshToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // ==================== 2. 响应体不得出现密码 ====================

    @Test
    @DisplayName("成功响应体不回显密码")
    void successResponseBodyHasNoPassword() throws Exception {
        String body = mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(NEW_PASSWORD), "响应体不得出现新密码：" + body);
        assertFalse(body.contains("passwordHash"), "响应体不得出现密码哈希字段：" + body);
    }

    @Test
    @DisplayName("失败响应体同样不得回显密码（弱密码 / 自身 / 用户不存在）")
    void failureResponseBodyHasNoPassword() throws Exception {
        String adminToken = tokenFor(admin);

        // 无数字
        String noDigit = mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody("PasswordOnly")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(noDigit.contains("PasswordOnly"), "失败响应不得回显密码：" + noDigit);

        // 过短（7 位，越过 @Size(min=8)）
        String tooShort = mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody("Abc12")))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertFalse(tooShort.contains("Abc12"), "失败响应不得回显密码：" + tooShort);

        // 超长（73 字符）
        String tooLong = mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(TOO_LONG_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertFalse(tooLong.contains(TOO_LONG_PASSWORD), "失败响应不得回显密码：" + tooLong);

        // 空值
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"\"}"))
            .andExpect(status().isBadRequest());

        // 库里必须纹丝未动
        User unchanged = userRepository.findById(target.getId()).orElseThrow();
        assertEquals(0, unchanged.getTokenVersion(), "失败路径不得递增令牌版本");
        assertFalse(unchanged.isMustChangePassword(), "失败路径不得置强制改密标记");
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, unchanged.getPasswordHash()), "失败路径不得改哈希");
    }

    @Test
    @DisplayName("72 字符边界内应被接受（上下限口径正确）")
    void boundaryLengthPasswordIsAccepted() throws Exception {
        assertEquals(72, BOUNDARY_OK_PASSWORD.length());
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(BOUNDARY_OK_PASSWORD)))
            .andExpect(status().isOk());
        assertTrue(passwordEncoder.matches(BOUNDARY_OK_PASSWORD,
            userRepository.findById(target.getId()).orElseThrow().getPasswordHash()));
    }

    // ==================== 3. 审计留痕 ====================

    @Test
    @DisplayName("审计落 ADMIN_RESET_USER_PASSWORD，且 detail 不含密码")
    void auditLogIsWrittenWithoutPassword() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isOk());

        // 按「本次目标用户」定位，避免上一用例的异步落库记录混入
        List<AuditLog> logs = awaitAuditLogs("ADMIN_RESET_USER_PASSWORD").stream()
            .filter(l -> target.getId().toString().equals(l.getTarget()))
            .toList();
        assertFalse(logs.isEmpty(), "必须落一条 ADMIN_RESET_USER_PASSWORD 审计");

        AuditLog log = logs.get(0);
        assertEquals(admin.getId().toString(), log.getActor(), "审计 actor 应为操作者");
        assertEquals(target.getId().toString(), log.getTarget(), "审计 target 应为目标用户");
        assertTrue(log.isSuccess(), "成功态审计须记 success=true");
        assertFalse(log.getDetail() != null && log.getDetail().contains(NEW_PASSWORD),
            "审计 detail 绝不能含密码：" + log.getDetail());
    }

    @Test
    @DisplayName("审计表中不存在任何含明码的记录（全表兜底扫描）")
    void noAuditRecordContainsPlainPassword() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isOk());

        // 失败路径也各来一条，覆盖「失败态 detail 拼接异常消息」的分支
        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody("PasswordOnly")))
            .andExpect(status().isBadRequest());

        for (int i = 0; i < 60 && auditLogRepository.count() < 2; i++) {
            Thread.sleep(50);
        }
        List<AuditLog> all = auditLogRepository.findAll();
        assertFalse(all.isEmpty());
        for (AuditLog l : all) {
            assertFalse(l.getDetail() != null && l.getDetail().contains(NEW_PASSWORD),
                "审计记录含明码：action=" + l.getAction() + ", detail=" + l.getDetail());
            assertFalse(l.getTarget() != null && l.getTarget().contains(NEW_PASSWORD),
                "审计 target 含明码：action=" + l.getAction());
        }
    }

    // ==================== 4. 护栏与错误码 ====================

    @Test
    @DisplayName("对自己重置 / 不存在的用户被拒，且响应不回显密码")
    void guardsRejectSelfAndUnknownUser() throws Exception {
        String adminToken = tokenFor(admin);

        String self = mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/password/reset")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SELF_OPERATION_NOT_ALLOWED"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(self.contains(NEW_PASSWORD));

        String unknown = mockMvc.perform(post("/api/admin/users/99999999-9999-9999-9999-999999999999"
                + "/password/reset")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(unknown.contains(NEW_PASSWORD));

        // 操作者自身不得被误伤
        User adminAfter = userRepository.findById(admin.getId()).orElseThrow();
        assertEquals(0, adminAfter.getTokenVersion());
        assertFalse(adminAfter.isMustChangePassword());
    }

    @Test
    @DisplayName("非管理员不得代重置（403，且服务层未被执行）")
    void nonAdminCannotReset() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/password/reset")
                .header("Authorization", "Bearer " + tokenFor(target))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(NEW_PASSWORD)))
            .andExpect(status().isForbidden());

        User adminAfter = userRepository.findById(admin.getId()).orElseThrow();
        assertEquals(0, adminAfter.getTokenVersion());
        assertFalse(adminAfter.isMustChangePassword());
    }
}
