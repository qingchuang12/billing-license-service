package com.billing.license.infrastructure.crypto;

import com.billing.license.entity.License;
import com.billing.license.entity.Product;
import com.billing.license.infrastructure.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LicenseIssuer 单元测试 —— 覆盖 A3（更新门槛字段）、A4（features 非法 JSON）、A5（kid 验签预留）。
 * 使用内存 Ed25519 KmsService 桩，纯单元测试、不启动 Spring 上下文。
 */
class LicenseIssuerTest {

    private LicenseIssuer issuer;
    private KmsService kms;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        // 只实现 4 个接口方法：3 参 verify 走 KmsService 的 default 实现（验证零破坏）
        kms = new KmsService() {
            public byte[] sign(byte[] data) {
                try {
                    Signature s = Signature.getInstance("Ed25519");
                    s.initSign(kp.getPrivate());
                    s.update(data);
                    return s.sign();
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            public boolean verify(byte[] data, byte[] sig) {
                try {
                    Signature s = Signature.getInstance("Ed25519");
                    s.initVerify(kp.getPublic());
                    s.update(data);
                    return s.verify(sig);
                } catch (Exception e) { return false; }
            }
            public byte[] getPublicKey() { return kp.getPublic().getEncoded(); }
            public String getAlgorithm() { return "Ed25519"; }
        };
        issuer = new LicenseIssuer(kms);
        ReflectionTestUtils.setField(issuer, "licenseKid", "license-key-1");
    }

    private License license(Product product) {
        return License.builder()
            .licenseKey("LIC-ISSUER-1")
            .customerId(UUID.randomUUID())
            .product(product)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(365))
            .machineCode("MACHINE-1")
            .build();
    }

    private Product product(Integer updateUntilDays, Integer maxMajorVersion, String features) {
        return Product.builder()
            .id(UUID.randomUUID()).sku("pro-buyout").name("Pro 买断")
            .licenseDurationDays(365)
            .updateUntilDays(updateUntilDays)
            .maxMajorVersion(maxMajorVersion)
            .features(features)
            .build();
    }

    // ---------------- A3：更新门槛字段落 payload ----------------

    @Test
    void issueLicense_shouldWriteUpdateEntitlement_whenConfigured() {
        String token = issuer.issueLicense(license(product(365, 2, null)));
        Map<String, Object> payload = issuer.decodePayload(token);

        long iat = ((Number) payload.get("iat")).longValue();
        assertEquals(iat + 365 * 86400L, ((Number) payload.get("update_until")).longValue());
        assertEquals(2, ((Number) payload.get("max_major_version")).intValue());
    }

    @Test
    void issueLicense_shouldOmitUpdateEntitlement_whenNullOrZero() {
        Map<String, Object> nullPayload =
            issuer.decodePayload(issuer.issueLicense(license(product(null, null, null))));
        assertFalse(nullPayload.containsKey("update_until"));
        assertFalse(nullPayload.containsKey("max_major_version"));

        Map<String, Object> zeroPayload =
            issuer.decodePayload(issuer.issueLicense(license(product(0, null, null))));
        assertFalse(zeroPayload.containsKey("update_until"), "updateUntilDays=0 表示不限制，不应写入 update_until");
    }

    @Test
    void issueLicense_shouldOmitMaxMajorVersion_whenZero() {
        // 与 updateUntilDays=0 口径一致：<= 0 视为「不限制」，不得写入 0 让客户端误判
        Map<String, Object> payload =
            issuer.decodePayload(issuer.issueLicense(license(product(365, 0, null))));

        assertFalse(payload.containsKey("max_major_version"));
        assertTrue(payload.containsKey("update_until"), "updateUntilDays 有效时仍应写入");
    }

    // ---------------- A4：features 解析强约束 ----------------

