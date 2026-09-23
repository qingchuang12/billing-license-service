package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * MFA 子密钥派生（plan-7.0 / M2）。
 *
 * <p><b>为什么是「一个主密钥 + 派生」而不是两个配置项</b>：MFA 需要两个<b>互相隔离</b>的密钥——
 * 票据签名密钥与 TOTP 密钥加密密钥。若让运维各配一个，是两份额外的部署负担；而若两个用途
 * 共用同一密钥，则任一处泄漏即波及另一处。这里折中为：单个主密钥 {@code account.mfa-key}
 * 经 HKDF-Expand 派生两个互不相关的子密钥，<b>既满足密钥隔离、运维也只多配一个环境变量</b>。
 *
 * <p><b>为什么不复用 {@code account.jwt-secret} 派生</b>：那会让「令牌签名」与「MFA 密钥加密」
 * 同源，一处泄漏双杀，违背密钥隔离的初衷。
 *
 * <p><b>fail-fast</b>（B8 定案）：主密钥缺失或过短即<b>启动失败</b>，与 {@code jwt-secret} 同风格。
 * 宁可升级时明确报错，也不要留下「看似正常、实则无法加密」的中间态。
 */
@Component
public class MfaKeyDeriver {

    /** HS256 与 AES-256 均要求 ≥256 bit */
    private static final int MIN_KEY_BYTES = 32;

    /** 域分隔标签：改变任一标签即产出完全不同的子密钥 */
    private static final byte[] TICKET_INFO =
        "mfa-ticket-signing-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECRET_ENCRYPTION_INFO =
        "mfa-secret-encryption-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey ticketSigningKey;
    private final SecretKey secretEncryptionKey;

    public MfaKeyDeriver(AccountProperties properties) {
        String master = properties.getMfa().getKey();
        if (master == null || master.isBlank()) {
            throw new IllegalStateException(
                "account.mfa-key 未配置：请设置环境变量 ACCOUNT_MFA_KEY（至少 32 字符）");
        }
        byte[] masterBytes = master.getBytes(StandardCharsets.UTF_8);
        if (masterBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                "account.mfa-key 过短：要求至少 " + MIN_KEY_BYTES + " 字节，当前 " + masterBytes.length);
        }
        this.ticketSigningKey =
            new SecretKeySpec(derive(masterBytes, TICKET_INFO), "HmacSHA256");
        this.secretEncryptionKey =
            new SecretKeySpec(derive(masterBytes, SECRET_ENCRYPTION_INFO), "AES");
    }

    /**
     * 票据签名密钥。用它与 {@code JwtTokenService}（{@code account.jwt-secret}）<b>不同的密钥</b>
     * 签发票据，是「MFA 不可被绕过」的技术前提：票据因此<b>不可能</b>被 {@code JwtAuthFilter}
     * 当作合法访问令牌接受。
     */
    public SecretKey ticketSigningKey() {
        return ticketSigningKey;
    }

    /** TOTP 密钥的 AES-256-GCM 加密密钥 */
    public SecretKey secretEncryptionKey() {
        return secretEncryptionKey;
    }

    /**
     * HKDF-Expand 单块（RFC 5869 §2.3）：{@code HMAC-SHA256(PRK, info || 0x01)}。
     *
     * <p>主密钥本身已要求 ≥32 字节的高熵随机串（非口令），故<b>跳过 HKDF-Extract</b>——
     * 这正是 RFC 5869 对「IKM 已是均匀随机」情形的说明。输出长度 32 字节 = HashLen，
     * 故单块即可，无需多块拼接。
     */
    private static byte[] derive(byte[] master, byte[] info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 0x01);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
