package com.billing.license.service;

import com.billing.license.entity.MachineFirstSeen;
import com.billing.license.repository.MachineFirstSeenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 机器码首次出现账本（C8，2026-09-20）。
 *
 * <p><b>解决的问题</b>：客户端试用账本存在本地，删掉存档重装即可再领一次 60 天试用。
 * 服务端此前对「这台机器是否来过」毫无记忆，客户端离线也无从判断。
 *
 * <p><b>做法</b>：服务端为每台机器记一行 {@code first_seen_at}（首次见到、永不更新），
 * 并在兑换/下单时顺带登记；客户端首跑联网问一次，若这台机器早就被见过，
 * 就把试用起点回溯到那个时间——删档重来拿到的仍是**已过期**的试用。
 *
 * <p><b>边界（必须知道）</b>：完全离线首跑时客户端拿不到服务端时间，仍会发新试用；
 * 待其联网（兑换/探测）后即被回溯纠正。要彻底堵死只能要求首跑强制联网，属另一档产品取舍。
 *
 * <p><b>不存 PII</b>：只记机器码与两个时间戳。
 */
@Service
@RequiredArgsConstructor
public class MachineRegistryService {

    /** 来源标记：兑换 / 购买下单 / 客户端首跑探测 */
    public static final String SRC_REDEEM = "REDEEM";
    public static final String SRC_PURCHASE = "PURCHASE";
    public static final String SRC_PROBE = "PROBE";

    private final MachineFirstSeenRepository repository;

    /**
     * 登记「见到这台机器」，返回其首次见到时间。
     *
     * <p>幂等：已存在则只刷新 {@code last_seen_at}，{@code first_seen_at} 保持不变——
     * 这是判定用的权威值，绝不能被后续访问改写。
     *
     * @param machineCode 机器码；空值直接忽略（无机器码的 License 不参与该账本）
     * @return 首次见到时间；machineCode 为空时返回 empty
     */
    @Transactional
    public Optional<LocalDateTime> touch(String machineCode, String source) {
        if (machineCode == null || machineCode.isBlank()) {
            return Optional.empty();
        }
        String code = machineCode.trim();
        LocalDateTime now = LocalDateTime.now();

        Optional<MachineFirstSeen> existing = repository.findByMachineCode(code);
        if (existing.isPresent()) {
            repository.touchLastSeen(code, now);
            return Optional.ofNullable(existing.get().getFirstSeenAt());
        }
        try {
            repository.save(MachineFirstSeen.firstTime(code, now, source));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发下另一条请求先落库：以库里那条为准（first_seen_at 更早，正是我们要的口径）
            return repository.findByMachineCode(code).map(MachineFirstSeen::getFirstSeenAt);
        }
        return Optional.of(now);
    }

    /** 只读查询：这台机器此前是否来过（不登记，客户端首跑探测用） */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> firstSeenAt(String machineCode) {
        if (machineCode == null || machineCode.isBlank()) {
            return Optional.empty();
        }
        return repository.findByMachineCode(machineCode.trim()).map(MachineFirstSeen::getFirstSeenAt);
    }
}
