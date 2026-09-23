package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.MfaEnrollResponse;
import com.billing.license.dto.MfaStatusResponse;
import com.billing.license.dto.UserProfileResponse;
import com.billing.license.entity.User;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.JwtTokenService;
import com.billing.license.security.MfaSecretCipher;
import com.billing.license.security.MfaTicketService;
import com.billing.license.security.TotpService;
import com.billing.license.service.risk.RateLimitService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 管理员二次因子（MFA）服务（plan-7.0 / M3，决策 B8）。
 *
 * <p><b>形态</b>：TOTP（认证器 App）为主因子，邮箱验证码为兜底（恢复路径）。
 * <b>默认关</b>，管理员在管理台自助开启；口令策略不加严（B8 定案）。
 *
 * <p><b>为什么闸门在服务端签发令牌处</b>：{@code User.role} 不写进 JWT、由
 * {@code JwtAuthFilter} 每请求现查，故本服务是「第二因子未过就不发令牌」的唯一执行点。
 * 只在管理台 UI 加一步输入是<b>假安全</b>——绕过页面直调 {@code /api/account/login}
 * 照样拿得到令牌。
 *
 * <p><b>校验条件的口径</b>：只看 {@code mfa_enabled}，<b>不看角色</b>。这样降权后仍受保护
 * （更保守）；而绑定入口只在 {@code /api/admin/mfa/**}（{@code ROLE_ADMIN}），故实际只有
 * 管理员能开启。降权时由运维脚本 {@code reset-admin-mfa.sql} 一并清理，避免出现
 * 「非管理员却被要求第二因子、又没有入口解绑」的死角。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private static final String CODE_FAIL_NAMESPACE = "acct-mfa-code-fail";
    private static final String PASSWORD_FAIL_NAMESPACE = "acct-mfa-pwd-fail";
    private static final int FAIL_WINDOW_MINUTES = 10;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final MfaTicketService ticketService;
    private final TotpService totpService;
    private final MfaSecretCipher secretCipher;
    private final VerificationCodeService verificationCodeService;
    private final RateLimitService rateLimitService;
    private final AccountProperties properties;

    // ==================== 管理端：绑定管理（ROLE_ADMIN） ====================

    @Transactional(readOnly = true)
    public MfaStatusResponse status(UUID userId) {
        return MfaStatusResponse.from(requireUser(userId), emailFallbackEnabled());
    }

    /**
     * 生成新密钥（<b>尚未启用</b>）。
     *
     * <p>要求重新输入当前密码：本端点仅凭令牌即可调用，若不校验口令，攻击者窃取令牌后就能
     * <b>静默绑定自己的认证器</b>，把合法管理员挡在门外。
     *
     * <p>已启用时拒绝而非覆盖：覆盖会让 <b>{@code mfa_enabled} 仍为真、而密钥已换成新的</b>，
     * 管理员手上的认证器立即失效且他并不知情——那是把自己锁在门外的经典方式。
     */
    @Transactional
    public MfaEnrollResponse enroll(UUID userId, String password) {
        User user = requireUser(userId);
        verifyPassword(user, password);
        if (user.isMfaEnabled()) {
            throw new BusinessException("MFA_ALREADY_ENABLED",
                "已开启二次验证；如需更换认证器，请先解绑再重新绑定");
        }

        String secret = totpService.generateSecret();
        user.setMfaSecretCipher(secretCipher.encrypt(secret));
        user.setMfaEnabled(false);
        user.setMfaEnrolledAt(null);
        user.setMfaLastUsedStep(null);
        userRepository.save(user);
        log.info("已生成二次因子密钥（待激活）：userId={}", userId);

        return MfaEnrollResponse.builder()
            .secret(secret)
            .otpauthUri(totpService.otpauthUri(user.getEmail(), secret))
            .activated(false)
            .build();
    }

    /**
     * 用认证器当前的动态码激活（密钥录对了才置 {@code mfa_enabled}）。
     *
     * <p>不要求口令：{@code enroll} 已要求过，而攻击者若已持有口令＋令牌，再要求一次口令
     * 并无额外防护价值。
     */
    @Transactional
    public void activate(UUID userId, String code) {
        User user = requireUser(userId);
        if (user.getMfaSecretCipher() == null) {
            throw new BusinessException("MFA_NOT_ENROLLED", "请先生成密钥并录入认证器");
        }
        guardFailures(CODE_FAIL_NAMESPACE, userId);

        // 激活时无「已用时间步」，允许容错窗口内任一步
        Long step = totpService.verify(decryptSecret(user), code, null);
        if (step == null) {
            recordFailure(CODE_FAIL_NAMESPACE, userId);
            throw new BusinessException("MFA_CODE_INVALID",
                "动态码无效，请确认认证器时间与手机时间一致后重试");
        }

        user.setMfaEnabled(true);
        user.setMfaEnrolledAt(LocalDateTime.now());
        // 记下刚用掉的步：该码不能紧接着用于登录（一码一次，RFC 6238 §5.2）
        user.setMfaLastUsedStep(step);
        userRepository.save(user);
        log.info("二次因子已启用：userId={}", userId);
    }

    /**
     * 解绑（关闭二次因子）。
     *
     * <p>同时要求口令与动态码（或邮箱兜底码）：解绑是<b>关闭一道防线</b>的高影响操作，
     * 只凭令牌即可解绑会让令牌泄漏直接等于「攻击者可悄悄关掉 MFA」。
     *
     * <p><b>刻意不递增 {@code tokenVersion}</b>：解绑后账号保护等级下降，但已签发的令牌都是
     * 「通过过第二因子」的会话，无失效必要；且解绑本身需双凭据，不构成「令牌泄漏即可关闭 MFA」
     * 的路径。递增反而会在解绑成功后立刻把管理员踢出登录，行为突兀。
     */
    @Transactional
    public void unbind(UUID userId, String password, String code) {
        User user = requireUser(userId);
        if (!user.isMfaEnabled()) {
            throw new BusinessException("MFA_NOT_ENABLED", "该账号未开启二次验证");
        }
        verifyPassword(user, password);
        guardFailures(CODE_FAIL_NAMESPACE, userId);
        if (!matchAnyCode(user, code)) {
            recordFailure(CODE_FAIL_NAMESPACE, userId);
            throw new BusinessException("MFA_CODE_INVALID", "动态码或邮箱验证码无效");
        }

        user.setMfaEnabled(false);
        user.setMfaSecretCipher(null);
        user.setMfaEnrolledAt(null);
        user.setMfaLastUsedStep(null);
        userRepository.save(user);
        log.info("二次因子已解绑：userId={}", userId);
    }

    // ==================== 登录侧：第二因子（凭票据，无需令牌） ====================

    /**
     * 发送邮箱兜底码。
     *
     * <p>此处只做「票据有效 + 已启用 + 兜底开关开启」三道判定；发送频率由
     * {@link VerificationCodeService} 的邮箱冷却与窗口次数限制把守。
     */
    @Transactional
    public void challenge(String ticket, String clientIp) {
        User user = resolveTicket(ticket);
        if (!user.isMfaEnabled()) {
            throw new BusinessException("MFA_NOT_ENABLED", "该账号未开启二次验证");
        }
        if (!emailFallbackEnabled()) {
            throw new BusinessException("MFA_EMAIL_FALLBACK_DISABLED",
                "本服务未开放邮箱验证码兜底，请使用认证器动态码");
        }
        verificationCodeService.sendSecondFactorCode(user.getEmail(), clientIp);
    }

    /**
     * 校验第二因子并签发正式令牌。
     *
     * <p>先试 TOTP，再试邮箱兜底码——客户端无需声明用的是哪一种。两者都失败才计一次失败，
     * 故邮箱兜底用户不会因为「TOTP 先落空」而白白消耗失败额度。
     */
    @Transactional
    public AuthResponse verify(String ticket, String code) {
        User user = resolveTicket(ticket);
        if (!user.isMfaEnabled()) {
            throw new BusinessException("MFA_NOT_ENABLED", "该账号未开启二次验证");
        }
        guardFailures(CODE_FAIL_NAMESPACE, user.getId());

        if (!matchAnyCode(user, code)) {
            recordFailure(CODE_FAIL_NAMESPACE, user.getId());
            throw new BusinessException("MFA_CODE_INVALID", "动态码或邮箱验证码无效");
        }

        user.setLastLoginAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("第二因子校验通过，签发令牌：userId={}", saved.getId());

        return AuthResponse.builder()
            .accessToken(jwtTokenService.issue(saved.getId(), saved.getTokenVersion()))
            .expiresIn(jwtTokenService.expiresInSeconds())
            .user(UserProfileResponse.from(saved))
            .build();
    }

    // ==================== 内部方法 ====================

    /**
     * 动态码或邮箱兜底码，任一命中即通过。
     *
     * <p>命中 TOTP 时把时间步写回实体（由调用方负责 {@code save}），使同一个码无法二次使用。
     * 邮箱码走 {@link VerificationCodeService#tryConsume}（返回值表达结果，不抛异常），
     * 故本方法可安全地在事务方法内调用。
     */
    private boolean matchAnyCode(User user, String code) {
        if (user.getMfaSecretCipher() != null) {
            Long step = totpService.verify(decryptSecret(user), code, user.getMfaLastUsedStep());
            if (step != null) {
                user.setMfaLastUsedStep(step);
                return true;
            }
        }
        if (emailFallbackEnabled()) {
            return verificationCodeService.tryConsume(
                user.getEmail(), VerificationCode.CodePurpose.LOGIN_MFA, code)
                == VerificationCodeService.VerifyResult.OK;
        }
        return false;
    }

    /**
     * 解析并校验一次性登录票据，返回对应用户。
     *
     * <p>票据由独立密钥签发，故普通访问令牌在此处签名不符、必然被拒；{@code ver} 比对则让
     * 改密 / 登出 / 降权后票据立即失效。
     */
    private User resolveTicket(String ticket) {
        Claims claims;
        try {
            claims = ticketService.parse(ticket);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException("MFA_TICKET_INVALID", "登录票据无效或已过期，请重新登录");
        }
        UUID userId = ticketService.extractUserId(claims);
        if (userId == null) {
            throw new BusinessException("MFA_TICKET_INVALID", "登录票据无效或已过期，请重新登录");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("MFA_TICKET_INVALID", "登录票据无效或已过期，请重新登录"));
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号已被停用，请联系客服");
        }
        if (user.getTokenVersion() != ticketService.extractTokenVersion(claims)) {
            throw new BusinessException("MFA_TICKET_INVALID", "登录票据已失效（账号信息已变更），请重新登录");
        }
        return user;
    }

    /** 校验当前口令；失败计数并入 MFA 自身的限流档，与登录锁定互不干扰。 */
    private void verifyPassword(User user, String password) {
        guardFailures(PASSWORD_FAIL_NAMESPACE, user.getId());
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            recordFailure(PASSWORD_FAIL_NAMESPACE, user.getId());
            log.info("二次因子操作口令校验失败：userId={}", user.getId());
            // 复用改密的既有错误码，避免为同一语义新增词表项
            throw new BusinessException("OLD_PASSWORD_MISMATCH", "密码错误");
        }
    }

    /**
     * 解密 TOTP 密钥。解密失败只可能是密文损坏或 {@code ACCOUNT_MFA_KEY} 已轮换——
     * 后者是运维事件，须给出可操作的指引而非 500。
     */
    private String decryptSecret(User user) {
        try {
            return secretCipher.decrypt(user.getMfaSecretCipher());
        } catch (IllegalStateException e) {
            log.error("TOTP 密钥解密失败，疑似 ACCOUNT_MFA_KEY 已轮换：userId={}", user.getId());
            throw new BusinessException("MFA_SECRET_UNREADABLE",
                "二次验证密钥不可用，请联系运维重置（scripts/db/reset-admin-mfa.sql）");
        }
    }

    private void guardFailures(String namespace, UUID userId) {
        int failures = rateLimitService.peekCount(namespace, userId.toString(), FAIL_WINDOW_MINUTES);
        if (failures >= properties.getMfa().getVerifyFailMax()) {
            log.warn("二次因子操作触发限流：namespace={}, userId={}, failures={}",
                namespace, userId, failures);
            throw new BusinessException("MFA_VERIFY_LIMIT", "尝试过于频繁，请稍后再试");
        }
    }

    private void recordFailure(String namespace, UUID userId) {
        rateLimitService.countOnly(namespace, userId.toString(), FAIL_WINDOW_MINUTES,
            properties.getMfa().getVerifyFailMax() + 1);
    }

    private boolean emailFallbackEnabled() {
        return properties.getMfa().isEmailFallbackEnabled();
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "账号不存在"));
    }
}
