package com.billing.license.repository;

import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.entity.Currency;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentLedgerRepository extends JpaRepository<PaymentLedgerEntry, Long> {

    Optional<PaymentLedgerEntry> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<PaymentLedgerEntry> findByOrderIdStrOrderByOccurredAtDesc(String orderIdStr);

    List<PaymentLedgerEntry> findByOccurredAtBetween(LocalDateTime from, LocalDateTime to);

    List<PaymentLedgerEntry> findByChannelTxnIdOrderByOccurredAtDesc(String channelTxnId);

    @Query("SELECT e FROM PaymentLedgerEntry e WHERE e.occurredAt BETWEEN :from AND :to "
        + "AND (:provider IS NULL OR e.provider = :provider) "
        + "AND (:type IS NULL OR e.entryType = :type) "
        + "AND (:currency IS NULL OR e.currency = :currency) "
        + "ORDER BY e.occurredAt DESC")
    Page<PaymentLedgerEntry> search(@Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("provider") PaymentMethod provider,
                                    @Param("type") PaymentLedgerEntry.EntryType type,
                                    @Param("currency") Currency currency,
                                    Pageable pageable);
}
