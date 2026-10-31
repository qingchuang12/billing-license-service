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

    /**
     * 产品码（产品维度，如 ai-tools）：一个产品含多个 SKU 档位。
     * 收银台 GET /api/products?product= 按此过滤目录；历史数据与非目录类产品可为空。
     */
    @Column(name = "product_code")
    private String productCode;

    @Column(nullable = false)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * 英文产品名（收银台双语展示用，K16 延伸）。中文名见 {@link #name}。
     */
    @Column(name = "name_en")
    private String nameEn;

    /**
     * 英文产品描述（收银台双语展示用，K16 延伸）。中文描述见 {@link #description}。
     */
    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;
    
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
     * 收款方式（#57）：一次性扣款 / 渠道自动续扣。
     * 与 {@link #licenseDurationDays}（权益期限）、渠道的二维码/跳转形态相互独立。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "charge_mode", nullable = false, length = 24)
    @Builder.Default
    private ChargeMode chargeMode = ChargeMode.ONE_TIME;

    /**
     * 产品档位/等级（PRO 基础版 / PRO_PLUS 高级版）。
     * 与 {@link #billingCycle} 组合表达三种付费模式：
     * 买断 = tier×ONE_TIME/LIFETIME；订阅 = tier×MONTHLY/HALF_YEAR/QUARTERLY/YEARLY；高级版 = PRO_PLUS。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PlanTier tier = PlanTier.PRO;

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

    /**
     * Stripe 订阅价目 ID（{@code price_} 前缀）：订阅类周期走 Stripe 时的渠道侧价目。
     * 为空时 Stripe 按计费周期内联创建 recurring price，不依赖后台预建价目。
     */
    @Column(name = "stripe_price_id")
    private String stripePriceId;

    /**
     * Paddle 订阅价目 ID（{@code pri_} 前缀）：订阅类周期走 Paddle 时的渠道侧价目。
     * Paddle 只认 catalog price id（不认内联价目），订阅周期下缺失即拒单。
     */
    @Column(name = "paddle_price_id")
    private String paddlePriceId;

    /**
     * Creem 产品 ID（{@code prod_} 前缀）：走 Creem 时的渠道侧目录产品。
     * Creem 下单必带 product_id（无 ad-hoc 内联价格），任意周期下缺失即拒单。
     */
    @Column(name = "creem_product_id")
    private String creemProductId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** 收银台是否展示「推荐」标记（原静态白名单字段，改由后台产品配置维护） */
    @Column(nullable = false)
    @Builder.Default
    private Boolean recommended = false;
    
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
        HALF_YEAR,
        QUARTERLY,
        YEARLY,
        LIFETIME;

        /** 是否订阅类周期（由渠道托管自动续费）；一次性买断为 ONE_TIME / LIFETIME。 */
        public boolean isRecurring() {
            return this == MONTHLY || this == HALF_YEAR || this == QUARTERLY || this == YEARLY;
        }
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
