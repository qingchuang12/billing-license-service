package com.billing.license.dto;

import com.billing.license.entity.PaymentEvent;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class PaymentEventView {
    private UUID id;
    private String provider;
    private String eventId;
    private String eventType;
    private String orderId;
    private String providerPaymentId;
    private BigDecimal amount;
    private String currency;
    private Boolean processed;
    private LocalDateTime createdAt;
    private String payloadPreview;

    public static PaymentEventView from(PaymentEvent e) {
        String payload = e.getPayload();
        if (payload != null && payload.length() > 500) {
            payload = payload.substring(0, 500) + "...";
        }
        return PaymentEventView.builder()
            .id(e.getId())
            .provider(e.getProvider())
            .eventId(e.getEventId())
            .eventType(e.getEventType())
            .orderId(e.getOrderId())
            .providerPaymentId(e.getProviderPaymentId())
            .amount(e.getAmount())
            .currency(e.getCurrency())
            .processed(e.getProcessed())
            .createdAt(e.getCreatedAt())
            .payloadPreview(payload)
            .build();
    }
}
