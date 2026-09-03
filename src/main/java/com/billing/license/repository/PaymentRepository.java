package com.billing.license.repository;

import com.billing.license.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 支付记录 Repository
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    
    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByOrderIdStr(String orderIdStr);

    Optional<Payment> findByTransactionId(String transactionId);
}
