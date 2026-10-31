package com.billing.license.repository;

import com.billing.license.entity.PaymentAttempt;
import com.billing.license.service.payment.strategy.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByPaymentId(String paymentId);

    List<PaymentAttempt> findAllByOrderIdStrOrderByCreatedAtDesc(String orderIdStr);

    List<PaymentAttempt> findByTransactionIdOrderByCreatedAtDesc(String transactionId);

    /** 退款串行化令牌：锁住该订单最近一次成功收款尝试（不再锁订单行，避免 REQUIRES_NEW 互锁）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaymentAttempt a WHERE a.orderIdStr = :orderIdStr AND a.status = :status "
        + "ORDER BY a.createdAt DESC")
    List<PaymentAttempt> findForUpdateByOrderAndStatus(@Param("orderIdStr") String orderIdStr,
                                                       @Param("status") PaymentStatus status);

    Optional<PaymentAttempt> findFirstByOrderIdStrAndStatusOrderByCreatedAtDesc(String orderIdStr,
                                                                                PaymentStatus status);

    List<PaymentAttempt> findByOrderIdStrAndStatus(String orderIdStr, PaymentStatus status);
}
