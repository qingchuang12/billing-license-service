package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.math.BigDecimal;
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
    
    @Column(nullable = false)
    private String currency = "USD";

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
    private BillingCycle billingCycle = BillingCycle.ONE_TIME;

    /**
     * 产品档位/等级（PRO 基础版 / PRO_PLUS 高级版）。
     * 与 {@link #billingCycle} 组合表达三种付费模式：
     * 买断 = tier×ONE_TIME/LIFETIME；订阅 = tier×MONTHLY/YEARLY；高级版 = PRO_PLUS。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanTier tier = PlanTier.PRO;

    /**
     * 权益特性清单（JSON 数组字符串，如 ["OFFLINE","MULTI_DEVICE","PRIORITY_SUPPORT"]）。
     * 用于区分 Pro 与 Pro Plus 的权益差异，并写入 License payload 供客户端离线校验。
     */
    @Column(columnDefinition = "TEXT")
    private String features;

    @Column(nullable = false)
    private Integer licenseDurationDays = 365;
    
    @Column(nullable = false)
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
    public BigDecimal getPriceForRegion(boolean domestic) {
        BigDecimal primary = domestic ? priceCny : priceUsd;
        if (primary != null) {
            return primary;
        }
        BigDecimal secondary = domestic ? priceUsd : priceCny;
        if (secondary != null) {
            return secondary;
        }
        return price;
    }

    /** 双币种定价（B19）：下单币种随区域走，与 {@link #getPriceForRegion} 配套 */
    public String getCurrencyForRegion(boolean domestic) {
        return domestic ? "CNY" : "USD";
    }
}
