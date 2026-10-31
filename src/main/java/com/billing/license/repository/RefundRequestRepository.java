package com.billing.license.repository;

import com.billing.license.entity.RefundRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {

    Optional<RefundRequest> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundRequest r WHERE r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") UUID id);

    Optional<RefundRequest> findByRefundRequestId(String refundRequestId);

    List<RefundRequest> findTop50ByStatusInOrderByUpdatedAtAsc(Collection<RefundRequest.Status> statuses);

    List<RefundRequest> findByStatusInOrderByUpdatedAtAsc(Collection<RefundRequest.Status> statuses);
}
