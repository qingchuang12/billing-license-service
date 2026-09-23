package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 邮箱验证码（plan v2.10）。
 *
 * <p><b>仅存哈希不存明文</b>：{@code codeHash} 为 SHA-256(pepper + code)，
 * 即便库被读也无法直接得到验证码；校验日志不打印明文。
 *
 * <p><b>一次性</b>：校验通过即写 {@code consumedAt}；{@code attemptCount} 达阈值时由服务层
 * 判定作废，防止暴力枚举（6 位数字空间仅 10^6）。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "verification_codes")
public class VerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 接收邮箱（小写，与 {@link User#getEmail()} 同域） */
    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodePurpose purpose;

    /** SHA-256(pepper + code)，不存明文 */
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 非空即已消费（含「校验通过」与「主动作废」两种情形） */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /** 校验失败次数；达配置阈值即作废该码 */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** 未消费且未过期 */
    public boolean isUsable() {
        return consumedAt == null && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }

    /**
     * 用途：注册验证 / 找回密码 / 二次因子兜底。
     *
     * <p>新增 {@link #LOGIN_MFA}（plan-7.0 / M1）<b>无需 DDL</b>：表列 {@code purpose} 为
     * {@code VARCHAR(30)} 且无 CHECK 约束（`V1__baseline_schema.sql:478`），已实测确认。
     */
    public enum CodePurpose {
        REGISTER,
        RESET_PASSWORD,
        /** 登录第二因子兜底码（TOTP 不可用时的恢复路径） */
        LOGIN_MFA
    }
}
