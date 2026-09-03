package com.billing.license.infrastructure.crypto;

import com.billing.license.entity.License;
import com.billing.license.infrastructure.kms.KmsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * License Issuer - Creates and signs JWS-format licenses
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseIssuer {

    private final KmsService kmsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * M1：kid 可配置，支持密钥轮换。
     * 轮换方式：将 {@code billing.license-kid} 指向新密钥标识，新签发的 License 携带新 kid；
     * 验证侧按 kid 选择对应公钥（当前 KmsService 使用单一密钥，所有 kid 映射到同一公钥，
     * 多密钥密码学轮换需 KMS 侧密钥版本/别名支持，属基础设施层）。
     */
    @Value("${billing.license-kid:license-key-1}")
    private String licenseKid;

    /**
     * Issue a signed license token
     */
    public String issueLicense(License license) {
        try {
            // Create header
            Map<String, Object> header = new HashMap<>();
            header.put("alg", getJwsAlgorithm());
            header.put("typ", "JWT");
            header.put("kid", licenseKid);
            
            // Create payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("lic", license.getLicenseKey());
            payload.put("cid", license.getCustomerId().toString());
            if (license.getProduct() != null) {
                payload.put("sku", license.getProduct().getSku());
                // B17：写入档位与权益清单，供客户端离线校验 Pro / Pro Plus 的权益差异
                if (license.getProduct().getTier() != null) {
                    payload.put("plan", license.getProduct().getTier().name());
                }
                if (license.getProduct().getFeatures() != null && !license.getProduct().getFeatures().isEmpty()) {
                    try {
                        payload.put("feat", objectMapper.readValue(license.getProduct().getFeatures(), List.class));
                    } catch (Exception ignored) {
                        // 特征字段非合法 JSON 时忽略，不影响主签发流程
                    }
                }
            }
            // B17：绑定机器码，客户端可离线校验设备授权
            if (license.getMachineCode() != null) {
                payload.put("mid", license.getMachineCode());
            }
            // B17：关联订单，便于对账与换机重发追溯
            if (license.getOrder() != null) {
                payload.put("oid", license.getOrder().getId().toString());
            }
            payload.put("iat", ZonedDateTime.now().toEpochSecond());
            
            if (license.getExpiresAt() != null) {
                payload.put("exp", license.getExpiresAt()
                    .atZone(ZoneId.systemDefault()).toEpochSecond());
            }
            
            if (license.getMetadata() != null && !license.getMetadata().isEmpty()) {
                payload.put("meta", objectMapper.readValue(license.getMetadata(), Map.class));
            }
            
            // Encode header and payload
            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            String headerB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            
            // Sign
            String signingInput = headerB64 + "." + payloadB64;
            byte[] signature = kmsService.sign(signingInput.getBytes(StandardCharsets.UTF_8));
            String signatureB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature);
            
            String token = signingInput + "." + signatureB64;
            log.debug("Issued license token: {}", license.getLicenseKey());
            
            return token;
        } catch (Exception e) {
            log.error("Failed to issue license", e);
            throw new RuntimeException("Failed to issue license", e);
        }
    }
    
    /**
     * Verify a license token signature
     */
    public boolean verifyLicense(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            
            String signingInput = parts[0] + "." + parts[1];
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            
            return kmsService.verify(
                signingInput.getBytes(StandardCharsets.UTF_8),
                signature
            );
        } catch (Exception e) {
            log.error("Failed to verify license", e);
            return false;
        }
    }
    
    /**
     * Decode payload without verification (for inspection)
     */
    public Map<String, Object> decodePayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid token format");
            }
            
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            return payload;
        } catch (Exception e) {
            log.error("Failed to decode payload", e);
            throw new RuntimeException("Failed to decode payload", e);
        }
    }
    
    private String getJwsAlgorithm() {
        String alg = kmsService.getAlgorithm();
        switch (alg) {
            case "Ed25519":
                return "EdDSA";
            case "EC":
                return "ES256";
            case "RSA":
                return "RS256";
            default:
                return "ES256";
        }
    }
}
