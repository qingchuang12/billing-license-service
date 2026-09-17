package com.billing.license.entity;

import com.billing.license.exception.BusinessException;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Product entity representing a billable product/plan
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "products")
public class Product {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String sku;
    
    @Column(nullable = false)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @Column(nullable = false)
    private BigDecimal price;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Currency currency = Currency.USD;

    /**
     * 人民币定价（国内下单使用）。双币种定价（B19）：与 {@link #priceUsd} 二选一按区域取用。
     */
    @Column(name = "price_cny")
    private BigDecimal priceCny;

    /**
     * 美元定价（国际下单使用）。双币种定价（B19）：沿用原 {@code price} 字段语义。
     */
    @Column(name = "price_usd")
    private BigDecimal priceUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BillingCycle billingCycle = BillingCycle.ONE_TIME;

    /**
     * 产品档位/等级（PRO 基础版 / PRO_PLUS 高级版）。
     * 与 {@link #billingCycle} 组合表达三种付费模式：
     * 买断 = tier×ONE_TIME/LIFETIME；订阅 = tier×MONTHLY/YEARLY；高级版 = PRO_PLUS。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PlanTier tier = PlanTier.PRO;

    /**
     * 权益特性清单（JSON 数组字符串，如 ["OFFLINE","MULTI_DEVICE","PRIORITY_SUPPORT"]）。
     * 用于区分 Pro 与 Pro Plus 的权益差异，并写入 License payload 供客户端离线校验。
     */
    @Column(columnDefinition = "TEXT")
    private String features;

    @Column(nullable = false)
    @Builder.Default
    private Integer licenseDurationDays = 365;

    /**
     * 更新权益截止天数（自签发日起算，签发时换算为 payload 的 {@code update_until} 绝对秒）。
     * 语义：NULL 或 &lt;= 0 均表示「不限制」，不写入 payload（当前不支持用 0 表达「不含更新」）；
     * 常见取值 365 / 730。
     */
    @Column(name = "update_until_days")
    private Integer updateUntilDays;

    /**
     * 允许使用的大版本上限（写入 payload 的 {@code max_major_version}）。
     * 语义：NULL 或 &lt;= 0 均表示「不限制」，不写入 payload，与 {@link #updateUntilDays} 口径一致。
     */
    @Column(name = "max_major_version")
    private Integer maxMajorVersion;
    
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    public enum BillingCycle {
        ONE_TIME,
        MONTHLY,
        QUARTERLY,
        YEARLY,
        LIFETIME
    }

    /**
     * 双币种定价（B19）：按区域取价。
     * 国内优先取 {@link #priceCny}（CNY），国际优先取 {@link #priceUsd}（USD）；
     * 缺失时回退另一档，最后兜底 {@link #price} 原字段，避免取到 null。
     */
    // C6：双币种定价——目标档位缺失即拒绝下单，禁止跨币种回退（金额取对侧、币种按区域 = 静默资损）。
    public BigDecimal getPriceForRegion(boolean domestic) {
        BigDecimal price = domestic ? priceCny : priceUsd;
        if (price == null) {
            throw new BusinessException("PRICE_NOT_CONFIGURED",
                "产品未配置" + (domestic ? "国内(CNY)" : "国际(USD)") + "价格，无法下单");
        }
        return price;
    }

    /** 双币种定价（B19）：下单币种随区域走，与 {@link #getPriceForRegion} 配套（币种与价格同源判定） */
    public Currency getCurrencyForRegion(boolean domestic) {
        return domestic ? Currency.CNY : Currency.USD;
    }
}
