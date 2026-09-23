package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 终端用户账号（plan v2.10）。
 *
 * <p><b>{@code id} 直接承载 {@code Order.customerId}</b>（决策 1）：登录用户下单即以 userId 作为
 * customerId，天然打通「用户 → 订单 → License」；匿名订单的 customerId 为随机 UUID，
 * 不对应任何 User。由于后者存在，本表不建到 orders 的外键。
 *
 * <p><b>{@code tokenVersion} 是令牌失效开关</b>（决策 2）：登出 / 改密 / 重置密码时 +1，
 * 使该用户所有已签发的令牌立即失效，由 {@code JwtAuthFilter} 每请求校验。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 邮箱（登录标识）；写入前须经 {@link #normalizeEmail()} 归一化为小写 */
    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt 密文（长度固定 60 字符，列宽 100 留冗余） */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    /**
     * 角色：决定登录后获得的权限域（plan-6.0 统一登录）。
     *
     * <p><b>刻意不写入 JWT</b>：由 {@code JwtAuthFilter} 每请求从 DB 现查（该过滤器本就要查 User
     * 校验 status 与 tokenVersion，零额外成本）。这样管理员被降权/停用时可<b>即时生效</b>，
     * 并复用既有的 {@code tokenVersion + 1} 令其已签发令牌立即失效。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** 令牌版本号；递增后该用户所有旧令牌失效 */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private int tokenVersion = 0;

    /** 连续登录失败次数；登录成功时清零 */
    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private int failedLoginCount = 0;

    /** 锁定截止时刻；为空或已过期表示未锁定 */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // ==================== 二次因子（plan-7.0 / M1，B8 定案） ====================

    /**
     * TOTP 密钥<b>密文</b>（AES-256-GCM，Base64(iv||ct||tag)）；{@code null} 表示从未生成。
     *
     * <p><b>不可明文存储</b>：与密码哈希不同，TOTP 密钥是<b>可逆秘密</b>——泄漏即等于对方能
     * 永久生成有效动态码。加密密钥由 {@code account.mfa-key} 派生，不复用 token 签名密钥。
     */
    @Column(name = "mfa_secret_cipher")
    private String mfaSecretCipher;

    /**
     * 是否已启用二次因子。<b>登录只认本列</b>（不认密钥是否存在），
     * 故「已生成密钥但未完成激活」的中间态不会把管理员锁在门外。
     */
    @Column(name = "mfa_enabled", nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    @Column(name = "mfa_enrolled_at")
    private LocalDateTime mfaEnrolledAt;

    /**
     * 最近一次成功校验的 TOTP 时间步（{@code epochSecond / stepSeconds}），用于防重放
     * （RFC 6238 §5.2）：同一时间步的 6 位码只能用一次，堵住「截获后在 30 秒窗口内重放」。
     */
    @Column(name = "mfa_last_used_step")
    private Long mfaLastUsedStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 邮箱归一化为小写并去空白，避免同一邮箱因大小写不同被判定为两个账号 */
    public void normalizeEmail() {
        if (email != null) {
            this.email = email.trim().toLowerCase();
        }
    }

    /** 当前是否处于锁定状态（锁定窗口未过） */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    public enum UserStatus {
        /** 正常可登录 */
        ACTIVE,
        /** 已停用（管理员操作），不可登录 */
        DISABLED
    }

    /** 角色：决定登录后可访问的权限域（plan-6.0 统一登录）。 */
    public enum UserRole {
        /** 普通消费者：仅 ROLE_USER，可访问 /api/account/** */
        USER,
        /** 管理员：ROLE_USER + ROLE_ADMIN，可访问 /api/admin/**（X-API-Key 通道已于 A12 移除，现仅由管理员 JWT 授权） */
        ADMIN
    }
}
