package com.billing.license.repository;

import com.billing.license.entity.MachineFirstSeen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 机器码首次出现账本仓储（C8）。
 *
 * <p>{@code @Modifying} 方法要求调用方处于事务中（服务层标注 {@code @Transactional}）。
 */
@Repository
public interface MachineFirstSeenRepository extends JpaRepository<MachineFirstSeen, String> {

    Optional<MachineFirstSeen> findByMachineCode(String machineCode);

    /**
     * 刷新"最近见到"的时间（不碰 first_seen_at）。
     *
     * @return 更新条数；0 表示这台机器此前没见过
     */
    @Modifying
    @Query("UPDATE MachineFirstSeen m SET m.lastSeenAt = :now WHERE m.machineCode = :machineCode")
    int touchLastSeen(@Param("machineCode") String machineCode, @Param("now") LocalDateTime now);
}
