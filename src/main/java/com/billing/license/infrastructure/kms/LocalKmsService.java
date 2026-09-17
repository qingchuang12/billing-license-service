package com.billing.license.infrastructure.kms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯本地文件 KMS 实现（唯一实现，无云 KMS 依赖）。
 * 密钥来自文件挂载：路径优先取环境变量 PRIVATE_KEY_PATH/PUBLIC_KEY_PATH，
 * 未设置时回退到 application.yml 的 billing.private-key-path/public-key-path（H12 修复点）。
 */
@Slf4j
@Service
public class LocalKmsService implements KmsService {

    // H12 加固：密钥文件延迟到首次 sign/verify 时再加载，构造期不读盘。
    // 旧实现在构造期读文件，密钥挂载延迟（k8s Secret / 配置中心）即导致启动崩溃；
    // 延迟加载后应用可正常启动，密钥缺失仅在真正签名时失败，便于启动顺序解耦与集成测试无密钥启动。
    private final String privateKeyPath;
    private final String publicKeyPath;

    private volatile KeyPair loadedKeys;
    private final Object keyLock = new Object();

    /**
     * A5：主 kid（新签发的 License 携带此 kid）。额外公钥按 kid 注入，用于验签历史 License。
     */
    private final String primaryKid;

    /**
     * A5：额外验签公钥来源，优先 {@code billing.public-keys.<kid>}，
     * 未配置时回退环境变量 {@code PUBLIC_KEY_<KID>}（KID 大写、连字符转下划线）。
     */
    private final Environment environment;

    /** A5：按 kid 缓存已加载的额外公钥，避免每次验签重复读盘 */
    private final Map<String, KidPublicKey> kidPublicKeyCache = new ConcurrentHashMap<>();

    public LocalKmsService(
            @Value("${billing.private-key-path:/keys/private.key}") String ymlPrivatePath,
            @Value("${billing.public-key-path:/keys/public.key}") String ymlPublicPath,
            @Value("${billing.license-kid:license-key-1}") String licenseKid,
            Environment environment) {
        // 环境变量优先，未设置则回退 yml 配置路径
        this.privateKeyPath = System.getenv().getOrDefault("PRIVATE_KEY_PATH", ymlPrivatePath);
        this.publicKeyPath = System.getenv().getOrDefault("PUBLIC_KEY_PATH", ymlPublicPath);
        this.primaryKid = licenseKid;
        this.environment = environment;
        log.info("LocalKmsService 已就绪（密钥延迟加载）：private={}, public={}, kid={}",
            privateKeyPath, publicKeyPath, primaryKid);
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
            // C11：Ed25519 私钥/公钥为 32 字节裸数据；JDK 的 PKCS8EncodedKeySpec / X509EncodedKeySpec
            // 要求 DER 结构，裸字节会抛 InvalidKeySpecException（默认签名算法 ED25519 首次签发 100% 失败）。
            // 手工包装标准 DER 前缀后构造。
            algorithm = "Ed25519";
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(wrapEd25519PrivateKey(privateKeyBytes)));
            publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(wrapEd25519PublicKey(publicKeyBytes)));
        } else {
            algorithm = detectAlgorithm(publicKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
            privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        }
        return new KeyPair(algorithm, privateKey, publicKey);
    }

    /** Ed25519 裸 32 字节私钥 → PKCS#8 PrivateKeyInfo DER（RFC 8410, version 0） */
    private static byte[] wrapEd25519PrivateKey(byte[] raw) {
        byte[] prefix = { 0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
                0x04, 0x22, 0x04, 0x20 };
        byte[] out = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(raw, 0, out, prefix.length, raw.length);
        return out;
    }

    /** Ed25519 裸 32 字节公钥 → X.509 SubjectPublicKeyInfo DER（BIT STRING 封装） */
    private static byte[] wrapEd25519PublicKey(byte[] raw) {
        byte[] prefix = { 0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00 };
        byte[] out = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(raw, 0, out, prefix.length, raw.length);
        return out;
    }

    /**
     * A5：按 kid 解析验签公钥。主 kid / 空 kid 用当前密钥对；
     * 其余 kid 查 {@code billing.public-keys.<kid>} 或环境变量；查不到则回退主公钥并告警。
     */
    private KidPublicKey resolvePublicKey(String kid) {
        KeyPair kp = loadKeys();
        if (kid == null || kid.isBlank() || kid.equals(primaryKid)) {
            return new KidPublicKey(kp.algorithm, kp.publicKey);
        }
        String path = resolvePublicKeyPath(kid);
        if (path == null) {
            log.warn("未知 kid={}（未配置 billing.public-keys.{} 或环境变量 {}），回退主公钥验签",
                kid, kid, toEnvVarName(kid));
            return new KidPublicKey(kp.algorithm, kp.publicKey);
        }
        return kidPublicKeyCache.computeIfAbsent(kid, k -> loadPublicKey(path));
    }

    private String resolvePublicKeyPath(String kid) {
        String fromConfig = environment == null ? null : environment.getProperty("billing.public-keys." + kid);
        if (fromConfig != null && !fromConfig.isBlank()) {
            return fromConfig.trim();
        }
        String fromEnv = System.getenv(toEnvVarName(kid));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return null;
    }

    private static String toEnvVarName(String kid) {
        return "PUBLIC_KEY_" + kid.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** A5：按路径加载单个公钥（复用 Ed25519 裸密钥 DER 包装逻辑） */
    private KidPublicKey loadPublicKey(String path) {
        try {
            byte[] publicKeyBytes = Files.readAllBytes(Paths.get(path));
            if (publicKeyBytes.length == 32) {
                PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(wrapEd25519PublicKey(publicKeyBytes)));
                return new KidPublicKey("Ed25519", publicKey);
            }
            String algorithm = detectAlgorithm(publicKeyBytes);
            PublicKey publicKey = KeyFactory.getInstance(algorithm)
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            return new KidPublicKey(algorithm, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("按 kid 加载公钥失败（path=" + path + "）：" + e.getMessage(), e);
        }
    }

    /** A5：按 kid 缓存的验签公钥条目（含算法，供选择 Signature 算法） */
    private static final class KidPublicKey {
        final String algorithm;
        final PublicKey publicKey;
        KidPublicKey(String algorithm, PublicKey publicKey) {
            this.algorithm = algorithm;
            this.publicKey = publicKey;
        }
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
        return verify(data, signature, primaryKid);
    }

    /**
     * A5：按 kid 选择公钥验签。kid 为 null / 主 kid / 未配置的未知 kid 时，均使用主公钥（未知 kid 会告警）。
     */
    @Override
    public boolean verify(byte[] data, byte[] signature, String kid) {
        try {
            KidPublicKey entry = resolvePublicKey(kid);
            Signature sig = Signature.getInstance(getSignatureAlgorithm(entry.algorithm));
            sig.initVerify(entry.publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            log.error("Failed to verify signature (kid={})", kid, e);
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
