package com.billing.license.infrastructure.kms;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * W17 验证（无需真实 AWS）：用 mock KmsClient 驱动 AwsKmsService，
 * 断言① 各 EC 曲线映射正确签名算法（P-521→ECDSA_SHA_512）与 JWS alg；
 * ② ECDSA 签名 DER→raw 与 verify raw→DER 互逆。
 */
class AwsKmsServiceTest {

    private String signAlgoFor(KeySpec spec) {
        return switch (spec) {
            case ECC_NIST_P384 -> "SHA384withECDSA";
            case ECC_NIST_P521 -> "SHA512withECDSA";
            default -> "SHA256withECDSA";
        };
    }

    private KeyPair ecKeyPair(String curve) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(curve));
        return g.generateKeyPair();
    }

    private KmsClient mockClientWithEc(String keySpecName, KeyPair kp) throws Exception {
        KeySpec spec = KeySpec.fromValue(keySpecName);
        KmsClient mock = mock(KmsClient.class);
        when(mock.getPublicKey(any(GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .keySpec(spec)
                        .publicKey(SdkBytes.fromByteArray(kp.getPublic().getEncoded()))
                        .build());
        when(mock.sign(any(SignRequest.class))).thenAnswer(inv -> {
            SignRequest req = inv.getArgument(0);
            Signature s = Signature.getInstance(signAlgoFor(spec));
            s.initSign(kp.getPrivate());
            s.update(req.message().asByteArray());
            return SignResponse.builder().signature(SdkBytes.fromByteArray(s.sign())).build();
        });
        when(mock.verify(any(VerifyRequest.class))).thenAnswer(inv -> {
            VerifyRequest req = inv.getArgument(0);
            Signature v = Signature.getInstance(signAlgoFor(spec));
            v.initVerify(kp.getPublic());
            v.update(req.message().asByteArray());
            return VerifyResponse.builder().signatureValid(v.verify(req.signature().asByteArray())).build();
        });
        return mock;
    }

    @Test
    void p256_mapsEs256_andSignConvertsDerToRaw() throws Exception {
        KeyPair kp = ecKeyPair("secp256r1");
        KmsClient mock = mockClientWithEc("ECC_NIST_P256", kp);
        AwsKmsService svc = new AwsKmsService(mock, "test-key");

        assertEquals("ES256", svc.getAlgorithm());
        byte[] raw = svc.sign("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, raw.length); // P-256 raw = 32 + 32

        // DER→raw→DER 互逆：还原的 DER 可由本地 JCA 校验通过
        byte[] der = AwsKmsService.rawToDer(raw, 32);
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(kp.getPublic());
        v.update("hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(v.verify(der));
        // verify 入口接受 raw（内部转 DER 送 KMS）
        assertTrue(svc.verify("hello".getBytes(StandardCharsets.UTF_8), raw));
    }

    @Test
    void p521_mapsEs512_notRejected() throws Exception {
        KeyPair kp = ecKeyPair("secp521r1");
        KmsClient mock = mockClientWithEc("ECC_NIST_P521", kp);
        AwsKmsService svc = new AwsKmsService(mock, "test-key");

        assertEquals("ES512", svc.getAlgorithm());
        byte[] raw = svc.sign("data".getBytes(StandardCharsets.UTF_8));
        assertEquals(132, raw.length); // P-521 raw = 66 + 66

        // W17：此前错配 ECDSA_SHA_256，AWS 会拒绝签发；此处必须走 SHA_512
        verify(mock).sign(argThat((SignRequest r) -> r.signingAlgorithm() == SigningAlgorithmSpec.ECDSA_SHA_512));
        assertTrue(svc.verify("data".getBytes(StandardCharsets.UTF_8), raw));
    }
}
