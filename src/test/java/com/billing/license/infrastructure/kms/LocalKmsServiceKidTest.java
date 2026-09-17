package com.billing.license.infrastructure.kms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LocalKmsService 多 kid 验签单元测试（A5）：
 * 未知 kid 回退主公钥、按 kid 选用额外公钥、带 kid 与不带 kid 行为一致。
 * 使用临时目录内真实 Ed25519 裸密钥（32 字节），纯单元测试、不启动 Spring 上下文。
 */
class LocalKmsServiceKidTest {

    @TempDir
    Path tempDir;

    private LocalKmsService kms;
    private byte[] data;
    private byte[] signature;

    @BeforeEach
    void setUp() throws Exception {
        java.security.KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path privatePath = writeRawKey(tempDir.resolve("private.key"), kp.getPrivate().getEncoded());
        Path publicPath = writeRawKey(tempDir.resolve("public.key"), kp.getPublic().getEncoded());

        // Environment 未配置任何 billing.public-keys.*，getProperty 返回 null
        kms = new LocalKmsService(
            privatePath.toString(), publicPath.toString(), "license-key-1", mock(Environment.class));

        data = "license-signing-input".getBytes(StandardCharsets.UTF_8);
        signature = kms.sign(data);
    }

    /** Ed25519 裸密钥 = DER 末 32 字节（与生产挂载的密钥格式一致） */
    private static Path writeRawKey(Path path, byte[] der) throws Exception {
        return Files.write(path, Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    @Test
    void verifyWithKid_shouldBehaveSameAsWithoutKid() {
        assertTrue(kms.verify(data, signature), "不带 kid 应验签通过");
        assertTrue(kms.verify(data, signature, "license-key-1"), "主 kid 应验签通过");
        assertTrue(kms.verify(data, signature, null), "kid 为 null 应回退主公钥");
        assertEquals("Ed25519", kms.getAlgorithm());
    }

    @Test
    void verifyWithUnknownKid_shouldFallbackToPrimaryKey() {
        // 未配置 billing.public-keys.unknown-kid / PUBLIC_KEY_UNKNOWN_KID → 回退主公钥
        assertTrue(kms.verify(data, signature, "unknown-kid"));
        assertFalse(kms.verify(data, "broken".getBytes(StandardCharsets.UTF_8), "unknown-kid"));
    }

    @Test
    void verifyWithConfiguredKid_shouldUseThatPublicKey() throws Exception {
        java.security.KeyPair oldKp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path oldPublicPath = writeRawKey(tempDir.resolve("public.key.old"), oldKp.getPublic().getEncoded());
        byte[] oldSignature = signWith(oldKp, data);

        Environment environment = mock(Environment.class);
        when(environment.getProperty("billing.public-keys.license-key-0"))
            .thenReturn(oldPublicPath.toString());
        LocalKmsService multiKidKms = new LocalKmsService(
            tempDir.resolve("private.key").toString(),
            tempDir.resolve("public.key").toString(),
            "license-key-1", environment);

        assertTrue(multiKidKms.verify(data, oldSignature, "license-key-0"), "旧 kid 应走旧公钥验签");
        assertFalse(multiKidKms.verify(data, oldSignature, "license-key-1"), "主公钥不应校验旧密钥签名");
        assertTrue(multiKidKms.verify(data, signature, "license-key-1"), "主 kid 仍用主公钥");
    }

    private static byte[] signWith(java.security.KeyPair kp, byte[] payload) throws Exception {
        java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(payload);
        return sig.sign();
    }
}
