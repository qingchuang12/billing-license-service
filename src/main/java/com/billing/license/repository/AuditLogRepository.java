package com.billing.license.repository;

import com.billing.license.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /**
     * #13 保留期清理：按保留期截断点取一批待删记录（按时间正序，先删最旧的）。
     * 走 {@link Pageable} 而非一次性派生删除，使清理分批进行——
     * 一次性删几十万行会锁住表并拖长单次事务。
     */
    List<AuditLog> findByCreatedAtBefore(LocalDateTime cutoff, Pageable pageable);
}
