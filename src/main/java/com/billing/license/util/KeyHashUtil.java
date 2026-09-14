package com.billing.license.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 密钥哈希工具：用于审计 actor 标识（不记录明文密钥）。
 * 与 {@code AdminController} 原有逻辑保持一致（SHA-256，前缀 12 位）。
 */
public final class KeyHashUtil {

    private KeyHashUtil() {
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /** 取密钥哈希前缀（12 位）作为审计 actor；空密钥返回 "anonymous" */
    public static String actorHash(String key) {
        if (key == null || key.isEmpty()) {
            return "anonymous";
        }
        String h = sha256Hex(key);
        return h.length() > 12 ? h.substring(0, 12) : h;
    }
}
