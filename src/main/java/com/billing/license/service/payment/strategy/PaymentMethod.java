package com.billing.license.service.payment.strategy;

import com.billing.license.entity.Product;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 支付方式枚举
 *
 * <p>除地区归属外，声明每个渠道的**售卖能力**：
 * <ul>
 *   <li>{@code supportedBillingCycles}：可结算的计费周期。国内渠道只支持一次性买断；
 *       Stripe / Paddle 具备订阅能力，支持全部周期（含订阅周期）。</li>
 *   <li>{@code paymentProducts}：渠道实际提供的支付产品形态（扫码 / 跳转），
 *       供收银台展示与客户端渲染选择。</li>
 * </ul>
 * 能力矩阵是「哪些套餐能在这个渠道卖」的唯一真相：收银台下单、目录展示都按它过滤。
 */
public enum PaymentMethod {
    /** 支付宝 - 国内主流支付方式（绑机走当面付扫码，未绑机走电脑网站支付） */
    ALIPAY("支付宝", "CHINA", oneTimeCycles(),
        EnumSet.of(PaymentProduct.QRCODE, PaymentProduct.REDIRECT, PaymentProduct.WAP)),
    /** 微信支付 - 国内主流支付方式（Native 扫码） */
    WECHAT_PAY("微信支付", "CHINA", oneTimeCycles(), EnumSet.of(PaymentProduct.QRCODE)),
    /** Stripe - 国际信用卡支付，具备订阅能力 */
    STRIPE("Stripe", "GLOBAL", allCycles(), EnumSet.of(PaymentProduct.REDIRECT)),
    /** Paddle - 国际 SaaS 订阅支付（Merchant of Record），具备订阅能力 */
    PADDLE("Paddle", "GLOBAL", allCycles(), EnumSet.of(PaymentProduct.REDIRECT)),
    /** Creem - 国际支付（Merchant of Record），具备订阅能力；下单必带渠道目录产品 ID */
    CREEM("Creem", "GLOBAL", allCycles(), EnumSet.of(PaymentProduct.REDIRECT)),
    /** PayPal - 国际钱包支付（一次性收款） */
    PAYPAL("PayPal", "GLOBAL", oneTimeCycles(), EnumSet.of(PaymentProduct.REDIRECT));

    /** 支付方式中文名称 */
    private final String name;
    /** 支付区域：国内/国际 */
    private final String region;
    /** 该渠道可结算的计费周期集合 */
    private final Set<Product.BillingCycle> supportedCycles;
    /** 该渠道提供的支付产品形态 */
    private final Set<PaymentProduct> paymentProducts;

    PaymentMethod(String name, String region, Set<Product.BillingCycle> supportedCycles,
                  Set<PaymentProduct> paymentProducts) {
        this.name = name;
        this.region = region;
        this.supportedCycles = Collections.unmodifiableSet(EnumSet.copyOf(supportedCycles));
        this.paymentProducts = Collections.unmodifiableSet(EnumSet.copyOf(paymentProducts));
    }

    /** 一次性买断可用的周期：ONE_TIME / LIFETIME */
    private static Set<Product.BillingCycle> oneTimeCycles() {
        return EnumSet.of(Product.BillingCycle.ONE_TIME, Product.BillingCycle.LIFETIME);
    }

    /** 具备订阅能力的渠道：全部周期（含一次性） */
    private static Set<Product.BillingCycle> allCycles() {
        return EnumSet.allOf(Product.BillingCycle.class);
    }

    /** 该渠道能否结算指定计费周期的套餐（旧口径；新代码优先用 {@link #supportsChargeMode}）。 */
    public boolean supports(Product.BillingCycle cycle) {
        return cycle != null && supportedCycles.contains(cycle);
    }

    /**
     * 该渠道能否承担指定收款方式（#57）。
     * 国内渠道与 PayPal 只支持一次性；Stripe/Paddle/Creem 支持自动续扣。
     * 一次性收款允许任意权益期限（例如「一次性 30 天」），不再要求 LIFETIME。
     */
    public boolean supportsChargeMode(com.billing.license.entity.ChargeMode mode) {
        if (mode == null) {
            return false;
        }
        return mode == com.billing.license.entity.ChargeMode.AUTO_RECURRING
            ? supportsRecurring() : true;
    }

    /**
     * 该渠道能否结算指定币种。国内渠道只收 CNY；国际渠道只收非 CNY。
     *
     * <p>不能只看区域或页面过滤：直调 select-provider 可绕过收银台，把 USD 订单交给支付宝
     * 按人民币收款（金额数值相同但币种不同＝实际少收/多收）。
     */
    public boolean supportsCurrency(com.billing.license.entity.Currency currency) {
        if (currency == null) {
            return false;
        }
        return isDomestic() ? currency == com.billing.license.entity.Currency.CNY
            : currency != com.billing.license.entity.Currency.CNY;
    }

    /** 该渠道是否具备订阅（自动续费）能力 */
    public boolean supportsRecurring() {
        return supportedCycles.stream().anyMatch(Product.BillingCycle::isRecurring);
    }

    /** 获取支付方式名称 */
    public String getName() { return name; }

    /** 获取支付区域 */
    public String getRegion() { return region; }

    /** 可结算的计费周期（只读） */
    public Set<Product.BillingCycle> getSupportedBillingCycles() { return supportedCycles; }

    /** 提供的支付产品形态（只读） */
    public Set<PaymentProduct> getPaymentProducts() { return paymentProducts; }

    /**
     * 判断是否为国内支付方式
     * @return true-国内，false-国际
     */
    public boolean isDomestic() {
        return "CHINA".equals(region);
    }

    /**
     * 判断是否为国际支付方式
     * @return true-国际，false-国内
     */
    public boolean isInternational() {
        return "GLOBAL".equals(region);
    }
}
