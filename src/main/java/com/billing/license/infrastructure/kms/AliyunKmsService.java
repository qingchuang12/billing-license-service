package com.billing.license.infrastructure.kms;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.kms.model.v20160120.AsymmetricSignRequest;
import com.aliyuncs.kms.model.v20160120.AsymmetricSignResponse;
import com.aliyuncs.kms.model.v20160120.AsymmetricVerifyRequest;
import com.aliyuncs.kms.model.v20160120.AsymmetricVerifyResponse;
import com.aliyuncs.kms.model.v20160120.GetPublicKeyRequest;
import com.aliyuncs.kms.model.v20160120.GetPublicKeyResponse;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 阿里云 KMS 实现（kms.provider=aliyun）。
 * 私钥托管在阿里云 KMS（硬件加密机），sign/verify 均走云 API。
 *
 * <p>关键约定（基于 aliyun-java-sdk-kms:2.16.0 实测 API）：
 * <ul>
 *   <li>AsymmetricSign/AsymmetricVerify 接受的是<b>消息摘要</b>（SHA-256 的 base64），而非明文；
 *       因此本实现在本地先算 SHA-256 摘要再上送。</li>
 *   <li>该版本 SDK 的 GetPublicKeyResponse <b>不返回 KeySpec</b>，签名算法由
 *       {@code kms.aliyun.sign-algorithm} 配置驱动（如 RSA_PKCS1_SHA_256 / ECDSA_SHA_256）。</li>
 * </ul>
 *
 * <p>凭证取自环境变量 ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET，不在此硬编码。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kms.provider", havingValue = "aliyun")
public class AliyunKmsService implements KmsService {

    private final String keyId;
    private final String signAlgorithm; // 阿里云 API 算法名，如 RSA_PKCS1_SHA_256
    private final String algorithm;     // 供 JWS 头使用的基类型：RSA / EC
    private final DefaultAcsClient client;
    private final PublicKey cachedPublicKey;

    public AliyunKmsService(
            @Value("${kms.key-id:license-signing-key}") String keyId,
            @Value("${kms.aliyun.region:cn-hangzhou}") String region,
            @Value("${kms.aliyun.sign-algorithm:RSA_PKCS1_SHA_256}") String signAlgorithm,
            @Value("${kms.aliyun.key-type:RSA}") String keyType) {
        this.keyId = keyId;
        this.signAlgorithm = signAlgorithm;
        this.algorithm = "EC".equalsIgnoreCase(keyType) ? "EC" : "RSA";

        String ak = System.getenv("ALIYUN_ACCESS_KEY_ID");
        String sk = System.getenv("ALIYUN_ACCESS_KEY_SECRET");
        IClientProfile profile = DefaultProfile.getProfile(region, ak, sk);
        this.client = new DefaultAcsClient(profile);

        GetPublicKeyRequest gpk = new GetPublicKeyRequest();
        gpk.setKeyId(keyId);
        GetPublicKeyResponse pub = call(() -> client.getAcsResponse(gpk));
        this.cachedPublicKey = parsePublicKey(pub.getPublicKey());

        log.info("Initialized Aliyun KMS signer: keyId={}, algorithm={}, keyType={}",
                keyId, signAlgorithm, algorithm);
    }

    @Override
    public byte[] sign(byte[] data) {
        String digest = Base64.getEncoder().encodeToString(sha256(data));
        AsymmetricSignRequest req = new AsymmetricSignRequest();
        req.setKeyId(keyId);
        req.setAlgorithm(signAlgorithm);
        req.setDigest(digest);
        AsymmetricSignResponse resp = call(() -> client.getAcsResponse(req));
        return Base64.getDecoder().decode(resp.getValue());
    }

    @Override
    public boolean verify(byte[] data, byte[] signature) {
        String digest = Base64.getEncoder().encodeToString(sha256(data));
        AsymmetricVerifyRequest req = new AsymmetricVerifyRequest();
        req.setKeyId(keyId);
        req.setAlgorithm(signAlgorithm);
        req.setDigest(digest);
        req.setValue(Base64.getEncoder().encodeToString(signature));
        AsymmetricVerifyResponse resp = call(() -> client.getAcsResponse(req));
        return Boolean.TRUE.equals(resp.getValue());
    }

    @Override
    public byte[] getPublicKey() {
        return cachedPublicKey.getEncoded();
    }

    @Override
    public String getAlgorithm() {
        return algorithm;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static PublicKey parsePublicKey(String publicKey) {
        byte[] der;
        if (publicKey.contains("-----BEGIN")) {
            String b64 = publicKey
                    .replaceAll("-----BEGIN[^-]+-----", "")
                    .replaceAll("-----END[^-]+-----", "")
                    .replaceAll("\\s+", "");
            der = Base64.getDecoder().decode(b64);
        } else {
            der = Base64.getDecoder().decode(publicKey);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            try {
                return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            } catch (Exception ex) {
                throw new IllegalStateException("无法解析阿里云 KMS 公钥（非 RSA/EC）", ex);
            }
        }
    }

    private static <T> T call(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ClientException e) {
            // ClientException 为基类，ServerException 继承自它，捕获基类即可覆盖两者
            throw new RuntimeException("阿里云 KMS 调用失败：" + e.getErrCode() + " " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ClientException;
    }
}
