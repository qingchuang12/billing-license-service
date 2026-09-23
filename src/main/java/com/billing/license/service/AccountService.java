package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.UserProfileResponse;
import com.billing.license.entity.User;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.JwtTokenService;
import com.billing.license.security.MfaTicketService;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>登出 / 改密 / 重置密码一律 {@code tokenVersion + 1}，使该用户所有已签发令牌立即失效；</li>
 *   <li>密码与验证码明文不进日志、不进审计；</li>
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
    public AuthResponse login(String rawEmail, String password, String clientIp) {
        String email = normalize(rawEmail);
        AccountProperties.Risk risk = properties.getRisk();

        // IP 档：挡撞库扫描。先只读判定，避免把本次请求计入失败数
        int ipFailures = rateLimitService.peekCount("acct-login-ip-fail", clientIp, risk.getLoginIpFailWindowMinutes());
        if (ipFailures >= risk.getLoginIpFailMax()) {
            log.warn("登录触发 IP 限流：ip={}, failures={}", clientIp, ipFailures);
            throw new BusinessException("LOGIN_IP_LIMIT", "登录尝试过于频繁，请稍后再试");
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            recordLoginFailure(clientIp, risk, null);
            log.info("登录失败：邮箱不存在 email={}", email);
            throw new BusinessException("INVALID_CREDENTIALS", "邮箱或密码错误");
        }
        if (user.isLocked()) {
            throw new BusinessException("ACCOUNT_LOCKED", "账号已被临时锁定，请稍后再试");
        }
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号已被停用，请联系客服");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            recordLoginFailure(clientIp, risk, user);
            log.info("登录失败：密码错误 userId={}", user.getId());
            throw new BusinessException("INVALID_CREDENTIALS", "邮箱或密码错误");
        }

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
    public void changePassword(UUID userId, String oldPassword, String newPassword) {
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
        userRepository.save(user);
        invalidateTokens(userId, "改密");
    }

    // ==================== A7 找回密码 ====================

    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword, String clientIp) {
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
        userRepository.save(user);
        log.info("密码重置成功：userId={}", user.getId());
        invalidateTokens(user.getId(), "重置密码");
    }

    // ==================== 内部方法 ====================

    /** 记录一次登录失败：IP 计数（内存）+ 账号计数与锁定（落库） */
    private void recordLoginFailure(String clientIp, AccountProperties.Risk risk, User user) {
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
        return AuthResponse.builder()
            .accessToken(jwtTokenService.issue(user.getId(), user.getTokenVersion()))
            .expiresIn(jwtTokenService.expiresInSeconds())
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
        if (password == null || password.length() < properties.getPasswordMinLength() || password.length() > 72) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION",
                "密码长度须为 " + properties.getPasswordMinLength() + "–72 位");
        }
        if (properties.isPasswordRequireAlnum()
            && !(password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit))) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION", "密码须同时包含字母与数字");
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
