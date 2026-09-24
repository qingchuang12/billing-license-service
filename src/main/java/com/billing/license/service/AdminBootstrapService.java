package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.entity.User;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 初始管理员 bootstrap：空库首次部署时按配置自动建出第一个管理员，免运维手工改库。
 *
 * <p><b>解决什么问题</b>：全新库一个账号都没有，而管理端 {@code /api/admin/**} 一律要求
 * {@code ROLE_ADMIN}，于是<b>没有任何账号能登录</b>。此前只能让运维直连数据库 UPDATE 出一个
 * 管理员。配置化之后，首次部署带上环境变量即可直接使用。
 *
 * <p><b>唯一的生效条件：一个管理员都没有</b>（{@code countByRole(ADMIN) == 0}）。
 * 一旦库里已存在 ADMIN——无论它是不是由本配置创建的——本配置<b>完全失效</b>。
 * 这条"自废武功"的设计是刻意的：运维很可能把这段配置长期留在环境变量里忘了删，
 * 若它在任何情况下都生效，那么某次管理员账号被误删之后，就等于给攻击者留了扇自动开门。
 *
 * <p><b>邮箱已注册时只提权、绝不改密码</b>：该邮箱若已存在（通常是注册过的普通账号），
 * 只把它提升为 ADMIN；配置里的明文口令不会被写进去，以免顶掉用户正在使用的密码。
 *
 * <p><b>为什么不在 {@code CommandLineRunner} 里做，而要挂在 {@code ApplicationReadyEvent}</b>：
 * Runner 执行时应用尚未 Ready，异常表现与生命周期都不好控；且本逻辑要查库，
 * 必须排在 Flyway 迁移完成之后。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountProperties properties;

    /**
     * 应用就绪后立即执行。
     *
     * <p>刻意<b>吞掉"未配置"这种情况</b>（只 WARN）：bootstrap 是首次部署的便利措施，
     * 不是持续依赖，没配不应该让服务起不来。但<b>配了一半</b>（只给邮箱不给密码）
     * 是明确的运维错误，直接抛异常拒绝启动，避免留下"配了但没生效"的糊涂账。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        if (userRepository.countByRole(User.UserRole.ADMIN) > 0) {
            // 已有管理员 → 本配置整体失效，连日志都不必打（正常稳态每天都会走到这里）
            return;
        }

        AccountProperties.BootstrapAdmin cfg = properties.getBootstrapAdmin();
        String email = blankToNull(cfg.getEmail());
        String rawPassword = blankToNull(cfg.getPassword());

        if (email == null && rawPassword == null) {
            log.warn("库中不存在任何管理员，且未配置 account.bootstrap-admin —— 管理端 /api/admin/** 将无人可登录。"
                    + "首次部署请设置 ACCOUNT_BOOTSTRAP_ADMIN_EMAIL 与 ACCOUNT_BOOTSTRAP_ADMIN_PASSWORD。");
            return;
        }
        if (email == null || rawPassword == null) {
            throw new IllegalStateException("account.bootstrap-admin 的 email 与 password 必须成对配置，当前只配了其中一项");
        }
        // 与注册 / 改密共用同一份强度策略（含 BCrypt 72 字节上限）
        PasswordPolicy.validate(rawPassword, properties);

        String normalizedEmail = email.toLowerCase();
        Optional<User> existing = userRepository.findByEmail(normalizedEmail);
        if (existing.isPresent()) {
            User promoted = existing.get();
            promoted.setRole(User.UserRole.ADMIN);
            userRepository.save(promoted);
            log.info("bootstrap：已把既有账号提权为管理员（未改动其密码）：{}", normalizedEmail);
            return;
        }

        User admin = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(User.UserRole.ADMIN)
                .status(User.UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
        userRepository.save(admin);
        log.info("bootstrap：已创建初始管理员 {}，请于首次登录后立即修改密码", normalizedEmail);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
