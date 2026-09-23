package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.MfaEnrollResponse;
import com.billing.license.dto.MfaStatusResponse;
import com.billing.license.entity.User;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.*;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.*;

/**
 * {@link MfaService} 单元测试（plan-7.0 / M6）。
 *
 * <p><b>刻意使用真实的密码学组件</b>（{@link TotpService} / {@link MfaSecretCipher} /
 * {@link MfaTicketService} / {@link JwtTokenService}），只 mock 仓储、限流与口令编码器。
 * 因为这些组件正是安全性的所在——把它们 mock 掉，测试就只能验证「调用序列」，
 * 无法发现「密钥加密写成了明文」「票据与令牌同密钥」这类真正致命的问题。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MfaServiceTest {

    private static final String MASTER_KEY = "mfa-master-key-for-unit-tests-0123456789abcdef";
    private static final String JWT_SECRET = "jwt-secret-for-unit-tests-0123456789abcdefghijkl";
    private static final String PASSWORD = "Passw0rd2026";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private VerificationCodeService verificationCodeService;
    @Mock private RateLimitService rateLimitService;

    private AccountProperties properties;
    private TotpService totpService;
    private MfaSecretCipher secretCipher;
    private MfaTicketService ticketService;
    private JwtTokenService jwtTokenService;
    private MfaService service;
    private User admin;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        properties.setJwtSecret(JWT_SECRET);
        properties.getMfa().setKey(MASTER_KEY);

        MfaKeyDeriver keyDeriver = new MfaKeyDeriver(properties);
        totpService = new TotpService(properties);
        secretCipher = new MfaSecretCipher(keyDeriver);
        ticketService = new MfaTicketService(properties, keyDeriver);
        jwtTokenService = new JwtTokenService(properties);
        service = new MfaService(userRepository, passwordEncoder, jwtTokenService, ticketService,
            totpService, secretCipher, verificationCodeService, rateLimitService, properties);

        admin = User.builder()
            .id(UUID.randomUUID())
            .email("admin@example.com")
            .passwordHash("BCRYPT_HASH")
            .role(User.UserRole.ADMIN)
            .status(User.UserStatus.ACTIVE)
            .tokenVersion(0)
            .build();
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.matches(eq(PASSWORD), anyString())).thenReturn(true);
    }

    // ==================== 状态查询 ====================

    @Test
    @DisplayName("状态查询：未启用时 enabled=false，且回显邮箱兜底开关")
    void status_shouldReportDisabledByDefault() {
        MfaStatusResponse status = service.status(admin.getId());

        assertFalse(status.isEnabled());
        assertNull(status.getEnrolledAt());
        assertTrue(status.isEmailFallbackEnabled(), "默认开启邮箱兜底（B8 定案）");
    }

    // ==================== 生成密钥 ====================

    @Test
    @DisplayName("生成密钥：密钥须加密落库、且仍处于未启用状态")
    void enroll_shouldStoreEncryptedSecretAndStayDisabled() {
        MfaEnrollResponse response = service.enroll(admin.getId(), PASSWORD);

        assertNotNull(response.getSecret());
        assertFalse(response.isActivated(), "生成密钥不等于启用，须再用动态码激活");
        assertTrue(response.getOtpauthUri().startsWith("otpauth://totp/"));

        assertFalse(admin.isMfaEnabled(), "仅生成密钥不得启用 MFA");
        assertNull(admin.getMfaEnrolledAt());
        // 落库的必须是密文：既不能等于明文，也要能用当前主密钥解回同一个密钥
        assertNotNull(admin.getMfaSecretCipher());
        assertFalse(admin.getMfaSecretCipher().contains(response.getSecret()),
            "TOTP 密钥明文落库——读库者即可永久生成有效动态码");
        assertEquals(response.getSecret(), secretCipher.decrypt(admin.getMfaSecretCipher()));
    }

    @Test
    @DisplayName("已启用时拒绝再次生成密钥（避免静默换掉密钥把管理员锁在门外）")
    void enroll_shouldRejectWhenAlreadyEnabled() {
        admin.setMfaEnabled(true);
        admin.setMfaSecretCipher("whatever");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.enroll(admin.getId(), PASSWORD));

        assertEquals("MFA_ALREADY_ENABLED", ex.getErrorCode());
    }

    @Test
    @DisplayName("生成密钥须校验当前口令（防令牌泄漏后被静默绑定攻击者认证器）")
    void enroll_shouldRequireCorrectPassword() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.enroll(admin.getId(), "wrong-password"));

        assertEquals("OLD_PASSWORD_MISMATCH", ex.getErrorCode());
        assertNull(admin.getMfaSecretCipher(), "口令错误时不得落库任何密钥");
        verify(rateLimitService).countOnly(anyString(), anyString(), anyInt(), anyInt());
    }

    // ==================== 激活 ====================

    @Test
    @DisplayName("激活：动态码正确则启用，并记录已用时间步（防同码复用）")
    void activate_shouldEnableWhenCodeMatches() {
        String secret = service.enroll(admin.getId(), PASSWORD).getSecret();
        String code = totpService.code(secret, totpService.currentStep());

        service.activate(admin.getId(), code);

        assertTrue(admin.isMfaEnabled());
        assertNotNull(admin.getMfaEnrolledAt());
        assertNotNull(admin.getMfaLastUsedStep(), "须记录已用时间步，否则同一码可在窗口内重放");
    }

    @Test
    @DisplayName("激活：动态码错误则拒绝并计入失败次数，且不启用")
    void activate_shouldRejectWrongCodeAndCountFailure() {
        String secret = service.enroll(admin.getId(), PASSWORD).getSecret();
        String wrong = flipLastDigit(totpService.code(secret, totpService.currentStep()));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(admin.getId(), wrong));

        assertEquals("MFA_CODE_INVALID", ex.getErrorCode());
        assertFalse(admin.isMfaEnabled(), "验证失败不得启用");
        verify(rateLimitService).countOnly(eq("acct-mfa-code-fail"), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("激活：尚未生成密钥时给出可读错误")
    void activate_shouldRejectWhenNotEnrolled() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.activate(admin.getId(), "123456"));

        assertEquals("MFA_NOT_ENROLLED", ex.getErrorCode());
    }

    // ==================== 第二因子校验（登录侧） ====================

    @Test
    @DisplayName("校验：动态码正确时签发正式令牌与用户资料")
    void verify_shouldIssueTokenWhenCodeMatches() {
        enableMfa();
        String code = currentCode();

        AuthResponse response = service.verify(validTicket(), code);

        assertNotNull(response.getAccessToken(), "第二因子通过后必须签发令牌");
        assertFalse(response.isMfaRequired());
        assertNotNull(response.getUser());
        assertEquals(admin.getId(), response.getUser().getId());
        assertNotNull(admin.getLastLoginAt(), "第二因子通过才视为登录完成");
    }

    @Test
    @DisplayName("校验：同一个动态码用过一次后必须被拒（防重放，RFC 6238 §5.2）")
    void verify_shouldRejectReplayedCode() {
        // 激活时已用掉一个时间步，此处用同一个码再次登录
        String secret = service.enroll(admin.getId(), PASSWORD).getSecret();
        String code = totpService.code(secret, totpService.currentStep());
        service.activate(admin.getId(), code);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(validTicket(), code));

        assertEquals("MFA_CODE_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("校验：动态码不匹配时回退邮箱兜底码，命中即通过")
    void verify_shouldFallBackToEmailCode() {
        enableMfa();
        when(verificationCodeService.tryConsume(eq(admin.getEmail()),
            eq(VerificationCode.CodePurpose.LOGIN_MFA), eq("654321")))
            .thenReturn(VerificationCodeService.VerifyResult.OK);

        AuthResponse response = service.verify(validTicket(), "654321");

        assertNotNull(response.getAccessToken(), "邮箱兜底码命中应同样签发令牌");
    }

    @Test
    @DisplayName("校验：两种因子都失败时返回统一错误码，且只计一次失败")
    void verify_shouldFailWithUnifiedCodeWhenBothFactorsFail() {
        enableMfa();
        when(verificationCodeService.tryConsume(anyString(), any(), anyString()))
            .thenReturn(VerificationCodeService.VerifyResult.INVALID);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(validTicket(), "000000"));

        assertEquals("MFA_CODE_INVALID", ex.getErrorCode(),
            "不得分别回显「动态码错」与「邮箱码错」，避免缩小攻击面");
        verify(rateLimitService).countOnly(eq("acct-mfa-code-fail"), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("校验：未启用 MFA 的账号不得走本流程")
    void verify_shouldRejectWhenMfaNotEnabled() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(validTicket(), "123456"));

        assertEquals("MFA_NOT_ENABLED", ex.getErrorCode());
        // 未产生令牌：JwtTokenService 是真实对象（非 mock），故此处以「登录时间未更新」间接佐证
        assertNull(admin.getLastLoginAt(), "未启用 MFA 的账号不得走第二因子流程");
    }

    @Test
    @DisplayName("【防绕过】用访问令牌冒充票据必须被拒（否则 MFA 形同虚设）")
    void verify_shouldRejectAccessTokenAsTicket() {
        enableMfa();
        String accessToken = jwtTokenService.issue(admin.getId(), admin.getTokenVersion());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(accessToken, currentCode()));

        assertEquals("MFA_TICKET_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("校验：票据版本与账号现值不一致（已改密/登出）必须被拒")
    void verify_shouldRejectStaleTicket() {
        enableMfa();
        String ticket = ticketService.issue(admin.getId(), 0);
        admin.setTokenVersion(admin.getTokenVersion() + 1);   // 模拟改密

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(ticket, currentCode()));

        assertEquals("MFA_TICKET_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("校验：失败次数达上限时直接拒绝，不再消耗因子")
    void verify_shouldBlockWhenFailuresExceedLimit() {
        enableMfa();
        when(rateLimitService.peekCount(anyString(), anyString(), anyInt()))
            .thenReturn(properties.getMfa().getVerifyFailMax());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(validTicket(), "123456"));

        assertEquals("MFA_VERIFY_LIMIT", ex.getErrorCode());
    }

    @Test
    @DisplayName("校验：密钥不可解密（ACCOUNT_MFA_KEY 轮换）时给出可操作指引而非 500")
    void verify_shouldReportUnreadableSecret() {
        admin.setMfaEnabled(true);
        admin.setMfaSecretCipher("corrupted-or-encrypted-with-old-key");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(validTicket(), "123456"));

        assertEquals("MFA_SECRET_UNREADABLE", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("reset-admin-mfa.sql"),
            "报错须指向运维自救脚本");
    }

    @Test
    @DisplayName("校验：账号被停用时拒绝，不签发令牌")
    void verify_shouldRejectDisabledAccount() {
        admin.setMfaEnabled(true);
        admin.setStatus(User.UserStatus.DISABLED);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verify(validTicket(), "123456"));

        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
    }

    // ==================== 邮箱兜底挑战 ====================

    @Test
    @DisplayName("挑战：已启用且开启兜底时发出 LOGIN_MFA 验证码")
    void challenge_shouldSendSecondFactorCode() {
        enableMfa();

        service.challenge(validTicket(), "1.2.3.4");

        verify(verificationCodeService).sendSecondFactorCode(admin.getEmail(), "1.2.3.4");
    }

    @Test
    @DisplayName("挑战：服务端关闭邮箱兜底时拒绝，不发送")
    void challenge_shouldRejectWhenFallbackDisabled() {
        enableMfa();
        properties.getMfa().setEmailFallbackEnabled(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.challenge(validTicket(), "1.2.3.4"));

        assertEquals("MFA_EMAIL_FALLBACK_DISABLED", ex.getErrorCode());
        verify(verificationCodeService, never()).sendSecondFactorCode(anyString(), anyString());
    }

    // ==================== 解绑 ====================

    @Test
    @DisplayName("解绑：口令 + 动态码齐备时清空全部 MFA 字段")
    void unbind_shouldClearAllMfaFields() {
        enableMfa();
        String code = currentCode();

        service.unbind(admin.getId(), PASSWORD, code);

        assertFalse(admin.isMfaEnabled());
        assertNull(admin.getMfaSecretCipher(), "解绑必须清掉密钥密文，不能留可复用的残留");
        assertNull(admin.getMfaEnrolledAt());
        assertNull(admin.getMfaLastUsedStep());
    }

    @Test
    @DisplayName("解绑：口令错误必须拒绝，且不得清空任何字段")
    void unbind_shouldRequirePassword() {
        enableMfa();
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.unbind(admin.getId(), "wrong-password", currentCode()));

        assertEquals("OLD_PASSWORD_MISMATCH", ex.getErrorCode());
        assertTrue(admin.isMfaEnabled(), "口令校验失败时不得解绑");
        assertNotNull(admin.getMfaSecretCipher());
    }

    @Test
    @DisplayName("解绑：动态码错误必须拒绝")
    void unbind_shouldRequireValidCode() {
        enableMfa();
        when(verificationCodeService.tryConsume(anyString(), any(), anyString()))
            .thenReturn(VerificationCodeService.VerifyResult.INVALID);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.unbind(admin.getId(), PASSWORD, "000000"));

        assertEquals("MFA_CODE_INVALID", ex.getErrorCode());
        assertTrue(admin.isMfaEnabled());
    }

    @Test
    @DisplayName("解绑：刻意不递增 tokenVersion（已过 MFA 的会话无失效必要，且避免操作后立刻掉线）")
    void unbind_shouldNotBumpTokenVersion() {
        enableMfa();
        int before = admin.getTokenVersion();

        service.unbind(admin.getId(), PASSWORD, currentCode());

        assertEquals(before, admin.getTokenVersion(),
            "解绑不应递增 tokenVersion——该决策见 MfaService#unbind 的类注释");
    }

    @Test
    @DisplayName("解绑：未启用 MFA 时给出明确错误")
    void unbind_shouldRejectWhenNotEnabled() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.unbind(admin.getId(), PASSWORD, "123456"));

        assertEquals("MFA_NOT_ENABLED", ex.getErrorCode());
    }

    // ==================== 辅助方法 ====================

    /**
     * 使账号进入「已启用 MFA，且上一个码是若干步之前用掉的」状态。
     *
     * <p><b>刻意不调 {@link MfaService#activate}</b>：activate 会把「刚刚用掉的时间步」写入
     * {@code mfaLastUsedStep}，紧接着再用当前步的码登录会被判为重放（这正是
     * {@link #verify_shouldRejectReplayedCode} 所验证的行为）。此处把已用步设为 10 步之前，
     * 既更贴近「管理员很久前绑定、现在来登录」的真实场景，也让当前码可被接受。
     */
    private String enableMfa() {
        String secret = service.enroll(admin.getId(), PASSWORD).getSecret();
        admin.setMfaEnabled(true);
        admin.setMfaEnrolledAt(LocalDateTime.now());
        admin.setMfaLastUsedStep(totpService.currentStep() - 10);
        return secret;
    }

    /** 取当前有效动态码（基于已落库的密文密钥解密后计算，等于真实登录时的路径） */
    private String currentCode() {
        String secret = secretCipher.decrypt(admin.getMfaSecretCipher());
        return totpService.code(secret, totpService.currentStep());
    }

    private String validTicket() {
        return ticketService.issue(admin.getId(), admin.getTokenVersion());
    }

    /** 翻转末位，构造一个「长度正确但值错误」的码 */
    private static String flipLastDigit(String code) {
        char last = code.charAt(code.length() - 1);
        return code.substring(0, code.length() - 1) + (last == '9' ? '0' : (char) (last + 1));
    }
}
