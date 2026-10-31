package com.billing.license.repository;

import com.billing.license.entity.FulfillmentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FulfillmentEventRepository extends JpaRepository<FulfillmentEvent, UUID> {

    Optional<FulfillmentEvent> findByOrderIdStrAndEventType(String orderIdStr, String eventType);

    List<FulfillmentEvent> findByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
        List<FulfillmentEvent.Status> statuses, Integer attempts, Pageable pageable);
}
