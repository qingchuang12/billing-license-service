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
}
