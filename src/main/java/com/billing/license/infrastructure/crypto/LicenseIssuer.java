package com.billing.license.infrastructure.crypto;

import com.billing.license.entity.License;
import com.billing.license.entity.Product;
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
            long issuedAtSeconds = ZonedDateTime.now().toEpochSecond();
            payload.put("lic", license.getLicenseKey());
            payload.put("cid", license.getCustomerId().toString());
            Product product = license.getProduct();
            if (product != null) {
                payload.put("sku", product.getSku());
                // B17：写入档位与权益清单，供客户端离线校验 Pro / Pro Plus 的权益差异
                if (product.getTier() != null) {
                    payload.put("plan", product.getTier().name());
                }
                if (product.getFeatures() != null && !product.getFeatures().isEmpty()) {
                    try {
                        payload.put("feat", objectMapper.readValue(product.getFeatures(), List.class));
                    } catch (Exception e) {
                        // A4：非法 JSON 不再静默丢弃——明确告警且不写入 feat 键；签发流程继续（不因权益描述异常阻断售卖）
                        log.warn("产品 features 非合法 JSON 数组，已跳过 feat 键：sku={}, features={}",
                            product.getSku(), product.getFeatures(), e);
                    }
                }
                // A3：更新门槛——键缺失即「不限制」，故为 null 或 <= 0 时均不写入；
                // 注意：当前不支持用 0 表达「不含更新」，0 一律按「不限制」处理。
                if (product.getUpdateUntilDays() != null && product.getUpdateUntilDays() > 0) {
                    payload.put("update_until", issuedAtSeconds + product.getUpdateUntilDays() * 86400L);
                }
                if (product.getMaxMajorVersion() != null && product.getMaxMajorVersion() > 0) {
                    payload.put("max_major_version", product.getMaxMajorVersion());
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
            payload.put("iat", issuedAtSeconds);
            
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

            // A5：按 header 携带的 kid 选择公钥；解析失败时 kid 传 null 走默认分支
            return kmsService.verify(
                signingInput.getBytes(StandardCharsets.UTF_8),
                signature,
                extractKid(parts[0])
            );
        } catch (Exception e) {
            log.error("Failed to verify license", e);
            return false;
        }
    }

    /**
     * 从 JWS header 段解析 kid；解析失败返回 null（交由 KMS 走默认公钥）。
     */
    private String extractKid(String headerB64) {
        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(headerB64), StandardCharsets.UTF_8);
            Map<String, Object> header = objectMapper.readValue(headerJson, Map.class);
            Object kid = header.get("kid");
            return kid == null ? null : kid.toString();
        } catch (Exception e) {
            log.warn("解析 token header 失败，kid 置空并回退默认公钥验签");
            return null;
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
        return switch (alg) {
            case "Ed25519" -> "EdDSA";
            case "EC" -> "ES256";   // 本地/阿里云 EC 默认 ES256
            case "RSA" -> "RS256";
            default -> alg;          // 云 KMS（如阿里云）直接返回精确 JWS alg；本地已在上文映射
        };
    }
}
