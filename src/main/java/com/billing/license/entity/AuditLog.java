package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 管理操作审计日志实体（方案 B 落库）。
 * 与 {@code LicenseEvent} 同范式：UUID 主键 + @PrePersist 时间戳。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 操作者标识：管理员密钥哈希前缀 / "client"（客户端自吊销）/ "anonymous" */
    @Column(name = "actor", length = 64)
    private String actor;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target", length = 255)
    private String target;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
