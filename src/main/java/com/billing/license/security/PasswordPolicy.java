package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import com.billing.license.exception.BusinessException;

/**
 * 密码强度策略的<b>唯一实现</b>。
 *
 * <p>注册 / 改密 / 找回密码 / 初始管理员 bootstrap 共用同一实现，
 * 避免各写一份、日后「改了这里漏了那里」造成策略漂移。
 *
 * <p><b>为什么要有 72 字节上限</b>：BCrypt 会在 72 字节处静默截断，
 * 超长部分实际不参与比对。因此必须显式拒绝超长口令，而不是让多余字符被悄悄丢掉——
 * 否则用户以为自己设了 100 位的强口令，真正生效的只有前 72 字节。
 *
 * <p><b>为什么用静态工具而非 Bean</b>：策略纯依赖入参、无状态、无外部资源，
 * 做成静态方法可让调用方零成本复用，也不会给既有单测增加需要 mock 的协作者。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /**
     * 校验密码强度，通过则正常返回。
     *
     * @param password   明文密码；仅用于长度与字符类别判定，不会被存储或写入日志
     * @param properties 账号配置（取 {@code password-min-length} / {@code password-require-alnum}）
     * @throws BusinessException 违反策略时抛 {@code PASSWORD_POLICY_VIOLATION}
     */
    public static void validate(String password, AccountProperties properties) {
        if (password == null || password.length() < properties.getPasswordMinLength() || password.length() > 72) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION",
                    "密码长度须为 " + properties.getPasswordMinLength() + "–72 位");
        }
        if (properties.isPasswordRequireAlnum()
                && !(password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit))) {
            throw new BusinessException("PASSWORD_POLICY_VIOLATION", "密码须同时包含字母与数字");
        }
    }
}
