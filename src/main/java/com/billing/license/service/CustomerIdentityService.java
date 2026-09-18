package com.billing.license.service;

import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 客户身份解析服务（plan-3.0 §三 E1「客户标识邮箱化」）。
 *
 * <p><b>背景</b>：{@code POST /api/redeem/redeem} 与 {@code POST /api/checkout/create} 均为面向终端
 * 用户的入口，而终端用户不可能知道内部 {@code users.id}(UUID)。故对外统一以**邮箱**标识客户，
 * 内部仍以 {@code users.id}(UUID) 为主键与全部落库列（决策 1：方案 A，0 数据迁移）。
 *
 * <p><b>语义</b>：通过 {@link #resolveOrCreate(String)} 把「对外邮箱」解析为「内部 userId」：
 * <ul>
 *   <li>邮箱为空 → {@link BusinessException} {@code EMAIL_REQUIRED}；格式非法 → {@code INVALID_EMAIL}；</li>
 *   <li>已注册 → 返回既有 {@code userId}（复用，不重复建号）；</li>
 *   <li>未注册 → **自动创建访客账户**（随机不可登录密码、{@code emailVerified=false}），作为归属载体；</li>
 *   <li>并发下唯一索引 {@code uk_users_email} 冲突 → 捕获后重查返回，不向上抛错。</li>
 * </ul>
 *
 * <p><b>认领路径</b>：访客账户可后续走既有 {@code POST /api/account/password/reset} 设密码认领，
 * 无需新建流程。
 *
 * <p><b>PII</b>：日志/异常文案一律使用 {@link #maskEmail(String)} 掩码，不打印完整邮箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerIdentityService {

    /** 与既有 {@code AccountProperties} 校验口径一致的邮箱格式（保守版：本地部分 + @ + 域名 + . + 顶级域）。 */
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 将对外邮箱解析为内部 userId；未注册则自动创建访客账户。
     *
     * @param rawEmail 原始邮箱（可含空白/大小写差异）
     * @return 归属账户的 {@code users.id}
     * @throws BusinessException 邮箱为空（{@code EMAIL_REQUIRED}）或格式非法（{@code INVALID_EMAIL}）
     */
    @Transactional
    public UUID resolveOrCreate(String rawEmail) {
        String email = normalize(rawEmail);
        if (email.isEmpty()) {
            throw new BusinessException("EMAIL_REQUIRED", "客户邮箱必填");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException("INVALID_EMAIL", "邮箱格式非法：" + maskEmail(rawEmail));
        }

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return existing.get().getId();
        }

        // 未注册 → 建访客账户：随机密码不可登录（仅作归属载体），emailVerified=false 供后续认领
        User guest = User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
            .status(User.UserStatus.ACTIVE)
            .emailVerified(false)
            .tokenVersion(0)
            .failedLoginCount(0)
            .build();
        guest.normalizeEmail();

        try {
            User saved = userRepository.save(guest);
            log.info("未注册邮箱自动建访客账户：userId={}, email={}", saved.getId(), maskEmail(email));
            return saved.getId();
        } catch (DataIntegrityViolationException e) {
            // 并发撞唯一索引 uk_users_email：另一事务已创建同名账户，重查返回，不抛错
            log.info("访客账户并发创建冲突，重查既有账户：email={}", maskEmail(email));
            return userRepository.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new BusinessException("EMAIL_REQUIRED", "邮箱账户创建冲突，请重试"));
        }
    }

    /**
     * 只读解析：邮箱已注册则返回其 userId，否则返回 {@link Optional#empty()}；**不创建账户**。
     *
     * <p>用于管理端按邮箱过滤等只读场景，避免查询副作用建号。
     */
    @Transactional(readOnly = true)
    public Optional<UUID> resolveExisting(String rawEmail) {
        String email = normalize(rawEmail);
        if (email.isEmpty()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email).map(User::getId);
    }

    /** 邮箱掩码（{@code a***@x.com}），用于日志与异常文案，避免打印完整 PII。 */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        String head = local.substring(0, 1);
        return head + "***" + domain;
    }

    /** 归一化为小写去空白，与 {@code User#normalizeEmail()} 同语义（仓储查询入参要求归一化小写）。 */
    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
