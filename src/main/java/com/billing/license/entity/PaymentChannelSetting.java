package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "payment_channel_settings")
public class PaymentChannelSetting {

    @Id
    @Column(length = 32)
    private String channel;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    @PrePersist
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }
}
