package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Redeem Code entity for license activation codes
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "redeem_codes")
public class RedeemCode {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String code;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RedeemCodeStatus status = RedeemCodeStatus.UNUSED;
    
    @Column(name = "used_by")
    private UUID usedBy;
    
    @Column(name = "used_at")
    private LocalDateTime usedAt;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @Column(nullable = false)
    private Integer maxUses = 1;
    
    @Column(nullable = false)
    private Integer currentUses = 0;
    
    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "order_id")
    private String orderId;
    
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
    
    public enum RedeemCodeStatus {
        UNUSED,
        USED,
        EXPIRED,
        REVOKED
    }
}
