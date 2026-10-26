package com.billing.license.repository;

import com.billing.license.entity.PaymentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    Optional<PaymentEvent> findByProviderAndEventId(String provider, String eventId);

    boolean existsByProviderAndEventId(String provider, String eventId);

    /**
     * #13 保留期清理：按保留期截断点取一批待删记录。
     *
     * <p>保留期（默认 365 天）远大于渠道 webhook 的重投窗口，删掉的是早已不可能重投的
     * 历史事件，不影响 {@link #existsByProviderAndEventId} 的幂等去重语义。
     */
    List<PaymentEvent> findByCreatedAtBefore(LocalDateTime cutoff, Pageable pageable);
}