    @Test
    void issueLicense_shouldWriteFeat_whenFeaturesValidJson() {
        Map<String, Object> payload =
            issuer.decodePayload(issuer.issueLicense(license(product(null, null, "[\"OFFLINE\",\"API_ACCESS\"]"))));

        assertEquals(java.util.List.of("OFFLINE", "API_ACCESS"), payload.get("feat"));
    }

    @Test
    void issueLicense_shouldSkipFeatButKeepIssuing_whenFeaturesInvalidJson() {
        String token = issuer.issueLicense(license(product(null, null, "NOT_A_JSON_ARRAY")));
        Map<String, Object> payload = issuer.decodePayload(token);

        assertFalse(payload.containsKey("feat"), "非法 JSON 不得写入 feat 键");
        assertEquals("pro-buyout", payload.get("sku"));
        assertTrue(issuer.verifyLicense(token), "features 非法不得阻断签发");
    }

    // ---------------- A5：kid 验签预留 ----------------

    @Test
    void verifyLicense_shouldPassKidFromHeader() {
        String[] capturedKid = new String[1];
        KmsService recordingKms = new KmsService() {
            public byte[] sign(byte[] data) { return kms.sign(data); }
            public boolean verify(byte[] data, byte[] sig) { return kms.verify(data, sig); }
            public boolean verify(byte[] data, byte[] sig, String kid) {
                capturedKid[0] = kid;
                return kms.verify(data, sig);
            }
            public byte[] getPublicKey() { return kms.getPublicKey(); }
            public String getAlgorithm() { return kms.getAlgorithm(); }
        };
        LicenseIssuer kidIssuer = new LicenseIssuer(recordingKms);
        ReflectionTestUtils.setField(kidIssuer, "licenseKid", "license-key-1");

        String token = kidIssuer.issueLicense(license(product(null, null, null)));

        assertTrue(kidIssuer.verifyLicense(token));
        assertEquals("license-key-1", capturedKid[0], "验签应透传 header 中的 kid");
    }

    @Test
    void verifyLicense_shouldPassNullKid_whenHeaderUnparsable() {
        String[] capturedKid = new String[] { "unset" };
        KmsService recordingKms = new KmsService() {
            public byte[] sign(byte[] data) { return kms.sign(data); }
            public boolean verify(byte[] data, byte[] sig) { return kms.verify(data, sig); }
            public boolean verify(byte[] data, byte[] sig, String kid) {
                capturedKid[0] = kid;
                return kms.verify(data, sig);
            }
            public byte[] getPublicKey() { return kms.getPublicKey(); }
            public String getAlgorithm() { return kms.getAlgorithm(); }
        };
        LicenseIssuer kidIssuer = new LicenseIssuer(recordingKms);
        ReflectionTestUtils.setField(kidIssuer, "licenseKid", "license-key-1");

        // 手工构造：header 段非合法 Base64/JSON，但对 signingInput 重新签名（保证签名本身有效）
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"lic\":\"LIC-NO-HEADER\"}".getBytes(StandardCharsets.UTF_8));
        String signingInput = "!!!not-base64!!!." + payloadB64;
        byte[] signature = kms.sign(signingInput.getBytes(StandardCharsets.UTF_8));
        String brokenHeaderToken = signingInput + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertTrue(kidIssuer.verifyLicense(brokenHeaderToken), "header 解析失败应回退默认公钥而非直接失败");
        assertNull(capturedKid[0]);
    }

    @Test
    void kmsDefaultVerify_shouldDelegateToTwoArgVerify() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] sig = kms.sign(data);

        // 桩未覆写 3 参 verify：default 方法应委派给 2 参 verify，行为一致
        assertTrue(kms.verify(data, sig, "any-kid"));
        assertFalse(kms.verify(data, "bad-signature".getBytes(StandardCharsets.UTF_8), "any-kid"));
    }

    @Test
    void verifyLicense_shouldRejectTamperedPayload() {
        String token = issuer.issueLicense(license(product(null, null, null)));
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"lic\":\"FORGED\"}".getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertFalse(issuer.verifyLicense(tampered));
    }
}
