package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @JoinColumn(name = "order_id", nullable = true)
    private Order order;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LicenseStatus status = LicenseStatus.ACTIVE;
    
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "machine_code")
    private String machineCode; // 绑定的设备机器码（换机重发时更新）

    @Column(name = "reissued_from")
    private UUID reissuedFrom; // 换机重发时指向原 License ID

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

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
    
    // 设备绑定的唯一真相就是上面的 machine_code 列，**不回落 orders.machine_code**（plan-1.0 / E4 审计丙）。
    // 曾有过的回落（{@code machineCode == null ? order.getMachineCode() : machineCode}）会让「解绑只清本列」
    // 清不掉实际生效的绑定：管理端补签发的件机器码只落在订单上，回落照旧命中 → 解绑返回成功、换机仍被拒。
    // 存量件由 V11__backfill_license_machine_code.sql 抄平到本列；新件三条签发路径统一经
    // {@code LicenseService#bindToMachine} 写本列。恢复回落＝重新引入那条死锁，勿再改回。

    public enum LicenseStatus {
        /** 有效 */
        ACTIVE,
        /** 已过期（verify 时判定超期后落库） */
        EXPIRED,
        /** 已吊销（退款/违规，由管理端发起） */
        REVOKED,
        /** 已换机重发（旧 License 退出使用，新 License 通过 reissuedFrom 指回本记录） */
        REISSUED
    }
}
