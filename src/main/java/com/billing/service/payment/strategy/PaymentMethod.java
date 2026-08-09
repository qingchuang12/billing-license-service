package com.billing.service.payment.strategy;

/**
 * 支付方式枚举
 */
public enum PaymentMethod {
    ALIPAY("支付宝", "国内"),
    WECHAT_PAY("微信支付", "国内"),
    UNIONPAY("云闪付", "国内"),
    STRIPE("Stripe", "国际"),
    PADDLE("Paddle", "国际"),
    PAYPAL("PayPal", "国际");
    
    private final String name;
    private final String region;
    
    PaymentMethod(String name, String region) {
        this.name = name;
        this.region = region;
    }
    
    public String getName() { return name; }
    public String getRegion() { return region; }
    
    public boolean isDomestic() {
        return "国内".equals(region);
    }
    
    public boolean isInternational() {
        return "国际".equals(region);
    }
}
