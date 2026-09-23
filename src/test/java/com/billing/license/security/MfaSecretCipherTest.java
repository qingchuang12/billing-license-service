package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TOTP 密钥落库加解密验证（plan-7.0 / M6）。
 *
 * <p>TOTP 密钥是<b>可逆秘密</b>（校验时要用它重算动态码），无法只存哈希，因此必须加密落库——
 * 明文落库意味着读库者能<b>永久生成有效动态码</b>。本类断言加密的几条基本性质：
 * 往返一致、同上文两次密文不同（随机 IV）、篡改可检出（GCM 认证标签）、换密钥即失败。
 */
class MfaSecretCipherTest {

    private static final String MASTER_KEY = "mfa-master-key-for-unit-tests-0123456789abcdef";

    private MfaSecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new MfaSecretCipher(new MfaKeyDeriver(properties(MASTER_KEY)));
    }

    @Test
    @DisplayName("加解密往返一致")
    void encryptDecrypt_shouldRoundTrip() {
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

        String packed = cipher.encrypt(secret);

        assertFalse(packed.contains(secret), "密文不得包含明文密钥片段");
        assertEquals(secret, cipher.decrypt(packed));
    }

    @Test
    @DisplayName("同一明文两次加密得到不同密文（每次使用独立随机 IV）")
    void encrypt_shouldUseFreshIvPerCall() {
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

        String first = cipher.encrypt(secret);
        String second = cipher.encrypt(secret);

        assertFalse(first.equals(second), "两次密文相同说明 IV 被复用，GCM 下会破坏机密性");
        assertEquals(secret, cipher.decrypt(first));
        assertEquals(secret, cipher.decrypt(second));
    }

    @Test
    @DisplayName("篡改密文必须解密失败，不静默产出错误密钥")
    void decrypt_shouldRejectTamperedCiphertext() {
        String packed = cipher.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        // 翻转末尾一个字符，破坏 GCM 认证标签
        char last = packed.charAt(packed.length() - 1);
        String tampered = packed.substring(0, packed.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    @DisplayName("非密文垃圾串解密失败，不抛未包装异常")
    void decrypt_shouldRejectGarbage() {
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("not-a-valid-cipher"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(""));
    }

    @Test
    @DisplayName("更换主密钥后旧密文解不开（ACCOUNT_MFA_KEY 轮换的必然结果，有运维脚本兜底）")
    void decrypt_shouldFailAfterMasterKeyRotation() {
        String packed = cipher.encrypt("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        MfaSecretCipher afterRotation =
            new MfaSecretCipher(new MfaKeyDeriver(properties("rotated-master-key-0123456789abcdefghijkl")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> afterRotation.decrypt(packed));
        assertTrue(ex.getMessage().contains("ACCOUNT_MFA_KEY"),
            "报错须点明疑似密钥轮换，便于运维定位");
    }

    private static AccountProperties properties(String masterKey) {
        AccountProperties props = new AccountProperties();
        props.setJwtSecret("jwt-secret-for-unit-tests-0123456789abcdefghijkl");
        props.getMfa().setKey(masterKey);
        return props;
    }
}
