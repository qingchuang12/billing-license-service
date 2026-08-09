package com.billing.license.infrastructure.kms;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Local file-based KMS implementation
 */
@Slf4j
@Service
public class LocalKmsService implements KmsService {
    
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String algorithm;
    
    public LocalKmsService() throws Exception {
        // Load keys from files (paths configured via application.yml)
        String privateKeyPath = System.getenv().getOrDefault("PRIVATE_KEY_PATH", "/keys/private.key");
        String publicKeyPath = System.getenv().getOrDefault("PUBLIC_KEY_PATH", "/keys/public.key");
        
        byte[] privateKeyBytes = Files.readAllBytes(Paths.get(privateKeyPath));
        byte[] publicKeyBytes = Files.readAllBytes(Paths.get(publicKeyPath));
        
        // Detect algorithm and load keys
        if (privateKeyBytes.length == 32 || publicKeyBytes.length == 32) {
            // Ed25519
            this.algorithm = "Ed25519";
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            this.publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        } else {
            // EC or RSA - detect from key format
            String alg = detectAlgorithm(publicKeyBytes);
            this.algorithm = alg;
            KeyFactory keyFactory = KeyFactory.getInstance(alg);
            this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
            this.publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        }
        
        log.info("Loaded local keys with algorithm: {}", algorithm);
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
        try {
            Signature signature = Signature.getInstance(getSignatureAlgorithm());
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign data", e);
        }
    }
    
    @Override
    public boolean verify(byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(getSignatureAlgorithm());
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            log.error("Failed to verify signature", e);
            return false;
        }
    }
    
    @Override
    public byte[] getPublicKey() {
        return publicKey.getEncoded();
    }
    
    @Override
    public String getAlgorithm() {
        return algorithm;
    }
    
    private String getSignatureAlgorithm() {
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
