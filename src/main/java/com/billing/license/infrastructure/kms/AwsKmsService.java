package com.billing.license.infrastructure.kms;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
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
 *
 * <p>W17 修正：① 各 EC 曲线映射到正确的签名算法（P-521 → ECDSA_SHA_512，此前错配 SHA_256
 * 导致 AWS 拒绝签发）；② ECDSA 签名 AWS 返回 ASN.1 DER，而 JWS（客户端离线校验）要求 raw R‖S，
 * 故 sign 输出 DER→raw、verify 输入 raw→DER，与 LicenseIssuer 保持一致。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kms.provider", havingValue = "aws")
public class AwsKmsService implements KmsService {

    private final String keyId;
    private final KmsClient kmsClient;
    private final SigningAlgorithmSpec signingAlgorithm;
    /** 供 JWS 头使用的精确算法名：ES256 / ES384 / ES512 / RS256（W17） */
    private final String jwsAlgorithm;
    private final boolean ec;
    private final int coordLen; // EC 坐标字节数（P-256=32 / P-384=48 / P-521=66）
    private final PublicKey cachedPublicKey;

    public AwsKmsService(
            @Value("${kms.key-id:license-signing-key}") String keyId,
            @Value("${kms.aws.region:us-east-1}") String region) {
        this(KmsClient.builder().region(Region.of(region)).build(), keyId);
    }

    /** 测试 / 可注入 KmsClient 的构造器（不自行建连）。 */
    AwsKmsService(KmsClient kmsClient, String keyId) {
        this.keyId = keyId;
        this.kmsClient = kmsClient;

        GetPublicKeyResponse pub = kmsClient.getPublicKey(
                GetPublicKeyRequest.builder().keyId(keyId).build());
        this.cachedPublicKey = parseDer(pub.publicKey().asByteArray());

        KeySpec spec = pub.keySpec();
        if (spec == KeySpec.RSA_2048 || spec == KeySpec.RSA_4096) {
            this.signingAlgorithm = SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256;
            this.jwsAlgorithm = "RS256";
            this.ec = false;
            this.coordLen = 0;
        } else if (spec == KeySpec.ECC_NIST_P256) {
            this.signingAlgorithm = SigningAlgorithmSpec.ECDSA_SHA_256;
            this.jwsAlgorithm = "ES256";
            this.ec = true;
            this.coordLen = 32;
        } else if (spec == KeySpec.ECC_NIST_P384) {
            this.signingAlgorithm = SigningAlgorithmSpec.ECDSA_SHA_384;
            this.jwsAlgorithm = "ES384";
            this.ec = true;
            this.coordLen = 48;
        } else if (spec == KeySpec.ECC_NIST_P521) { // W17：P-521 必须 SHA-512
            this.signingAlgorithm = SigningAlgorithmSpec.ECDSA_SHA_512;
            this.jwsAlgorithm = "ES512";
            this.ec = true;
            this.coordLen = 66;
        } else { // ECC_SECG_P256K1 等其余 EC 类
            this.signingAlgorithm = SigningAlgorithmSpec.ECDSA_SHA_256;
            this.jwsAlgorithm = "ES256";
            this.ec = true;
            this.coordLen = 32;
        }
        log.info("Initialized AWS KMS signer: keyId={}, keySpec={}, jwsAlgorithm={}", keyId, spec, jwsAlgorithm);
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
        byte[] sig = kmsClient.sign(req).signature().asByteArray();
        // W17：ECDSA AWS 返回 DER，JWS 需 raw R‖S
        return ec ? derToRaw(sig, coordLen) : sig;
    }

    @Override
    public boolean verify(byte[] data, byte[] signature) {
        // 校验走云 KMS（与签名同源）；raw 需还原为 DER 再送 KMS
        byte[] sig = ec ? rawToDer(signature, coordLen) : signature;
        VerifyRequest req = VerifyRequest.builder()
                .keyId(keyId)
                .message(SdkBytes.fromByteArray(data))
                .signature(SdkBytes.fromByteArray(sig))
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
        return jwsAlgorithm;
    }

    /** ASN.1 DER（SEQUENCE { INTEGER r, INTEGER s }）→ raw R‖S（定长拼接）。 */
    static byte[] derToRaw(byte[] der, int coordLen) {
        try {
            ASN1Sequence seq = ASN1Sequence.getInstance(der);
            org.bouncycastle.asn1.ASN1Encodable[] parts = seq.toArray();
            BigInteger r = ((ASN1Integer) parts[0]).getValue();
            BigInteger s = ((ASN1Integer) parts[1]).getValue();
            ByteArrayOutputStream out = new ByteArrayOutputStream(coordLen * 2);
            out.write(toFixedLength(r.toByteArray(), coordLen));
            out.write(toFixedLength(s.toByteArray(), coordLen));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("AWS KMS ECDSA 签名 DER→raw 转换失败", e);
        }
    }

    /** raw R‖S → ASN.1 DER（与 derToRaw 互逆），供 verify 还原送 KMS。 */
    static byte[] rawToDer(byte[] raw, int coordLen) {
        try {
            byte[] r = new byte[coordLen];
            byte[] s = new byte[coordLen];
            System.arraycopy(raw, 0, r, 0, coordLen);
            System.arraycopy(raw, coordLen, s, 0, coordLen);
            return new DERSequence(new org.bouncycastle.asn1.ASN1Integer(new BigInteger(1, r)),
                    new org.bouncycastle.asn1.ASN1Integer(new BigInteger(1, s))).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("AWS KMS ECDSA 签名 raw→DER 转换失败", e);
        }
    }

    private static byte[] toFixedLength(byte[] in, int len) {
        if (in.length == len) {
            return in;
        }
        byte[] out = new byte[len];
        if (in.length > len) {
            System.arraycopy(in, in.length - len, out, 0, len);
        } else {
            System.arraycopy(in, 0, out, len - in.length, in.length);
        }
        return out;
    }
}
