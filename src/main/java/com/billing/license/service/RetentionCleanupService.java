package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.repository.AuditLogRepository;
import com.billing.license.repository.LicenseEventRepository;
import com.billing.license.repository.PaymentEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 流水与审计表的保留期清理（#13）。
 *
 * <p>{@code audit_logs} / {@code payment_events} / {@code license_events} 三张表只增不减，
 * 长期膨胀直接恶化备份窗口与按条件查询耗时。本任务按各表保留期（默认 365 / 365 / 180 天）
 * 删除超期行，配置见 {@code billing.retention.*}；任一天数 ≤ 0 即该表不清理。
 *
 * <p><b>为什么分批</b>：一次性删几十万行会形成长事务并锁住表。这里每批取一批、删一批，
 * 批大小由 {@code billing.retention.batch-size} 控制（默认 1000），逐批提交短事务。
 *
 * <p><b>幂等与安全性</b>：只按 {@code created_at < now() - N 天} 删除，不碰任何业务主表；
 * {@code payment_events} 的保留期远大于渠道 webhook 重投窗口，删的是早已不可能重投的事件，
 * 不影响 {@code existsByProviderAndEventId} 的幂等去重（V15 迁移注释有完整论证）。
 *
 * <p>依赖索引：V15 给三张表的 {@code created_at} 各建一条索引，缺它时每批都是全表扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionCleanupService {

    private final AuditLogRepository auditLogRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final LicenseEventRepository licenseEventRepository;
    private final BillingProperties properties;

    /** 一批的兜底下限：配置被误设成 0/负数时按它跑，避免死循环 */
    private static final int MIN_BATCH_SIZE = 100;

    /** 一次任务最多跑多少批：防止数据量异常时长时间占用调度线程 */
    private static final int MAX_BATCHES = 1000;

    /** 清理结果（返回值便于单测断言，不必去猜日志） */
    public record PurgeResult(int auditLogs, int paymentEvents, int licenseEvents) {

        public int total() {
            return auditLogs + paymentEvents + licenseEvents;
        }
    }

    /**
     * 定时清理入口：默认每天跑一次（fixedDelay，上次完成后计），
     * 启动后延迟 5 分钟再跑首轮——避免应用刚启动就叠加一次大批量删除。
     */
    @Scheduled(
            initialDelayString = "${billing.retention.cleanup-initial-delay-ms:300000}",
            fixedDelayString = "${billing.retention.cleanup-interval-ms:86400000}")
    public PurgeResult purgeExpiredRecords() {
        BillingProperties.Retention cfg = properties.getRetention();
        int batchSize = cfg.getBatchSize() > MIN_BATCH_SIZE ? cfg.getBatchSize() : MIN_BATCH_SIZE;

        LocalDateTime auditCutoff = LocalDateTime.now().minusDays(cfg.getAuditLogDays());
        int audit = cfg.getAuditLogDays() > 0
                ? purge("audit_logs", auditCutoff, batchSize,
                        page -> auditLogRepository.findByCreatedAtBefore(auditCutoff, page),
                        batch -> auditLogRepository.deleteAll(batch))
                : 0;

        LocalDateTime paymentCutoff = LocalDateTime.now().minusDays(cfg.getPaymentEventDays());
        int payment = cfg.getPaymentEventDays() > 0
                ? purge("payment_events", paymentCutoff, batchSize,
                        page -> paymentEventRepository.findByCreatedAtBefore(paymentCutoff, page),
                        batch -> paymentEventRepository.deleteAll(batch))
                : 0;

        LocalDateTime licenseCutoff = LocalDateTime.now().minusDays(cfg.getLicenseEventDays());
        int license = cfg.getLicenseEventDays() > 0
                ? purge("license_events", licenseCutoff, batchSize,
                        page -> licenseEventRepository.findByCreatedAtBefore(licenseCutoff, page),
                        batch -> licenseEventRepository.deleteAll(batch))
                : 0;

        if (audit + payment + license > 0) {
            log.info("保留期清理完成：audit_logs={}, payment_events={}, license_events={}",
                    audit, payment, license);
        }
        return new PurgeResult(audit, payment, license);
    }

    /**
     * 分批删除一张表内截断点之前的全部记录。
     *
     * <p>循环终止条件：取回的批次不足一批（已是最后一批）；另设 {@link #MAX_BATCHES} 硬上限，
     * 超出后本轮停止、下一轮继续——宁可多跑一轮，也不要让单次调度无限占用线程。
     */
    private <T> int purge(String table, LocalDateTime cutoff, int batchSize,
                          Function<Pageable, List<T>> fetcher, Consumer<List<T>> deleter) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<T> batch = fetcher.apply(PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break;
            }
            deleter.accept(batch);
            total += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("保留期清理：表 {} 删除 {} 条（截断点 {}）", table, total, cutoff);
        }
        return total;
    }
}
