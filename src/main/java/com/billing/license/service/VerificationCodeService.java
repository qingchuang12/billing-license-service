package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.VerificationCodeRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.risk.RateLimitService;
import com.billing.license.util.KeyHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 邮箱验证码签发与校验（plan v2.10 / A3）。
 *
 * <p><b>安全要点</b>：
 * <ol>
 *   <li>库里只存 {@code SHA-256(pepper + code)}，不存明文——库被读也无法直接得到验证码；</li>
 *   <li>校验失败累计达 {@code account.risk.code-verify-fail-max} 即作废该码（6 位数字空间仅 10^6，必须防爆破）；</li>
 *   <li>签发新码前作废同邮箱同用途的旧未消费码，避免多码并存被撞；</li>
 *   <li>明文只出现在「发送通道」与（联调模式下）日志，不进审计、不进响应。</li>
 * </ol>
 *
 * <p><b>两个校验入口</b>（plan-7.0 / M3 引入）：{@link #verifyAndConsume} 以异常表达结果，
 * 供「验证码是唯一凭据」的流程使用（注册、找回密码）；{@link #tryConsume} 以返回值表达结果，
 * 供「动态码<b>或</b>邮箱码二选一」的二次因子流程使用。后者存在的原因很实际：在事务方法内
 * <b>捕获</b>另一个事务方法抛出的运行时异常，会把共享事务标记为 rollback-only，导致外层提交时
 * 抛 {@link org.springframework.transaction.UnexpectedRollbackException}——把一次正常的
 * 「验证码输错」变成 500。用返回值表达结果即可彻底回避。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 公开的发码端点 {@code POST /api/account/verification-code} 允许的用途白名单。
     *
     * <p>{@code LOGIN_MFA} 刻意不在其中：它只能由二次因子挑战端点（已校验登录票据）签发。
     * 否则任何人都能凭该公开端点向任意邮箱<b>反复投递「登录验证码」</b>——尽管理论上拿不到码
     * 就登不进去，但会给合法用户制造「莫名其妙收到登录码」的钓鱼话术由头。
     */
    private static final Set<VerificationCode.CodePurpose> PUBLIC_PURPOSES =
        EnumSet.of(VerificationCode.CodePurpose.REGISTER,
            VerificationCode.CodePurpose.RESET_PASSWORD);

    private final VerificationCodeRepository codeRepository;
    private final RateLimitService rateLimitService;
    private final AccountProperties properties;
    private final EmailNotificationService emailNotificationService;

    /**
     * 签发并发送验证码（公开端点入口，受用途白名单约束）。
     *
     * @param rawEmail 原始邮箱（内部归一化为小写）
     * @param purpose  用途；仅 {@code REGISTER} / {@code RESET_PASSWORD} 可通过
     * @param clientIp 客户端 IP（用于 IP 维度限流）
     */
    @Transactional
    public void send(String rawEmail, VerificationCode.CodePurpose purpose, String clientIp) {
        if (!PUBLIC_PURPOSES.contains(purpose)) {
            throw new BusinessException("PURPOSE_NOT_ALLOWED", "该验证码用途不接受直接请求");
        }
        doSend(rawEmail, purpose, clientIp);
    }

    /**
     * 签发并发送二次因子兜底码（plan-7.0 / M3）。
     *
     * <p>仅供 {@link MfaService} 在<b>校验过登录票据</b>后调用，故不经公开端点、也不受
     * {@link #PUBLIC_PURPOSES} 限制。
     */
    @Transactional
    public void sendSecondFactorCode(String rawEmail, String clientIp) {
        doSend(rawEmail, VerificationCode.CodePurpose.LOGIN_MFA, clientIp);
    }

    /**
     * 校验并消费验证码，以异常表达结果。
     *
     * @throws BusinessException {@code CODE_INVALID} / {@code CODE_EXPIRED} / {@code CODE_TOO_MANY_ATTEMPTS}
     */
    @Transactional
    public void verifyAndConsume(String rawEmail, VerificationCode.CodePurpose purpose, String code) {
        // 自调用不走代理，但本方法自身已带事务，故 tryConsume 的写入仍受同一事务保护
        switch (tryConsume(rawEmail, purpose, code)) {
            case OK -> {
                // 校验通过，已消费
            }
            case EXPIRED -> throw new BusinessException("CODE_EXPIRED", "验证码已过期，请重新获取");
            case TOO_MANY_ATTEMPTS -> throw new BusinessException("CODE_TOO_MANY_ATTEMPTS",
                "验证码错误次数过多，已作废，请重新获取");
            default -> throw new BusinessException("CODE_INVALID", "验证码无效或已被使用，请重新获取");
        }
    }

    /**
     * 校验并消费验证码，以<b>返回值</b>表达结果（不抛异常）。
     *
     * <p>供「动态码或邮箱码二选一」的场景使用，见类注释关于事务的说明。
     */
    @Transactional
    public VerifyResult tryConsume(String rawEmail, VerificationCode.CodePurpose purpose, String code) {
        String email = normalize(rawEmail);
        LocalDateTime now = LocalDateTime.now();

        List<VerificationCode> usable = codeRepository.findUsable(email, purpose, now);
        if (usable.isEmpty()) {
            // 区分「输错」与「来晚了」：查未消费但已过期的码
            boolean expired = codeRepository.findUnconsumed(email, purpose).stream()
                .anyMatch(v -> v.getExpiresAt() != null && !v.getExpiresAt().isAfter(now));
            log.info("验证码校验失败：无可用码 email={}, purpose={}, expired={}", email, purpose, expired);
            return expired ? VerifyResult.EXPIRED : VerifyResult.INVALID;
        }

        VerificationCode record = usable.get(0);
        if (!constantTimeEquals(record.getCodeHash(), hash(code))) {
            record.setAttemptCount(record.getAttemptCount() + 1);
            boolean exhausted = record.getAttemptCount() >= properties.getRisk().getCodeVerifyFailMax();
            if (exhausted) {
                record.setConsumedAt(now);
                log.warn("验证码连续错误达上限，已作废：email={}, purpose={}, attempts={}",
                    email, purpose, record.getAttemptCount());
            }
            codeRepository.save(record);
            return exhausted ? VerifyResult.TOO_MANY_ATTEMPTS : VerifyResult.INVALID;
        }

        record.setConsumedAt(now);
        codeRepository.save(record);
        return VerifyResult.OK;
    }

    /** 验证码校验结果（{@link #tryConsume} 的返回值） */
    public enum VerifyResult {
        /** 校验通过，已消费 */
        OK,
        /** 码不存在、错误或已被使用 */
        INVALID,
        /** 存在未消费但已过期的码 */
        EXPIRED,
        /** 错误次数达上限，该码已作废 */
        TOO_MANY_ATTEMPTS
    }

    /** 签发并投递的主体：限流 → 作废旧码 → 落库 → 投递。两个公开入口共用。 */
    private void doSend(String rawEmail, VerificationCode.CodePurpose purpose, String clientIp) {
        String email = normalize(rawEmail);
        checkSendRateLimit(email, clientIp);

        String code = generateCode(properties.getCodeLength());
        LocalDateTime now = LocalDateTime.now();

        // 作废同邮箱同用途的旧未消费码：保证任何时刻最多一个有效码
        codeRepository.invalidateUnused(email, purpose, now);

        codeRepository.save(VerificationCode.builder()
            .email(email)
            .purpose(purpose)
            .codeHash(hash(code))
            .expiresAt(now.plusMinutes(properties.getCodeTtlMinutes()))
            .attemptCount(0)
            .build());

        deliver(email, code, purpose);
    }

    /**
     * 发送侧限流（plan 5.2）：邮箱冷却 → 邮箱窗口次数 → IP 窗口次数，三层独立计数。
     */
    private void checkSendRateLimit(String email, String clientIp) {
        AccountProperties.Risk risk = properties.getRisk();
        try {
            // RateLimitService 窗口以分钟为单位，冷却按秒配置，此处向上取整换算
            int cooldownMinutes = Math.max(1, (int) Math.ceil(risk.getCodeSendCooldownSeconds() / 60.0));
            rateLimitService.checkAndCount("acct-code-cooldown", email, 1, cooldownMinutes);
            rateLimitService.checkAndCount("acct-code-email", email,
                risk.getCodeSendEmailMax(), risk.getCodeSendEmailWindowMinutes());
            rateLimitService.checkAndCount("acct-code-ip", clientIp, risk.getCodeSendIpMax(), 60);
        } catch (RateLimitService.RateLimitExceededException e) {
            log.warn("验证码发送触发限流：email={}, ip={}", email, clientIp);
            throw new BusinessException("CODE_SEND_TOO_FREQUENT", "验证码发送过于频繁，请稍后再试");
        }
    }

    /**
     * 投递验证码：联调模式只写日志（SMTP 未配置时不被阻塞），否则发邮件。
     */
    private void deliver(String email, String code, VerificationCode.CodePurpose purpose) {
        if (properties.isCodeLogOnly()) {
            // 联调模式：明文入日志。生产务必关闭（account.code-log-only=false）并配置真实 SMTP。
            log.info("[code-log-only] 验证码已生成：email={}, purpose={}, code={}", email, purpose, code);
            return;
        }
        emailNotificationService.sendVerificationCodeEmail(email, code, purpose.name(),
            properties.getCodeTtlMinutes());
    }

    /** 生成定长数字验证码（SecureRandom，与兑换码生成同源思路） */
    private String generateCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** SHA-256(pepper + code)；pepper 为空时退化为无 pepper（仅联调用） */
    private String hash(String code) {
        String pepper = properties.getCodePepper() == null ? "" : properties.getCodePepper();
        return KeyHashUtil.sha256Hex(pepper + code);
    }

    /** 常量时间比较，避免按哈希逐字符短路泄露信息 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
