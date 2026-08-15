package com.billing.license.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一收银台响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResponse {

    private String checkoutId;

    private String orderNumber;

    private String status;

    private String provider;

    private java.util.List<String> paymentMethods;

    private String payUrl;

    private String paymentMode;

    private String qrcode;

    private String redirectUrl;

    private Long expiresAt;

    private String license;

    private String redeemCode;
}
