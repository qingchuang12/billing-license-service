package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.UserProfileResponse;
import com.billing.license.entity.User;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.AuthUserPrincipal;
import com.billing.license.security.JwtTokenService;
import com.billing.license.security.MfaTicketService;
import com.billing.license.security.PasswordPolicy;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 账号服务（plan v2.10 / A4、A5）：注册、登录、登出、当前用户、改密、找回密码。
 *
 * <p><b>设计约束</b>：
 * <ol>
 *   <li>登录失败统一返回 {@code INVALID_CREDENTIALS}（不区分邮箱不存在与密码错误，防账号枚举）；</li>
 *   <li>账号级锁定落库（{@code failed_login_count} / {@code locked_until}），跨重启有效；
 *       IP 级限流走内存 {@link RateLimitService}（多实例部署需共享存储，与既有限制同源）；</li>
 *   <li>登出 / 改密 / 重置密码一律 {@code tokenVersion + 1}，使该用户所有已签发令牌立即失效；
 *       Q3（plan-7.0）：改密与自助重置<b>同时清除</b> {@code must_change_password} 标记，
 *       管理员代重置<b>置位</b>该标记（见 {@code AdminUserService#resetPassword}）；</li>
 *   <li>密码与验证码明文不进日志、不进审计；</li>
 *   <li>改密 / 自助重置成功后发送<b>不含密码</b>的安全提醒（P1，旁路：发送失败不回滚变更）；</li>
 *   <li><b>登录是两阶段的</b>（plan-7.0 / M3）：账号启用二次因子时，{@link #login} 校验密码后
 *       <b>不签发令牌</b>，而是返回一次性票据；真正的令牌只由
 *       {@link MfaService#verify} 在第二因子通过后签发。故本类是「MFA 不可被绕过」的守门点。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final MfaTicketService mfaTicketService;
    private final VerificationCodeService verificationCodeService;
    private final RateLimitService rateLimitService;
    private final AuthenticationManager authenticationManager;
    private final EmailNotificationService emailNotificationService;
    private final AccountProperties properties;

    // ==================== A2 注册 ====================

    @Transactional
    public AuthResponse register(String rawEmail, String password, String emailCode, String clientIp) {
        String email = normalize(rawEmail);
        AccountProperties.Risk risk = properties.getRisk();

        try {
            rateLimitService.checkAndCount("acct-register-ip", clientIp,
                risk.getRegisterIpMax(), risk.getRegisterWindowMinutes());
            rateLimitService.checkAndCount("acct-register-email", email,
                risk.getRegisterEmailMax(), risk.getRegisterWindowMinutes());
        } catch (RateLimitService.RateLimitExceededException e) {
            log.warn("注册触发限流：email={}, ip={}", email, clientIp);
            throw new BusinessException("REGISTER_LIMIT", "注册操作过于频繁，请稍后再试");
        }

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException("EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        validatePasswordPolicy(password);

        boolean verified = false;
        if (properties.isRequireEmailVerification()) {
            if (emailCode == null || emailCode.isBlank()) {
                throw new BusinessException("EMAIL_CODE_REQUIRED", "请先获取并填写邮箱验证码");
            }
            verificationCodeService.verifyAndConsume(email, VerificationCode.CodePurpose.REGISTER, emailCode);
            verified = true;
        }

        User user = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(password))
            .status(User.UserStatus.ACTIVE)
            .emailVerified(verified)
            .tokenVersion(0)
            .failedLoginCount(0)
            .build();
        user.normalizeEmail();
        User saved = userRepository.save(user);
        log.info("账号注册成功：userId={}, email={}", saved.getId(), email);

        return toAuthResponse(saved);
    }

    // ==================== A3 登录 ====================

    @Transactional
    public AuthResponse login(String rawEmail, String password, String clientIp, String locale) {
        String email = normalize(rawEmail);
        AccountProperties.Risk risk = properties.getRisk();

        // IP 档：挡撞库扫描。先只读判定，避免把本次请求计入失败数
        int ipFailures = rateLimitService.peekCount("acct-login-ip-fail", clientIp, risk.getLoginIpFailWindowMinutes());
        if (ipFailures >= risk.getLoginIpFailMax()) {
            log.warn("登录触发 IP 限流：ip={}, failures={}", clientIp, ipFailures);
            throw new BusinessException("LOGIN_IP_LIMIT", "登录尝试过于频繁，请稍后再试");
        }

        // 认证链（融合 Spring Security，2026-09-24）：加载用户 + 锁定/停用预检 + 密码比对交给
        // DaoAuthenticationProvider；IP 限流、失败计数与锁定落库、两阶段 MFA、tokenVersion 仍在
        // 本类外层，行为与改造前逐条一致。
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        } catch (LockedException e) {
            throw new BusinessException("ACCOUNT_LOCKED", "账号已被临时锁定，请稍后再试");
        } catch (DisabledException e) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号已被停用，请联系客服");
        } catch (BadCredentialsException e) {
            // 防账号枚举：邮箱不存在（UsernameNotFound 已被 hideUserNotFoundExceptions 收敛为
            // BadCredentials）与密码错误统一同一文案，并同样记录失败（user 为 null 时只计 IP）。
            User failed = userRepository.findByEmail(email).orElse(null);
            recordLoginFailure(clientIp, risk, failed, locale);
            if (failed == null) {
                log.info("登录失败：邮箱不存在 email={}", email);
            } else {
                log.info("登录失败：密码错误 userId={}", failed.getId());
            }
            throw new BusinessException("INVALID_CREDENTIALS", "邮箱或密码错误");
        }

        User user = ((AuthUserPrincipal) authentication.getPrincipal()).getUser();
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);

        // plan-7.0 / M3（B8）：已启用二次因子的账号，密码通过后**不发令牌**，改发一次性票据。
        // 闸门必须在这里（签发令牌之前）——只在管理台 UI 加一步输入是假安全，
        // 绕过页面直调本接口照样拿得到令牌，MFA 等于没做。
        // 注意 mfa_enabled 已为真但密钥不可解的情况由 MfaService 报可读错误，不会走到这里。
        if (user.isMfaEnabled()) {
            userRepository.save(user);
            log.info("登录第一步（密码）通过，等待第二因子：userId={}", user.getId());
            return AuthResponse.pendingSecondFactor(
                mfaTicketService.issue(user.getId(), user.getTokenVersion()), mfaMethods());
        }

        user.setLastLoginAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("登录成功：userId={}", saved.getId());

        return toAuthResponse(saved);
    }

    // ==================== A4 登出 ====================

    @Transactional
    public void logout(UUID userId) {
        invalidateTokens(userId, "登出");
    }

    // ==================== A5 当前用户 ====================

    @Transactional(readOnly = true)
    public UserProfileResponse me(UUID userId) {
        return UserProfileResponse.from(requireUser(userId));
    }

    // ==================== A6 改密 ====================

    @Transactional
    public void changePassword(UUID userId, String oldPassword, String newPassword, String locale) {
        AccountProperties.Risk risk = properties.getRisk();
        try {
            rateLimitService.checkAndCount("acct-change-pwd", userId.toString(),
                risk.getChangePasswordUserMax(), risk.getChangePasswordWindowMinutes());
        } catch (RateLimitService.RateLimitExceededException e) {
            log.warn("改密触发限流：userId={}", userId);
            throw new BusinessException("CHANGE_PASSWORD_LIMIT", "改密操作过于频繁，请稍后再试");
        }

        User user = requireUser(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException("OLD_PASSWORD_MISMATCH", "旧密码错误");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION", "新密码不得与旧密码相同");
        }
        validatePasswordPolicy(newPassword);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Q3：改密是清除「强制改密」标记的两条路径之一（另一条是邮箱验证码自助重置），
        // 与写新哈希同事务——避免出现「密码已改但标记仍在、用户仍被过滤器挡着」的半完成态。
        user.setMustChangePassword(false);
        userRepository.save(user);
        invalidateTokens(userId, "改密");
        // P1 安全提醒（旁路，@Async）：只告知发生过变更，不含任何密码；失败不回滚上面的变更
        emailNotificationService.sendPasswordChangedEmail(user.getEmail(), "SELF_CHANGE", locale);
    }

    // ==================== A7 找回密码 ====================

    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword, String clientIp, String locale) {
        String email = normalize(rawEmail);
        AccountProperties.Risk risk = properties.getRisk();
        try {
            rateLimitService.checkAndCount("acct-reset-email", email,
                risk.getResetEmailMax(), risk.getResetWindowMinutes());
            rateLimitService.checkAndCount("acct-reset-ip", clientIp,
                risk.getResetIpMax(), risk.getResetWindowMinutes());
        } catch (RateLimitService.RateLimitExceededException e) {
            log.warn("找回密码触发限流：email={}, ip={}", email, clientIp);
            throw new BusinessException("RESET_LIMIT", "找回密码操作过于频繁，请稍后再试");
        }

        // 先校验验证码：验证码本身错误仍落 CODE_INVALID
        verificationCodeService.verifyAndConsume(email, VerificationCode.CodePurpose.RESET_PASSWORD, code);

        // B6（账户枚举修复，2026-09-20）：注册端点保留后，账号可在购买/兑换外单独创建，
        // 原「查无账户抛 EMAIL_NOT_PURCHASED」既语义不再成立（不再等价于"未购买"），
        // 又构成账户枚举神谕（暴露该邮箱是否存在/是否付费客户）。
        // 现改为：查无账户时静默成功返回（与成功路径同响应），不泄露该邮箱是否注册。
        // 验证码已在上一步消费——攻击者即便猜测也无法凭返回值区分"邮箱不存在"与"密码已重置"。
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            log.info("找回密码：邮箱无对应账户，静默返回（防枚举）");
            return;
        }
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号已被停用，请联系客服");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION", "新密码不得与旧密码相同");
        }
        validatePasswordPolicy(newPassword);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Q3：用户自己走完找回流程即视为已重新掌握密码，清除强制改密标记（与写新哈希同事务）
        user.setMustChangePassword(false);
        userRepository.save(user);
        log.info("密码重置成功：userId={}", user.getId());
        invalidateTokens(user.getId(), "重置密码");
        // P1 安全提醒（旁路，@Async）：不含密码；失败不回滚上面的重置
        emailNotificationService.sendPasswordChangedEmail(user.getEmail(), "SELF_RESET", locale);
    }

    // ==================== 内部方法 ====================

    /** 记录一次登录失败：IP 计数（内存）+ 账号计数与锁定（落库） */
    private void recordLoginFailure(String clientIp, AccountProperties.Risk risk, User user, String locale) {
        rateLimitService.countOnly("acct-login-ip-fail", clientIp,
            risk.getLoginIpFailWindowMinutes(), risk.getLoginIpFailMax() + 1);
        if (user == null) {
            return;
        }
        int failures = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(failures);
        if (failures >= risk.getLoginAccountFailMax()) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(risk.getLoginAccountLockMinutes()));
            log.warn("账号因连续登录失败被锁定：userId={}, failures={}, 锁定时长={}分钟",
                user.getId(), failures, risk.getLoginAccountLockMinutes());
            // SEC-5：锁定即「被撞库」强信号，旁路提醒被锁账号邮箱。本方法仅在 BadCredentials 分支调用；
            // 账号一旦锁定，后续尝试在认证链即抛 LockedException 被单独捕获、不再进入此处，故每次锁定只发一封。
            // try/catch 兜底：@Async 的「提交」本身可能同步抛 TaskRejectedException（线程池饱和），
            // 若不放行会让下面的 save 被跳过、锁定丢失——通知是旁路，绝不能阻断安全动作。
            try {
                emailNotificationService.sendAccountLockedEmail(
                    user.getEmail(), risk.getLoginAccountLockMinutes(), locale);
            } catch (Exception e) {
                log.warn("锁定提醒邮件提交失败，不影响锁定：userId={}", user.getId(), e);
            }
        }
        userRepository.save(user);
    }

    /** tokenVersion +1：使该用户所有已签发令牌立即失效 */
    private void invalidateTokens(UUID userId, String reason) {
        User user = requireUser(userId);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        log.info("已因「{}」使旧令牌失效：userId={}, tokenVersion={}", reason, userId, user.getTokenVersion());
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "账号不存在"));
    }

    private AuthResponse toAuthResponse(User user) {
        boolean admin = user.getRole() == User.UserRole.ADMIN;
        return AuthResponse.builder()
            .accessToken(jwtTokenService.issue(user.getId(), user.getTokenVersion(), admin))
            .expiresIn(jwtTokenService.expiresInSeconds(admin))
            .user(UserProfileResponse.from(user))
            .build();
    }

    /**
     * 当前可用的第二因子方式，供待第二因子响应告知客户端（plan-7.0 / M3）。
     * 邮箱兜底关闭时不下发 {@code EMAIL}，避免客户端引导用户走一条服务端不接受的路径。
     */
    private List<String> mfaMethods() {
        return properties.getMfa().isEmailFallbackEnabled()
            ? List.of("TOTP", "EMAIL")
            : List.of("TOTP");
    }

    /** 密码策略（plan 8.3）：长度 8–72，可选要求同时含字母与数字 */
    private void validatePasswordPolicy(String password) {
        // 委托给 PasswordPolicy 唯一实现：bootstrap 初始管理员与之共用同一份策略，
        // 避免日后改策略时只改一处、另一处悄悄漂移
        PasswordPolicy.validate(password, properties);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
