package com.billing.license.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP 密钥的落库加解密（plan-7.0 / M2）。
 *
 * <p><b>为什么必须加密而不是存哈希</b>：与密码不同，TOTP 密钥是<b>可逆秘密</b>——校验时必须
 * 用它重新计算动态码，故无法只存哈希。而一旦明文落库，读库者即可<b>永久生成有效动态码</b>，
 * 危害超过密码哈希泄漏（密码还得去撞，动态码直接算）。这正是 RFC 6238 §5.1 对
 * 「storing the keys securely … encrypting them」的要求。
 *
 * <p><b>不复用 KMS</b>：实查 {@code KmsService} 只有 Ed25519 的 {@code sign}/{@code verify}，
 * 是<b>非对称签名</b>服务，<b>不具备对称加解密能力</b>，无法用于本用途。
 *
 * <p>算法取 AES-256-GCM：带认证标签，密文被篡改即解密失败（不会静默产出错误密钥）。
 * 存储格式为 {@code Base64(iv || ciphertext || tag)}，每次加密使用独立随机 IV。
 */
@Component
@RequiredArgsConstructor
public class MfaSecretCipher {

    /** GCM 推荐 IV 长度 96 bit（NIST SP 800-38D） */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final MfaKeyDeriver keyDeriver;
    private final SecureRandom random = new SecureRandom();

    /** 加密 TOTP 密钥（Base32 明文）→ 可落库的 Base64 密文 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyDeriver.secretEncryptionKey(),
                new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            // 加密失败属运行环境异常（算法缺失），不应静默降级为非加密存储
            throw new IllegalStateException("TOTP 密钥加密失败", e);
        }
    }

    /**
     * 解密 TOTP 密钥。密文被篡改、或主密钥已轮换（换了 {@code ACCOUNT_MFA_KEY}）时，
     * GCM 认证会失败并抛出——此时该账号需走运维脚本 {@code reset-admin-mfa.sql} 重新绑定。
     */
    public String decrypt(String packed) {
        try {
            byte[] raw = Base64.getDecoder().decode(packed);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyDeriver.secretEncryptionKey(),
                new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 密钥解密失败（密文损坏或 ACCOUNT_MFA_KEY 已轮换）", e);
        }
    }
}
