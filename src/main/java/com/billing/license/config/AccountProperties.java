package com.billing.license.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 账号体系配置（plan v2.10），命名空间 {@code account}。
 *
 * <p>风格与 {@link BillingProperties} 一致：顶层为开关与策略，风控阈值收敛在
 * {@link Risk} 内部类中，与 plan 第五节限流矩阵逐条对应。
 *
 * <p><b>fail-fast</b>：{@code jwt-secret} 不设默认值，缺失即启动失败
 * （与 {@code DB_PASSWORD} / {@code ADMIN_API_KEYS} 的安全风格一致）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "account")
public class AccountProperties {

    /** 令牌签名密钥（HS256，对称）；不设默认值，缺失即启动失败 */
    private String jwtSecret;

    /** 令牌有效期（小时），默认 7 天 */
    private int tokenTtlHours = 168;

    /** 注册是否强制邮箱验证码；联调期可临时关闭 */
    private boolean requireEmailVerification = true;

    /** 验证码位数（6 位数字） */
    private int codeLength = 6;

    /** 验证码有效期（分钟） */
    private int codeTtlMinutes = 10;

    /**
     * 验证码哈希 pepper。留空表示不掺 pepper（仅用于本地联调）；
     * 生产必须配置，否则库被读后可用彩虹表反查 6 位数字。
     */
    private String codePepper = "";

    /**
     * 联调模式：验证码只写日志、不发邮件。
     * 用于 SMTP 未配置时打通「注册 / 找回密码」链路，避免被邮件通道阻塞。
     */
    private boolean codeLogOnly = false;

    /** 密码最小长度（BCrypt 上限 72 字节） */
    private int passwordMinLength = 8;

    /** 密码是否要求同时含字母与数字 */
    private boolean passwordRequireAlnum = true;

    private Risk risk = new Risk();

    /**
     * 账号风控阈值。
     *
     * <p><b>单位说明</b>：{@code RateLimitService} 的窗口以「分钟」为单位，
     * 故 {@code codeSendCooldownSeconds}（秒）在调用处向上取整换算为分钟，
     * 其余阈值均为分钟窗口。
     */
    @Data
    public static class Risk {

        /** 登录：同一账号连续失败达此值即锁定 */
        private int loginAccountFailMax = 5;
        /** 登录：账号锁定时长（分钟） */
        private int loginAccountLockMinutes = 15;

        /** 登录：同一 IP 失败窗口内上限（挡撞库扫描） */
        private int loginIpFailMax = 20;
        /** 登录：IP 失败计数窗口（分钟） */
        private int loginIpFailWindowMinutes = 10;

        /** 验证码：同一邮箱发送冷却（秒） */
        private int codeSendCooldownSeconds = 60;
        /** 验证码：同一邮箱窗口内发送上限 */
        private int codeSendEmailMax = 5;
        /** 验证码：邮箱发送计数窗口（分钟） */
        private int codeSendEmailWindowMinutes = 60;
        /** 验证码：同一 IP 窗口内发送上限 */
        private int codeSendIpMax = 10;

        /** 验证码：单个码的最大校验失败次数，超过即作废 */
        private int codeVerifyFailMax = 5;

        /** 注册：同一 IP 窗口内上限 */
        private int registerIpMax = 5;
        /** 注册：同一邮箱窗口内上限 */
        private int registerEmailMax = 3;

        /** 找回密码：同一邮箱窗口内上限 */
        private int resetEmailMax = 5;
        /** 找回密码：同一 IP 窗口内上限 */
        private int resetIpMax = 10;

        /** 改密：同一用户窗口内上限 */
        private int changePasswordUserMax = 3;
        /** 改密：计数窗口（分钟） */
        private int changePasswordWindowMinutes = 60;

        /** 注册：IP / 邮箱计数窗口（分钟） */
        private int registerWindowMinutes = 60;
        /** 找回密码：IP / 邮箱计数窗口（分钟） */
        private int resetWindowMinutes = 60;
    }
}
