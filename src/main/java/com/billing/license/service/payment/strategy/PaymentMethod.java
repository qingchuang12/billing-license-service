package com.billing.license.service.payment.strategy;

/**
 * 支付方式枚举
 * 
 * 定义系统支持的所有支付渠道，分为国内和国际两类
 */
public enum PaymentMethod {
    /** 支付宝 - 国内主流支付方式 */
    ALIPAY("支付宝", "国内"),
    /** 微信支付 - 国内主流支付方式 */
    WECHAT_PAY("微信支付", "国内"),
    /** 云闪付/银联 - 国内支付方式 */
    UNIONPAY("云闪付", "国内"),
    /** Stripe - 国际信用卡支付 */
    STRIPE("Stripe", "国际"),
    /** Paddle - 国际 SaaS 订阅支付（Merchant of Record） */
    PADDLE("Paddle", "国际"),
    /** PayPal - 国际钱包支付 */
    PAYPAL("PayPal", "国际");
    
    /** 支付方式中文名称 */
    private final String name;
    /** 支付区域：国内/国际 */
    private final String region;
    
    /**
     * 构造函数
     * @param name 支付方式名称
     * @param region 支付区域
     */
    PaymentMethod(String name, String region) {
        this.name = name;
        this.region = region;
    }
    
    /**
     * 获取支付方式名称
     * @return 支付方式名称
     */
    public String getName() { return name; }
    
    /**
     * 获取支付区域
     * @return 支付区域（国内/国际）
     */
    public String getRegion() { return region; }
    
    /**
     * 判断是否为国内支付方式
     * @return true-国内，false-国际
     */
    public boolean isDomestic() {
        return "国内".equals(region);
    }
    
    /**
     * 判断是否为国际支付方式
     * @return true-国际，false-国内
     */
    public boolean isInternational() {
        return "国际".equals(region);
    }
}
