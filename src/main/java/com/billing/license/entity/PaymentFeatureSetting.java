package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "payment_feature_settings")
public class PaymentFeatureSetting {

    @Id
    @Column(name = "feature_key", length = 64)
    private String featureKey;

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
