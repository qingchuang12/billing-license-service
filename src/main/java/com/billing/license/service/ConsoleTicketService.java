package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * AI-Tools 运营台活跃统计「安装票据 + 摘要」签发。
 *
 * <p>职责边界（与 product-console 接入指南 v1.2 对齐）：
 * <ul>
 *   <li>客户端只传机器码与可选 signedToken；本服务用<b>不进客户端</b>的摘要盐生成
 *       {@code deviceKeyHash}/{@code accountKeyHash}，再用<b>不进客户端</b>的票据密钥签安装票据；</li>
 *   <li>product-console 只收到 64 位小写十六进制 HMAC 摘要与票据，收不到邮箱、许可证密钥、明文机器码；</li>
 *   <li>signedToken 验签通过且 token 的 {@code mid} 与本机机器码一致时，账号摘要取 {@code cid}；
 *       否则回退匿名摘要（按机器码稳定），保证试用/未登录也能上报。</li>
 * </ul>
 *
 * <p>失败策略：密钥/盐未配置返回 503；参数错误返回 400；不因取票失败影响授权主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsoleTicketService {

    private final BillingProperties properties;
    private final LicenseIssuer licenseIssuer;

    /** 票据 + 两个摘要。字段名沿用客户端 camelCase 契约。 */
    public record Ticket(String installTicket, String deviceKeyHash, String accountKeyHash, String userType) {
    }

    public boolean isConfigured() {
        BillingProperties.Console cfg = properties.getConsole();
        return cfg != null
            && cfg.getTicketSecret() != null && !cfg.getTicketSecret().isBlank()
            && cfg.getProductSalt() != null && !cfg.getProductSalt().isBlank();
    }

    public Ticket issue(String signedToken, String machineCode, String productCode) {
        BillingProperties.Console cfg = properties.getConsole();
        if (!isConfigured()) {
            throw new BusinessException("TICKET_SECRET_NOT_CONFIGURED", "运营台票据密钥/摘要盐未配置");
        }
        String code = trimToEmpty(productCode);
        if (!cfg.getProductCode().equals(code)) {
            throw new BusinessException("PRODUCT_MISMATCH", "产品编码不匹配");
        }
        String machine = trimToEmpty(machineCode);
        if (machine.isEmpty()) {
            throw new BusinessException("MACHINE_CODE_REQUIRED", "机器码不能为空");
        }

        String deviceKeyHash = hmac(cfg.getProductSalt(), machine);
        TokenInfo token = tokenInfo(signedToken, machine);
        String accountKeyHash = token != null && token.customerId() != null
            ? hmac(cfg.getProductSalt(), "account:" + token.customerId())
            : hmac(cfg.getProductSalt(), "anonymous:" + machine);
        String userType = userTypeOf(token == null ? null : token.sku());

        long ttl = Math.max(60, Math.min(3600, cfg.getTicketTtlSeconds()));
        long expiresAt = Instant.now().getEpochSecond() + ttl;
        String payload = "v1|" + code + "|" + deviceKeyHash + "|" + expiresAt + "|" + UUID.randomUUID();
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = hmac(cfg.getTicketSecret(), payloadB64);
        String signatureB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(hexToBytes(signature));

        log.debug("Console ticket issued: product={}, ttl={}s", code, ttl);
        return new Ticket(payloadB64 + "." + signatureB64, deviceKeyHash, accountKeyHash, userType);
    }

    private record TokenInfo(String customerId, String sku) {
    }

    /**
     * 只接受签名有效、未过期、且 mid（若存在）与本机机器码一致的 token。
     * 任何一种不成立都回退匿名摘要，不把取票失败升级成授权失败。
     */
    private TokenInfo tokenInfo(String signedToken, String machineCode) {
        String token = trimToEmpty(signedToken);
        if (token.isEmpty()) {
            return null;
        }
        try {
            if (!licenseIssuer.verifyLicense(token)) {
                return null;
            }
            Map<String, Object> payload = licenseIssuer.decodePayload(token);
            if (payload == null) {
                return null;
            }
            Object exp = payload.get("exp");
            if (exp instanceof Number number && number.longValue() <= Instant.now().getEpochSecond()) {
                return null;
            }
            String mid = asString(payload.get("mid"));
            if (mid != null && !mid.equals(machineCode)) {
                return null;
            }
            return new TokenInfo(asString(payload.get("cid")), asString(payload.get("sku")));
        } catch (Exception e) {
            log.debug("Console ticket token ignored: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 与 product-console 的 membership_user_types（pro,pro_plus）对齐；未知/未激活显式 free。 */
    private static String userTypeOf(String sku) {
        if (sku == null) {
            return "free";
        }
        String s = sku.toLowerCase();
        if (s.startsWith("pro-plus")) {
            return "pro_plus";
        }
        if (s.startsWith("pro-")) {
            return "pro";
        }
        return "free";
    }

    private static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException("CONSOLE_HMAC_FAILED", "运营台摘要生成失败");
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
