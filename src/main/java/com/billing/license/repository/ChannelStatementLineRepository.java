package com.billing.license.repository;

import com.billing.license.entity.ChannelStatementLine;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelStatementLineRepository extends JpaRepository<ChannelStatementLine, Long> {

    Optional<ChannelStatementLine> findByProviderAndChannelTxnIdAndEntryType(
        PaymentMethod provider, String channelTxnId, PaymentLedgerEntry.EntryType entryType);

    List<ChannelStatementLine> findByProviderAndStatementDateBetweenOrderByStatementDateAscIdAsc(
        PaymentMethod provider, LocalDate from, LocalDate to);

    List<ChannelStatementLine> findByMatchStatus(String matchStatus);
}
