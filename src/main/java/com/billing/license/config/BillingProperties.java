package com.billing.license.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

/**
 * Billing configuration properties
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {
    
    /**
     * Signature algorithm: ED25519, ES256, ES384, ES512, RS256, RS384, RS512
     */
    private String signatureAlgorithm = "ED25519";
    
    /**
     * Private key path for signing licenses
     */
    private String privateKeyPath = "/keys/private.key";
    
    /**
     * Public key path distributed to clients
     */
    private String publicKeyPath = "/keys/public.key";
    
    /**
     * License expiration check interval in hours
     */
    private Integer licenseCheckIntervalHours = 24;
    
    /**
     * Default license duration in days
     */
    private Integer defaultLicenseDurationDays = 365;

    /**
     * 风控配置（架构十七：IP/邮箱/机器码频控）
     */
    private Risk risk = new Risk();

    @Data
    public static class Risk {
        /** 同一邮箱在窗口期内最大下单/创建收银台次数 */
        private int emailPurchaseMax = 10;
        /** 邮箱购买频控窗口（分钟） */
        private int emailPurchaseWindowMinutes = 60;

        /** 同一机器码在窗口期内最大换机（reissue）次数 */
        private int machineReissueMax = 3;
        /** 机器码换机频控窗口（分钟） */
        private int machineReissueWindowMinutes = 60;

        /** 单个 License 累计最大重发次数（跨生命周期） */
        private int licenseReissueMax = 5;

        /** 同一 IP 在窗口期内最大兑换成功次数 */
        private int redeemIpMax = 20;
        /** 兑换 IP 频控窗口（分钟） */
        private int redeemIpWindowMinutes = 10;

        /** 同一 IP 在窗口期内最大兑换失败（暴力猜测）次数 */
        private int redeemFailureMax = 10;
        /** 兑换失败频控窗口（分钟） */
        private int redeemFailureWindowMinutes = 10;
    }
}
