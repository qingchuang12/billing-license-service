package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * License entity representing a software license
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "licenses")
public class License {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private String licenseKey;
    
    @Column(nullable = false)
    private UUID customerId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LicenseStatus status = LicenseStatus.ACTIVE;
    
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;
    
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;
    
    @Column(columnDefinition = "TEXT")
    private String signedToken;
    
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
        if (issuedAt == null) {
            issuedAt = LocalDateTime.now();
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // 兼容方法 - 供LicenseService使用
    public String getMachineCode() {
        return this.order != null ? this.order.getMachineCode() : null;
    }

    // 内部类 Builder 扩展方法
    public static class LicenseBuilder {
        public LicenseBuilder machineCode(String machineCode) {
            // 这是一个兼容方法，实际机器码存储在关联的 Order 中
            return this;
        }
    }
    
    public enum LicenseStatus {
        ACTIVE,
        EXPIRED,
        REVOKED,
        SUSPENDED,
        PENDING_ACTIVATION
    }
}
