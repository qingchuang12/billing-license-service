package com.billing.service.payment.strategy;

import java.util.Map;

/**
 * 支付响应
 */
public class PaymentResponse {
    private String paymentId;
    private String status; // PENDING, SUCCESS, FAILED
    private String payUrl; // 支付链接或二维码URL
    private String qrCode; // 二维码内容
    private Map<String, String> extraParams; // 额外参数（如微信的appId, timeStamp等）
    
    public PaymentResponse() {}
    
    public PaymentResponse(String paymentId, String status) {
        this.paymentId = paymentId;
        this.status = status;
    }
    
    // Getters and Setters
    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getPayUrl() { return payUrl; }
    public void setPayUrl(String payUrl) { this.payUrl = payUrl; }
    
    public String getQrCode() { return qrCode; }
    public void setQrCode(String qrCode) { this.qrCode = qrCode; }
    
    public Map<String, String> getExtraParams() { return extraParams; }
    public void setExtraParams(Map<String, String> extraParams) { this.extraParams = extraParams; }
}
