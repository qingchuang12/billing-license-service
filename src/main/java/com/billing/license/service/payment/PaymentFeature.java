package com.billing.license.service.payment;

/**
 * 支付功能开关。数据库 {@code payment_feature_settings} 覆盖默认值；
 * {@code implemented=false} 表示接口/产品尚待接入，Web 端只展示不可开启。
 */
public enum PaymentFeature {

    ALIPAY_WAP("alipay.wap", "支付宝手机网站支付", true, true),
    ALIPAY_RECURRING("alipay.recurring", "支付宝周期扣款", false, false),
    ALIPAY_AUTO_RECONCILE("alipay.auto-reconcile", "支付宝自动对账", false, false),
    ALIPAY_TRANSFER("alipay.transfer", "支付宝单笔转账", false, false),
    ALIPAY_PREAUTH("alipay.preauth", "支付宝预授权", false, false),
    ALIPAY_HUABEI_INSTALLMENT("alipay.huabei-installment", "花呗分期", true, true),
    ALIPAY_INVOICE("alipay.invoice", "支付宝发票", false, false),

    CREEM_SUBSCRIPTION("creem.subscription", "Creem 订阅", true, true),
    CREEM_TAX_INVOICE("creem.tax-invoice", "Creem 税务/发票", false, false),
    CREEM_EVENT_NOTIFICATION("creem.event-notification", "Creem 事件通知", true, true),
    CREEM_CUSTOMER_CREDITS("creem.customer-credits", "Creem Customer Credits", false, false),
    CREEM_CUSTOMER_PORTAL("creem.customer-portal", "Creem Customer Portal", false, false);

    private final String key;
    private final String displayName;
    private final boolean defaultEnabled;
    private final boolean implemented;

    PaymentFeature(String key, String displayName, boolean defaultEnabled, boolean implemented) {
        this.key = key;
        this.displayName = displayName;
        this.defaultEnabled = defaultEnabled;
        this.implemented = implemented;
    }

    public String key() { return key; }
    public String displayName() { return displayName; }
    public boolean defaultEnabled() { return defaultEnabled; }
    public boolean implemented() { return implemented; }

    public static PaymentFeature fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (PaymentFeature feature : values()) {
            if (feature.key.equalsIgnoreCase(key.trim())) {
                return feature;
            }
        }
        return null;
    }
}
