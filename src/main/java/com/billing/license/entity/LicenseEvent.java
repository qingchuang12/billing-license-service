package com.billing.license.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * License 事件实体 - 激活/兑换/重发/作废/校验失败等审计记录
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "license_events")
public class LicenseEvent {

    public enum EventType {
        ISSUED,
        REDEEMED,
        REISSUED,
        REVOKED,
        VERIFY_FAILED,
        /** 释放本机绑定（换绑场景）：仅清空 machineCode，不吊销授权本身 */
        UNBOUND,
        /** 持许可证密钥在登录态下绑定设备（plan-7.0 方案 A 在线激活）；与 UNBOUND 互为反向操作 */
        ACTIVATED,
        /** 客户端兑换/激活后**启动时自动上报机器码**完成补绑（plan-7.0 / D2）；同为绑定动作，仅触发方不同 */
        BOUND_BY_REPORT,
        /**
         * 支付完成后客户端按机器码领取「待激活」授权成功（首次命中留痕，后续轮询不重复写）。
         * 只读领取本身不改变授权状态，留痕是为让售后能区分「用户自己拿到过」与「从未领取」。
         */
        PENDING_QUERIED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "license_id")
    private UUID licenseId;

    @Column(name = "license_key")
    private String licenseKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "machine_id")
    private String machineId;

    @Column
    private String ip;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "app_version")
    private String appVersion;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
