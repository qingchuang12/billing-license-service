package com.billing.license.repository;

import com.billing.license.entity.CheckoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {

    Optional<CheckoutSession> findByCheckoutId(String checkoutId);

    Optional<CheckoutSession> findByOrderId(UUID orderId);

    Optional<CheckoutSession> findByOrderNumber(String orderNumber);
}
