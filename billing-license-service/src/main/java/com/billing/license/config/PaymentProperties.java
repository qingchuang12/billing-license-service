package com.billing.license.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

/**
 * Payment configuration properties
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {
    
    /**
     * Payment provider: stripe, paypal, alipay, wechat_pay
     */
    private String provider = "stripe";
    
    private StripeConfig stripe = new StripeConfig();
    private PaypalConfig paypal = new PaypalConfig();
    
    @Data
    public static class StripeConfig {
        private String apiKey = "sk_test_xxx";
        private String webhookSecret = "whsec_xxx";
    }
    
    @Data
    public static class PaypalConfig {
        private String clientId = "";
        private String clientSecret = "";
        private String mode = "sandbox";
    }
}
