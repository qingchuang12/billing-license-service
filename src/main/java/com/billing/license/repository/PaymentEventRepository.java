package com.billing.license.repository;

import com.billing.license.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    Optional<PaymentEvent> findByProviderAndEventId(String provider, String eventId);

    boolean existsByProviderAndEventId(String provider, String eventId);
}
