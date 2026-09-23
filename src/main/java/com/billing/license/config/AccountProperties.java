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
 * （与 {@code DB_PASSWORD} 的安全风格一致）。管理端鉴权复用同一账号体系（管理员 JWT，A12 / 2026-09-23 移除 {@code ADMIN_API_KEYS}）。
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

    /** 二次因子（MFA）配置（plan-7.0 / M1，B8 定案） */
    private Mfa mfa = new Mfa();

    /**
     * 二次因子（MFA）配置。
     *
     * <p><b>为什么只有一个密钥 {@code key}</b>：MFA 需要两个互相隔离的密钥——票据签名密钥与
     * TOTP 密钥加密密钥——若让运维各配一个，是两份额外的部署负担。这里改为用同一个主密钥
     * 经 HMAC-SHA256 派生两个<b>互不相关</b>的子密钥（见 {@code MfaKeyDeriver}），
     * 既满足密钥隔离（一处泄漏不波及另一用途），运维也只多配一个环境变量。
     *
     * <p><b>fail-fast</b>：{@code key} 不设默认值，缺失或过短即启动失败——与
     * {@code jwt-secret} 同风格。宁可升级时明确报错，也不要留下「看似正常、实则密钥缺失」
     * 的中间态（那种状态下 MFA 要么不可用、要么降级为明文存储）。
     */
    @Data
    public static class Mfa {

        /**
         * MFA 主密钥（≥32 字节）；派生票据签名密钥与 TOTP 密钥加密密钥。
         * 不设默认值，缺失即启动失败（环境变量 {@code ACCOUNT_MFA_KEY}）。
         */
        private String key;

        /** 一次性登录票据有效期（秒）。短时效——票据只是「密码已通过」的临时凭证 */
        private int ticketTtlSeconds = 300;

        /** TOTP 时间步长（秒），RFC 6238 标准值为 30 */
        private int totpStepSeconds = 30;

        /** TOTP 校验允许的时间步容错（前后各 N 步），补偿客户端与服务器的时钟偏差 */
        private int totpWindowSteps = 1;

        /** 单张票据允许的动态码校验失败次数，达限即作废票据（6 位码空间仅 10^6） */
        private int verifyFailMax = 5;

        /**
         * 是否允许邮箱验证码作为兜底因子（恢复路径）。
         *
         * <p><b>默认开启但档次低于 TOTP</b>：邮箱与登录标识同源，其「因子独立性」弱于
         * 独立的认证器设备。关闭后，认证器丢失只能走运维脚本 {@code reset-admin-mfa.sql}。
         */
        private boolean emailFallbackEnabled = true;

        /** otpauth URI 的 issuer 展示名（认证器 App 中显示） */
        private String issuer = "BillingLicenseService";
    }

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
