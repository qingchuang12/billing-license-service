package com.billing.license.repository;

import com.billing.license.entity.CheckoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {

    Optional<CheckoutSession> findByCheckoutId(String checkoutId);

    Optional<CheckoutSession> findByOrderId(UUID orderId);

    Optional<CheckoutSession> findByOrderNumber(String orderNumber);

    /**
     * M2：清理过期且未支付（已支付会话需保留用于对账）的收银台会话，
     * 避免 expired_at 之前的 CREATED/PENDING/FAILED/EXPIRED/CANCELED 记录无限增长。
     */
    long deleteByStatusNotAndExpiresAtBefore(CheckoutSession.Status status, LocalDateTime expiresAt);
}
