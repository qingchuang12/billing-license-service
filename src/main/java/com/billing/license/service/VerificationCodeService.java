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
import java.util.List;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCodeRepository codeRepository;
    private final RateLimitService rateLimitService;
    private final AccountProperties properties;
    private final EmailNotificationService emailNotificationService;

    /**
     * 签发并发送验证码。
     *
     * @param rawEmail 原始邮箱（内部归一化为小写）
     * @param purpose  用途
     * @param clientIp 客户端 IP（用于 IP 维度限流）
     */
    @Transactional
    public void send(String rawEmail, VerificationCode.CodePurpose purpose, String clientIp) {
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
     * 校验并消费验证码。
     *
     * @throws BusinessException {@code CODE_INVALID} / {@code CODE_EXPIRED} / {@code CODE_TOO_MANY_ATTEMPTS}
     */
    @Transactional
    public void verifyAndConsume(String rawEmail, VerificationCode.CodePurpose purpose, String code) {
        String email = normalize(rawEmail);
        LocalDateTime now = LocalDateTime.now();

        List<VerificationCode> usable = codeRepository.findUsable(email, purpose, now);
        if (usable.isEmpty()) {
            // 区分「输错」与「来晚了」：查未消费但已过期的码
            boolean expired = codeRepository.findUnconsumed(email, purpose).stream()
                .anyMatch(v -> v.getExpiresAt() != null && !v.getExpiresAt().isAfter(now));
            log.info("验证码校验失败：无可用码 email={}, purpose={}, expired={}", email, purpose, expired);
            throw new BusinessException(expired ? "CODE_EXPIRED" : "CODE_INVALID",
                expired ? "验证码已过期，请重新获取" : "验证码无效或已被使用，请重新获取");
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
            throw new BusinessException(exhausted ? "CODE_TOO_MANY_ATTEMPTS" : "CODE_INVALID",
                exhausted ? "验证码错误次数过多，已作废，请重新获取" : "验证码无效或已被使用，请重新获取");
        }

        record.setConsumedAt(now);
        codeRepository.save(record);
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
