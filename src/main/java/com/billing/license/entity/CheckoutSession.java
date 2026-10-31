package com.billing.license.entity;

import com.billing.license.service.payment.strategy.PaymentMethod;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 统一收银台会话实体 - 记录一次收银台创建到支付完成的生命周期
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "checkout_sessions")
public class CheckoutSession {

    public enum Status {
        CREATED,
        PENDING,
        PAID,
        FAILED,
        EXPIRED,
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "checkout_id", nullable = false, unique = true)
    private String checkoutId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "order_number")
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column
    private PaymentMethod provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private Currency currency;

    @Column
    private BigDecimal amount;

    @Column
    private String locale;

    @Column
    private String country;

    @Column(name = "machine_id")
    private String machineId;

    @Column
    private String email;

    @Column(name = "return_url", columnDefinition = "TEXT")
    private String returnUrl;

    @Column(name = "cancel_url", columnDefinition = "TEXT")
    private String cancelUrl;

    @Column(name = "provider_session_id")
    private String providerSessionId;

    @Column(name = "pay_url", columnDefinition = "TEXT")
    private String payUrl;

    /** 扫码支付二维码内容（恢复支付入口必须原样返回，不能只存跳转地址）。 */
    @Column(name = "qr_code", columnDefinition = "TEXT")
    private String qrCode;

    /** 支付形态：redirect / qrcode；恢复时据此决定返回哪个字段。 */
    @Column(name = "payment_mode", length = 16)
    private String paymentMode;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_compensated_at")
    private LocalDateTime lastCompensatedAt; // R3：上次主动对账补偿时间，用于轮询冷却窗口

    /** #50：渠道确认关单的时间；非空表示该交易已不可能再收款。 */
    @Column(name = "channel_closed_at")
    private LocalDateTime channelClosedAt;

    @Column(columnDefinition = "TEXT")
    private String metadata;

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
}
