package com.billing.license.service;

import com.billing.license.entity.AuditLog;
import com.billing.license.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 操作审计日志服务（H11）。
 * <p>
 * 管理后台的敏感操作（退款、作废 License、换机重发等）必须留痕：
 * 记录「谁（密钥哈希，不记明文）、做了什么、作用于谁、结果如何、时间、IP」。
 * <p>
 * 方案 B（v2.8）：保留独立命名 logger "AUDIT" 输出（便于接入独立日志管道 / 合规留存），
 * 新增 {@link #persist(AuditLog)} 异步独立事务落库（由 {@code AuditAspect} 调用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    private final AuditLogRepository auditLogRepository;

    /**
     * 记录一条管理操作审计（结构化单行 AUDIT logger，与业务日志分离）。
     */
    public void audit(String actorKeyHash, String action, String target, boolean success, String detail) {
        String ts = LocalDateTime.now().toString();
        // 结构化单行，便于日志采集与检索
        auditLog.info("AUDIT ts={} actor={} action={} target={} result={} detail={}",
                ts, actorKeyHash, action, target, success ? "SUCCESS" : "FAIL", detail != null ? detail : "");
    }

    /**
     * 异步独立事务落库：主事务回滚审计仍落库（契合安全审计诉求）；
     * 落库异常仅记日志，不阻断业务主流程。
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(AuditLog record) {
        try {
            auditLogRepository.save(record);
        } catch (Exception e) {
            log.error("审计日志落库失败 actor={} action={}：{}",
                    record.getActor(), record.getAction(), e.getMessage());
        }
    }
}
