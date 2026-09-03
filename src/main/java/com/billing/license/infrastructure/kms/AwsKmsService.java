package com.billing.license.infrastructure.kms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.kms.model.VerifyRequest;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * AWS KMS 实现（kms.provider=aws）。
 * 私钥永不离开 KMS（HSM/托管），sign/verify 均走云 API；公钥在启动时拉取一次并缓存，
 * 供 LicenseIssuer.getPublicKey() 下发给客户端离线校验。
 *
 * <p>凭证使用 AWS SDK 默认链（环境变量 AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY、IAM 角色等），
 * 不在此硬编码。运行时需真实 AWS 凭证 + 已创建的非对称密钥（RSA_2048/4096 或 ECC_NIST_P*）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kms.provider", havingValue = "aws")
public class AwsKmsService implements KmsService {

    private final String keyId;
    private final KmsClient kmsClient;
    private final SigningAlgorithmSpec signingAlgorithm;
    private final String algorithm; // 供 JWS 头使用的基类型：RSA / EC
    private final PublicKey cachedPublicKey;

    public AwsKmsService(
            @Value("${kms.key-id:license-signing-key}") String keyId,
            @Value("${kms.aws.region:us-east-1}") String region) {
        this.keyId = keyId;
        this.kmsClient = KmsClient.builder().region(Region.of(region)).build();

        GetPublicKeyResponse pub = kmsClient.getPublicKey(
                GetPublicKeyRequest.builder().keyId(keyId).build());
        this.cachedPublicKey = parseDer(pub.publicKey().asByteArray());

        KeySpec spec = pub.keySpec();
        if (spec == KeySpec.RSA_2048 || spec == KeySpec.RSA_4096) {
            this.signingAlgorithm = SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256;
            this.algorithm = "RSA";
        } else if (spec == KeySpec.ECC_NIST_P384) {
            this.signingAlgorithm = SigningAlgorithmSpec.ECDSA_SHA_384;
            this.algorithm = "EC";
        } else { // ECC_NIST_P256 / ECC_NIST_P521 / ECC_SECG_P256K1 等 EC 类
            this.signingAlgorithm = SigningAlgorithmSpec.ECDSA_SHA_256;
            this.algorithm = "EC";
        }
        log.info("Initialized AWS KMS signer: keyId={}, keySpec={}, algorithm={}", keyId, spec, algorithm);
    }

    private static PublicKey parseDer(byte[] der) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            try {
                return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            } catch (Exception ex) {
                throw new IllegalStateException("无法解析 AWS KMS 公钥（非 RSA/EC DER）", ex);
            }
        }
    }

    @Override
    public byte[] sign(byte[] data) {
        SignRequest req = SignRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(data))
                .signingAlgorithm(signingAlgorithm)
                .build();
        return kmsClient.sign(req).signature().asByteArray();
    }

    @Override
    public boolean verify(byte[] data, byte[] signature) {
        // 校验走云 KMS（与签名同源，避免本地 JCA 算法/哈希约定不一致导致误判）
        VerifyRequest req = VerifyRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(data))
                .signature(SdkBytes.fromByteArray(signature))
                .signingAlgorithm(signingAlgorithm)
                .build();
        return Boolean.TRUE.equals(kmsClient.verify(req).signatureValid());
    }

    @Override
    public byte[] getPublicKey() {
        return cachedPublicKey.getEncoded();
    }

    @Override
    public String getAlgorithm() {
        return algorithm;
    }
}
