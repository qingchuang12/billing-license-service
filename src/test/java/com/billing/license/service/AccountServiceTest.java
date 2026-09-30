package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.UserProfileResponse;
import com.billing.license.entity.User;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.DbUserDetailsService;
import com.billing.license.security.JwtTokenService;
import com.billing.license.security.MfaTicketService;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AccountService} 单元测试。
 *
 * <p>覆盖：注册（重复邮箱 / 弱密码 / 成功路径的邮箱验证）、登录（错误凭据统一文案 / 失败计数 / 锁定 / 停用）、
 * 登出与改密的 {@code tokenVersion} 递增。IP 级限流走 mock，账号级锁定断言落库字段。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "Passw0rd2026";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private MfaTicketService mfaTicketService;
    @Mock private VerificationCodeService verificationCodeService;
    @Mock private RateLimitService rateLimitService;
    @Mock private EmailNotificationService emailNotificationService;

    private AccountProperties properties;
    private AccountService service;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(passwordEncoder.encode(anyString())).thenReturn("BCRYPT_HASH");
        when(jwtTokenService.issue(any(UUID.class), anyInt(), anyBoolean())).thenReturn("jwt-token");
        when(jwtTokenService.expiresInSeconds(anyBoolean())).thenReturn(604800L);

        // 登录已融合 Spring Security：用真实认证链（DaoAuthenticationProvider + ProviderManager），
        // 仅 mock 数据源（userRepository）与密码器（passwordEncoder）。密码比对、锁定/停用预检
        // 由真实 provider 执行，故下方各登录用例的断言与改造前完全一致。
        UserDetailsService userDetailsService = new DbUserDetailsService(userRepository);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        AuthenticationManager authenticationManager = new ProviderManager(provider);

        service = new AccountService(userRepository, passwordEncoder, jwtTokenService,
            mfaTicketService, verificationCodeService, rateLimitService, authenticationManager,
            emailNotificationService, properties);
    }

    @Test
    void register_emailAlreadyRegistered_rejected() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.register(EMAIL, PASSWORD, "123456", "1.2.3.4"));
        assertEquals("EMAIL_ALREADY_REGISTERED", ex.getErrorCode());
    }

    @Test
    void register_weakPassword_rejected() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.register(EMAIL, "short", "123456", "1.2.3.4"));
        assertEquals("PASSWORD_POLICY_VIOLATION", ex.getErrorCode());
    }

    @Test
    void register_missingEmailCode_rejectedWhenRequired() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.register(EMAIL, PASSWORD, "", "1.2.3.4"));
        assertEquals("EMAIL_CODE_REQUIRED", ex.getErrorCode());
    }

    @Test
    void register_success_verifiesCodeAndReturnsToken() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

        AuthResponse resp = service.register(EMAIL, PASSWORD, "123456", "1.2.3.4");

        verify(verificationCodeService).verifyAndConsume(EMAIL, VerificationCode.CodePurpose.REGISTER, "123456");
        assertEquals("jwt-token", resp.getAccessToken());
        assertEquals(604800L, resp.getExpiresIn());
        assertTrue(resp.getUser().isEmailVerified(), "验证码校验通过后应标记为已验证");
        assertEquals(EMAIL, resp.getUser().getEmail());
    }

    @Test
    void login_wrongPassword_countsFailureAndHidesReason() {
        User user = activeUser();
        user.setFailedLoginCount(0);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "BCRYPT_HASH")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.login(EMAIL, "wrong", "1.2.3.4"));

        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode(), "不得区分「邮箱不存在」与「密码错误」");
        assertEquals(1, user.getFailedLoginCount());
    }

    @Test
    void login_unknownEmail_countsIpFailureWithoutRevealingExistence() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.login(EMAIL, PASSWORD, "1.2.3.4"));

        assertEquals("INVALID_CREDENTIALS", ex.getErrorCode(), "邮箱不存在也须返回统一文案");
        verify(rateLimitService).countOnly(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void login_reachingThreshold_locksAccount() {
        User user = activeUser();
        user.setFailedLoginCount(properties.getRisk().getLoginAccountFailMax() - 1);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.login(EMAIL, "wrong", "1.2.3.4"));
        assertNotNull(user.getLockedUntil(), "达到阈值须锁定账号");
        // SEC-5：锁定触发时向被锁账号邮箱发旁路提醒，锁定分钟数随文案下发
        verify(emailNotificationService).sendAccountLockedEmail(
            EMAIL, properties.getRisk().getLoginAccountLockMinutes());

        User locked = activeUser();
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(locked));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.login(EMAIL, "wrong", "1.2.3.4"));
        assertEquals("ACCOUNT_LOCKED", ex.getErrorCode());
        // 已锁定账号的后续尝试在认证链即被拦（LockedException），不再进入 recordLoginFailure，
        // 故锁定提醒每次锁定事件只发一封——此处累计仍为 1 次。
        verify(emailNotificationService, times(1)).sendAccountLockedEmail(anyString(), anyInt());
    }

    /**
     * SEC-5 旁路容错：锁定提醒邮件即使发送抛异常，也<b>绝不能</b>回滚或阻断锁定本身
     * （锁定是安全动作，邮件只是通知）。{@code sendAccountLockedEmail} 为 {@code @Async}
     * 且内部吞异常，这里用 mock 抛出来模拟「通知链路故障」，断言账号仍被锁定、登录仍按预期失败。
     */
    @Test
    void login_lockEmailFailure_doesNotBlockLock() {
        User user = activeUser();
        user.setFailedLoginCount(properties.getRisk().getLoginAccountFailMax() - 1);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("SMTP down"))
            .when(emailNotificationService).sendAccountLockedEmail(anyString(), anyInt());

        assertThrows(BusinessException.class, () -> service.login(EMAIL, "wrong", "1.2.3.4"));
        assertNotNull(user.getLockedUntil(), "邮件发送失败也必须照常锁定账号");
    }

    @Test
    void login_success_clearsFailureState() {
        User user = activeUser();
        user.setFailedLoginCount(3);
        // 锁定已过期：isLocked() 为 false，登录成功后应连同 lockedUntil 一并清零
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);

        AuthResponse resp = service.login(EMAIL, PASSWORD, "1.2.3.4");

        assertEquals(0, user.getFailedLoginCount());
        assertNull(user.getLockedUntil());
        assertNotNull(user.getLastLoginAt());
        assertEquals("jwt-token", resp.getAccessToken());
    }

    /**
     * SEC-3：登录签发的令牌 TTL 必须按角色分叉——ADMIN 走短 TTL（issue/expiresIn 传 true），
     * 消费者走长 TTL（传 false）。这里只验「路由正确的布尔量」，具体 TTL 值由
     * {@code JwtTokenServiceTtlTest} 用真实签发验签覆盖。
     */
    @Test
    void login_adminRole_issuesShortTtlToken() {
        User admin = activeUser();
        admin.setRole(User.UserRole.ADMIN);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);

        service.login(EMAIL, PASSWORD, "1.2.3.4");

        verify(jwtTokenService).issue(eq(admin.getId()), anyInt(), eq(true));
        verify(jwtTokenService).expiresInSeconds(true);
    }

    @Test
    void login_consumerRole_issuesLongTtlToken() {
        User user = activeUser();
        user.setRole(User.UserRole.USER);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);

        service.login(EMAIL, PASSWORD, "1.2.3.4");

        verify(jwtTokenService).issue(eq(user.getId()), anyInt(), eq(false));
        verify(jwtTokenService).expiresInSeconds(false);
    }

    @Test
    void login_disabledAccount_rejected() {
        User user = activeUser();
        user.setStatus(User.UserStatus.DISABLED);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.login(EMAIL, PASSWORD, "1.2.3.4"));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
    }

    @Test
    void logout_bumpsTokenVersion() {
        User user = activeUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.logout(user.getId());

        assertEquals(1, user.getTokenVersion(), "登出须使所有旧令牌失效");
    }

    @Test
    void changePassword_wrongOldPassword_rejected() {
        User user = activeUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "BCRYPT_HASH")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changePassword(user.getId(), "wrong", "NewPassw0rd2026"));
        assertEquals("OLD_PASSWORD_MISMATCH", ex.getErrorCode());
    }

    @Test
    void changePassword_success_bumpsTokenVersion() {
        User user = activeUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);
        when(passwordEncoder.matches("NewPassw0rd2026", "BCRYPT_HASH")).thenReturn(false);

        service.changePassword(user.getId(), PASSWORD, "NewPassw0rd2026");

        assertEquals("BCRYPT_HASH", user.getPasswordHash());
        assertEquals(1, user.getTokenVersion(), "改密后须强制重新登录");
    }

    /**
     * plan-7.0 / Q3：已登录改密须<b>清除</b>「强制改密」标记——否则用户改完密码仍会被
     * {@code MustChangePasswordFilter} 挡在其他功能之外（半完成态）。
     */
    @Test
    void changePassword_clearsMustChangePasswordFlag() {
        User user = activeUser();
        user.setMustChangePassword(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);
        when(passwordEncoder.matches("NewPassw0rd2026", "BCRYPT_HASH")).thenReturn(false);

        service.changePassword(user.getId(), PASSWORD, "NewPassw0rd2026");

        assertFalse(user.isMustChangePassword(), "改密后须清除强制改密标记");
    }

    /** Q3 的另一条清除路径：邮箱验证码自助找回（用户自己走完流程即视为已重新掌握密码）。 */
    @Test
    void resetPassword_clearsMustChangePasswordFlag() {
        User user = activeUser();
        user.setMustChangePassword(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(false);

        service.resetPassword(EMAIL, "123456", PASSWORD, "1.2.3.4");

        assertFalse(user.isMustChangePassword(), "自助找回后须清除强制改密标记");
    }

    /**
     * plan-7.0 / P1：改密成功后发送安全提醒。<b>只告知发生过变更</b>——
     * 断言调用参数里只有邮箱与场景，方法签名本身就不含密码（编译期保证）。
     */
    @Test
    void changePassword_sendsPasswordChangedNotification() {
        User user = activeUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);
        when(passwordEncoder.matches("NewPassw0rd2026", "BCRYPT_HASH")).thenReturn(false);

        service.changePassword(user.getId(), PASSWORD, "NewPassw0rd2026");

        verify(emailNotificationService).sendPasswordChangedEmail(EMAIL, "SELF_CHANGE");
    }

    @Test
    void resetPassword_sendsPasswordChangedNotification() {
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(false);

        service.resetPassword(EMAIL, "123456", PASSWORD, "1.2.3.4");

        verify(emailNotificationService).sendPasswordChangedEmail(EMAIL, "SELF_RESET");
    }

    /** 防枚举的静默路径不得发通知：那等于告诉攻击者「这个邮箱存在」。 */
    @Test
    void resetPassword_emailWithoutAccount_sendsNoNotification() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.resetPassword(EMAIL, "123456", PASSWORD, "1.2.3.4"));

        verify(emailNotificationService, never()).sendPasswordChangedEmail(anyString(), anyString());
    }

    @Test
    void me_returnsProfile() {
        User user = activeUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UserProfileResponse profile = service.me(user.getId());

        assertEquals(user.getId(), profile.getId());
        assertEquals(EMAIL, profile.getEmail());
        assertEquals("ACTIVE", profile.getStatus());
    }

    /**
     * B6（2026-09-20，账户枚举修复）：保留注册端点后，账号可在购买/兑换外单独创建，
     * 原「查无账户抛 EMAIL_NOT_PURCHASED」既语义不再成立、又构成账户枚举神谕（暴露邮箱是否注册/付费）。
     * 现改为：查无账户时静默成功返回（不抛异常、不改库），与成功路径同响应，攻击者无法凭返回值区分。
     */
    @Test
    void resetPassword_emailWithoutAccount_silentlySucceedsWithoutEnumerationLeak() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        // 不抛任何异常（防枚举：与"密码已重置"同响应）
        assertDoesNotThrow(() -> service.resetPassword(EMAIL, "123456", PASSWORD, "1.2.3.4"));

        // 验证码仍被消费（上一步），但绝不落库任何用户变更
        verify(userRepository, never()).save(any(User.class));
    }

    /** 已存在账号（购买时创建）的正常重置路径：密码更新 + 旧令牌全部失效 */
    @Test
    void resetPassword_existingAccount_updatesPasswordAndBumpsTokenVersion() {
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(false);

        service.resetPassword(EMAIL, "123456", PASSWORD, "1.2.3.4");

        assertEquals("BCRYPT_HASH", user.getPasswordHash());
        assertEquals(1, user.getTokenVersion(), "重置密码须使该用户所有旧令牌失效");
        verify(verificationCodeService).verifyAndConsume(EMAIL, VerificationCode.CodePurpose.RESET_PASSWORD, "123456");
    }

    /**
     * plan-7.0 / M3（B8）：启用二次因子的账号，登录第一步<b>只发票据不发令牌</b>。
     * 这是「MFA 不可被绕过」的服务端守门点——若此处仍签发令牌，管理台 UI 上加多少步输入都没用。
     */
    @Test
    void login_shouldReturnTicketInsteadOfToken_whenMfaEnabled() {
        User user = activeUser();
        user.setMfaEnabled(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);
        when(mfaTicketService.issue(any(UUID.class), anyInt())).thenReturn("mfa-ticket");

        AuthResponse resp = service.login(EMAIL, PASSWORD, "1.2.3.4");

        assertTrue(resp.isMfaRequired(), "启用 MFA 的账号须返回「需第二因子」");
        assertEquals("mfa-ticket", resp.getMfaTicket());
        assertEquals(List.of("TOTP", "EMAIL"), resp.getMfaMethods());
        assertNull(resp.getAccessToken(), "第二因子未过前绝不得签发令牌");
        assertNull(resp.getUser(), "第二因子未过前不得回显账号信息");
        assertNull(user.getLastLoginAt(), "登录尚未完成，不应更新最近登录时间");
    }

    @Test
    void login_shouldReturnPlainToken_whenMfaDisabled() {
        User user = activeUser();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);

        AuthResponse resp = service.login(EMAIL, PASSWORD, "1.2.3.4");

        assertFalse(resp.isMfaRequired(), "未启用 MFA 的账号行为必须与改造前完全一致");
        assertEquals("jwt-token", resp.getAccessToken());
        assertNotNull(resp.getUser());
        assertNotNull(user.getLastLoginAt());
    }

    @Test
    void login_shouldOmitEmailMethod_whenEmailFallbackDisabled() {
        User user = activeUser();
        user.setMfaEnabled(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(PASSWORD, "BCRYPT_HASH")).thenReturn(true);
        when(mfaTicketService.issue(any(UUID.class), anyInt())).thenReturn("mfa-ticket");
        properties.getMfa().setEmailFallbackEnabled(false);

        AuthResponse resp = service.login(EMAIL, PASSWORD, "1.2.3.4");

        assertEquals(List.of("TOTP"), resp.getMfaMethods(),
            "邮箱兜底关闭时不得下发 EMAIL，避免客户端引导用户走服务端不接受的路径");
    }

    private User activeUser() {
        return User.builder()
            .id(UUID.randomUUID())
            .email(EMAIL)
            .passwordHash("BCRYPT_HASH")
            .status(User.UserStatus.ACTIVE)
            .emailVerified(true)
            .tokenVersion(0)
            .failedLoginCount(0)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
