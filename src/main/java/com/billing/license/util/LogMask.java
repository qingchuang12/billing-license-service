package com.billing.license.util;

/**
 * 日志脱敏：密钥 / 验证码等短敏感值只留首尾，避免完整值落日志。
 *
 * <p>邮箱脱敏统一走 {@code CustomerIdentityService#maskEmail(String)}，不在此重复实现。
 */
public final class LogMask {

    private LogMask() {
    }

    /**
     * 通用密钥/验证码掩码：长度 > 8 时保留首尾各 4 位，否则整体替换为 {@code ****}。
     * 空值返回空串，避免日志出现 {@code null} 噪声。
     */
    public static String secret(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
