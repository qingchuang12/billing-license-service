package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.UserProfileResponse;
import com.billing.license.entity.User;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.JwtTokenService;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock private VerificationCodeService verificationCodeService;
    @Mock private RateLimitService rateLimitService;

    private AccountProperties properties;
    private AccountService service;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        service = new AccountService(userRepository, passwordEncoder, jwtTokenService,
            verificationCodeService, rateLimitService, properties);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(passwordEncoder.encode(anyString())).thenReturn("BCRYPT_HASH");
        when(jwtTokenService.issue(any(UUID.class), anyInt())).thenReturn("jwt-token");
        when(jwtTokenService.expiresInSeconds()).thenReturn(604800L);
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

        User locked = activeUser();
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(locked));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.login(EMAIL, "wrong", "1.2.3.4"));
        assertEquals("ACCOUNT_LOCKED", ex.getErrorCode());
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
