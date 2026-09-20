package com.billing.license.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 机器码首次出现账本（C8，2026-09-20）。
 *
 * <p>用途：客户端试用账本存在本地，删档重装即可再领一次试用。有了这条记录，服务端就能回答
 * 「这台机器最早什么时候接触过本产品」，客户端据此把试用起点回溯到那个时间——
 * 删档重来拿到的仍是**已经过期**的试用，而不是全新 60 天。
 *
 * <p><b>口径</b>：{@code firstSeenAt} 只写一次、永不更新（判定权威值）；
 * {@code lastSeenAt} 每次见到就刷新（仅用于运营观察）。
 *
 * <p><b>不存 PII</b>：只有机器码（本地硬件派生的随机串），不落邮箱/订单/客户 id。
 */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "machine_first_seen")
public class MachineFirstSeen {

    /** 机器码（客户端弱/强绑定码，取服务端实际收到的那个） */
    @Id
    @Column(name = "machine_code", nullable = false, length = 64)
    private String machineCode;

    /** 首次见到该机器的时间；一经写入不再变更 */
    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt;

    /** 最近一次见到该机器的时间 */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** 首次见到的来源：REDEEM（兑换）/ PURCHASE（购买下单）/ PROBE（客户端首跑探测） */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    public static MachineFirstSeen firstTime(String machineCode, LocalDateTime now, String source) {
        return MachineFirstSeen.builder()
            .machineCode(machineCode)
            .firstSeenAt(now)
            .lastSeenAt(now)
            .source(source)
            .build();
    }
}
