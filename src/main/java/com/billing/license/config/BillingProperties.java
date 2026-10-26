package com.billing.license.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

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
     * License 过期检查间隔（小时）。
     *
     * <p>plan-1.0 / S2：此前**无任何消费点**（纯死配置）。现接成 verify 响应里
     * {@code nextCheckAfterMs} 的取值来源——服务端调复核节奏不必客户端发版。
     */
    private Integer licenseCheckIntervalHours = 360;

    /**
     * 支付后「按机器码领取待激活授权」的可领取时间窗（天）。
     *
     * <p>plan-1.0 / S1：只对**签发后 N 天内、且从未成功校验过**的授权开放匿名领取，
     * 使机器码泄漏的危害面收敛到「刚买完还没用过的那几天」，而非终身可捞。
     */
    private Integer pendingLicenseWindowDays = 7;
    
    /**
     * Default license duration in days
     */
    private Integer defaultLicenseDurationDays = 365;

    /**
     * 风控配置（架构十七：IP/邮箱/机器码频控）
     */
    private Risk risk = new Risk();

    /**
     * 自助退款策略（plan-4.1：用户端按使用时间折算退款）
     */
    private Refund refund = new Refund();

    /**
     * 权益特性显示名（中/英），配置驱动（收银台 K16 延伸）。
     * 键必须与 products.features 中的特性键一致（UPPER_SNAKE）；改名只需改此处，前端零发版。
     */
    private Map<String, FeatureLabel> featureLabels = new HashMap<>();

    /**
     * 流水与审计表的保留期（#13）。
     *
     * <p>三张只增不减的表（{@code audit_logs} / {@code payment_events} / {@code license_events}）
     * 按此处天数定期清理超期行。天数取合规口径，改动前须确认与业务/法务要求一致。
     */
    private Retention retention = new Retention();

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

    /**
     * 自助退款策略。
     *
     * <p>折算口径为「全周期线性」：可退额 = 实付额 × 剩余天数 ÷ 总天数，
     * 可退窗口即 License 有效期本身（权益自然到期即不可退）。
     */
    @Data
    public static class Refund {

        /**
         * 可退下限（订单币种）：折算额低于此值不开放自助退款。
         * 避免「剩余比例过低」时小额退款叠加渠道手续费反而倒亏。
         */
        private BigDecimal minAmount = new BigDecimal("1.00");

        /** 同一用户窗口内自助退款申请上限（防刷） */
        private int userRefundMax = 5;
        /** 自助退款频控窗口（分钟） */
        private int userRefundWindowMinutes = 60;
    }

    /** 权益特性显示名（中/英） */
    @Data
    public static class FeatureLabel {
        private String zh;
        private String en;
    }

    /**
     * 流水与审计保留期（#13，川哥 2026-10-25 拍板天数）。
     *
     * <p>任一值 ≤ 0 表示**该表不清理**（保留期关掉），适合合规要求变化时临时停用，
     * 不必改代码。批量大小控制单次事务的删除行数，避免长事务锁表。
     */
    @Data
    public static class Retention {

        /** 管理操作审计日志 {@code audit_logs} 保留天数 */
        private int auditLogDays = 365;

        /** 支付渠道事件 {@code payment_events} 保留天数（远大于渠道重投窗口，不影响幂等去重） */
        private int paymentEventDays = 365;

        /** 领证/核销事件 {@code license_events} 保留天数 */
        private int licenseEventDays = 180;

        /** 单批删除行数上限（每批一个短事务，避免长事务锁表） */
        private int batchSize = 1000;
    }
}
