package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.entity.AuditLog;
import com.billing.license.entity.LicenseEvent;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.repository.AuditLogRepository;
import com.billing.license.repository.LicenseEventRepository;
import com.billing.license.repository.PaymentEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 保留期清理单测（#13）：覆盖「只删超期行」「保留期关掉即不清理」「分批循环终止」三条主线，
 * 以及天数为 0 时**绝不访问该表**——配置误设成 0 不该变成清空全表。
 */
class RetentionCleanupServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final PaymentEventRepository paymentEventRepository = mock(PaymentEventRepository.class);
    private final LicenseEventRepository licenseEventRepository = mock(LicenseEventRepository.class);
    private final BillingProperties properties = new BillingProperties();
    private RetentionCleanupService service;

    @BeforeEach
    void setUp() {
        service = new RetentionCleanupService(
                auditLogRepository, paymentEventRepository, licenseEventRepository, properties);
    }

    private static List<AuditLog> auditBatch(int n) {
        List<AuditLog> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            batch.add(AuditLog.builder().action("REFUND_ORDER").success(true).build());
        }
        return batch;
    }

    @Test
    void purgeExpiredRecords_deletesOnlyExpiredRows() {
        properties.getRetention().setAuditLogDays(365);
        properties.getRetention().setPaymentEventDays(0);
        properties.getRetention().setLicenseEventDays(0);

        AtomicInteger calls = new AtomicInteger();
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class), any()))
                .thenAnswer(inv -> calls.getAndIncrement() == 0 ? auditBatch(3) : List.of());

        RetentionCleanupService.PurgeResult result = service.purgeExpiredRecords();

        assertEquals(3, result.auditLogs());
        assertEquals(0, result.paymentEvents());
        assertEquals(0, result.licenseEvents());
        verify(auditLogRepository).deleteAll(argThat(list -> list.size() == 3));
    }

    @Test
    void purgeExpiredRecords_skipsTableWhenRetentionDisabled() {
        properties.getRetention().setAuditLogDays(0);
        properties.getRetention().setPaymentEventDays(0);
        properties.getRetention().setLicenseEventDays(0);

        RetentionCleanupService.PurgeResult result = service.purgeExpiredRecords();

        assertEquals(0, result.total());
        verify(auditLogRepository, never()).findByCreatedAtBefore(any(), any());
        verify(paymentEventRepository, never()).findByCreatedAtBefore(any(), any());
        verify(licenseEventRepository, never()).findByCreatedAtBefore(any(), any());
    }

    @Test
    void purgeExpiredRecords_loopsUntilShortBatch() {
        properties.getRetention().setAuditLogDays(180);
        properties.getRetention().setPaymentEventDays(0);
        properties.getRetention().setLicenseEventDays(0);
        // 服务对过小批量有兜底下限（MIN_BATCH_SIZE=100），故按真实量级构造：满批 1 次 + 短批 1 次
        properties.getRetention().setBatchSize(100);

        AtomicInteger calls = new AtomicInteger();
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class), any()))
                .thenAnswer(inv -> switch (calls.getAndIncrement()) {
                    case 0 -> auditBatch(100);  // 满批，继续
                    case 1 -> auditBatch(50);   // 短批，终止
                    default -> List.of();
                });

        RetentionCleanupService.PurgeResult result = service.purgeExpiredRecords();

        assertEquals(150, result.auditLogs());
        verify(auditLogRepository, times(2)).deleteAll(any());
        assertEquals(2, calls.get(), "短批后不应再发起下一轮查询");
    }

    @Test
    void purgeExpiredRecords_clampsUndersizedBatchConfig() {
        properties.getRetention().setAuditLogDays(30);
        properties.getRetention().setPaymentEventDays(0);
        properties.getRetention().setLicenseEventDays(0);
        properties.getRetention().setBatchSize(1); // 误设成过小：应被兜底下限抬起来，而不是每批删 1 行

        AtomicInteger calls = new AtomicInteger();
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class), any()))
                .thenAnswer(inv -> calls.getAndIncrement() == 0 ? auditBatch(1) : List.of());

        RetentionCleanupService.PurgeResult result = service.purgeExpiredRecords();

        assertEquals(1, result.auditLogs());
        assertEquals(1, calls.get(), "小批量被兜底后首批即短批，应一轮结束");
    }

    @Test
    void purgeExpiredRecords_coversAllThreeTables() {
        properties.getRetention().setAuditLogDays(365);
        properties.getRetention().setPaymentEventDays(365);
        properties.getRetention().setLicenseEventDays(180);

        AtomicInteger auditCalls = new AtomicInteger();
        when(auditLogRepository.findByCreatedAtBefore(any(LocalDateTime.class), any()))
                .thenAnswer(inv -> auditCalls.getAndIncrement() == 0 ? auditBatch(1) : List.of());
        AtomicInteger paymentCalls = new AtomicInteger();
        when(paymentEventRepository.findByCreatedAtBefore(any(LocalDateTime.class), any()))
                .thenAnswer(inv -> paymentCalls.getAndIncrement() == 0
                        ? List.of(PaymentEvent.builder().provider("STRIPE").eventId("evt_1").build())
                        : List.of());
        AtomicInteger licenseCalls = new AtomicInteger();
        when(licenseEventRepository.findByCreatedAtBefore(any(LocalDateTime.class), any()))
                .thenAnswer(inv -> licenseCalls.getAndIncrement() == 0
                        ? List.of(LicenseEvent.builder().licenseKey("LIC-1").build())
                        : List.of());

        RetentionCleanupService.PurgeResult result = service.purgeExpiredRecords();

        assertEquals(1, result.auditLogs());
        assertEquals(1, result.paymentEvents());
        assertEquals(1, result.licenseEvents());
        assertEquals(3, result.total());
        verify(paymentEventRepository).deleteAll(any());
        verify(licenseEventRepository).deleteAll(any());
    }

    /** 断言被删批次的行数（避免只验「删过」而漏掉删多了） */
    private static List<AuditLog> argThat(java.util.function.Predicate<List<AuditLog>> predicate) {
        org.mockito.ArgumentMatcher<List<AuditLog>> matcher = predicate::test;
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
