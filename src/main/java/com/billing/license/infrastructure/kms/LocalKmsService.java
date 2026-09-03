package com.billing.license.infrastructure.kms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Local file-based KMS implementation (default provider).
 * 密钥来自文件挂载：路径优先取环境变量 PRIVATE_KEY_PATH/PUBLIC_KEY_PATH，
 * 未设置时回退到 application.yml 的 billing.private-key-path/public-key-path（H12 修复点）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "kms.provider", havingValue = "local", matchIfMissing = true)
public class LocalKmsService implements KmsService {

    // H12 加固：密钥文件延迟到首次 sign/verify 时再加载，构造期不读盘。
    // 旧实现在构造期读文件，密钥挂载延迟（k8s Secret / 配置中心）即导致启动崩溃；
    // 延迟加载后应用可正常启动，密钥缺失仅在真正签名时失败，便于启动顺序解耦与集成测试无密钥启动。
    private final String privateKeyPath;
    private final String publicKeyPath;

    private volatile KeyPair loadedKeys;
    private final Object keyLock = new Object();

    public LocalKmsService(
            @Value("${billing.private-key-path:/keys/private.key}") String ymlPrivatePath,
            @Value("${billing.public-key-path:/keys/public.key}") String ymlPublicPath) {
        // 环境变量优先，未设置则回退 yml 配置路径
        this.privateKeyPath = System.getenv().getOrDefault("PRIVATE_KEY_PATH", ymlPrivatePath);
        this.publicKeyPath = System.getenv().getOrDefault("PUBLIC_KEY_PATH", ymlPublicPath);
        log.info("LocalKmsService 已就绪（密钥延迟加载）：private={}, public={}", privateKeyPath, publicKeyPath);
    }

    /** 首次使用时加载密钥对（线程安全，仅加载一次） */
    private KeyPair loadKeys() {
        if (loadedKeys == null) {
            synchronized (keyLock) {
                if (loadedKeys == null) {
                    try {
                        byte[] privateKeyBytes = Files.readAllBytes(Paths.get(privateKeyPath));
                        byte[] publicKeyBytes = Files.readAllBytes(Paths.get(publicKeyPath));
                        loadedKeys = buildKeyPair(privateKeyBytes, publicKeyBytes);
                        log.info("Loaded local keys with algorithm: {}", loadedKeys.algorithm);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                            "本地密钥加载失败（private=" + privateKeyPath + ", public=" + publicKeyPath + "）：" + e.getMessage(), e);
                    }
                }
            }
        }
        return loadedKeys;
    }

    private KeyPair buildKeyPair(byte[] privateKeyBytes, byte[] publicKeyBytes) throws Exception {
        String algorithm;
        PrivateKey privateKey;
        PublicKey publicKey;
        if (privateKeyBytes.length == 32 || publicKeyBytes.length == 32) {
            algorithm = "Ed25519";
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        } else {
            algorithm = detectAlgorithm(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        }
        return new KeyPair(algorithm, privateKey, publicKey);
    }

    private static final class KeyPair {
        final String algorithm;
        final PrivateKey privateKey;
        final PublicKey publicKey;
        KeyPair(String algorithm, PrivateKey privateKey, PublicKey publicKey) {
            this.algorithm = algorithm;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    private String detectAlgorithm(byte[] publicKeyBytes) throws Exception {
        // Try EC first
        try {
            KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            return "EC";
        } catch (Exception e) {
            // Try RSA
            try {
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
                return "RSA";
            } catch (Exception ex) {
                throw new IllegalStateException("Unable to detect key algorithm");
            }
        }
    }
    
    @Override
    public byte[] sign(byte[] data) {
        KeyPair kp = loadKeys();
        try {
            Signature signature = Signature.getInstance(getSignatureAlgorithm(kp.algorithm));
            signature.initSign(kp.privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign data", e);
        }
    }
    
    @Override
    public boolean verify(byte[] data, byte[] signature) {
        KeyPair kp = loadKeys();
        try {
            Signature sig = Signature.getInstance(getSignatureAlgorithm(kp.algorithm));
            sig.initVerify(kp.publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            log.error("Failed to verify signature", e);
            return false;
        }
    }
    
    @Override
    public byte[] getPublicKey() {
        return loadKeys().publicKey.getEncoded();
    }
    
    @Override
    public String getAlgorithm() {
        return loadKeys().algorithm;
    }
    
    private String getSignatureAlgorithm(String algorithm) {
        switch (algorithm) {
            case "Ed25519":
                return "Ed25519";
            case "EC":
                return "SHA256withECDSA";
            case "RSA":
                return "SHA256withRSA";
            default:
                return "SHA256withECDSA";
        }
    }
}
