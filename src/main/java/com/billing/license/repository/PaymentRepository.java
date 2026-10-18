package com.billing.license.repository;

import com.billing.license.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 支付记录 Repository
 *
 * <p>继承 {@link JpaSpecificationExecutor} 以支持「交易流水明细」接口按渠道 / 状态 / 币种 / 时间范围
 * 的可选条件 + 分页查询（Spring Data 派生查询难以表达可选 AND 组合，Specification 更干净）。
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByOrderIdStr(String orderIdStr);

    /**
     * #4（退款并发保护）：带**悲观写锁**按订单取支付记录，作为退款入口的串行化令牌。
     *
     * <p>不直接锁订单行是因为退款失败态由 {@code REQUIRES_NEW} 内层事务写同一订单行，
     * 外层若持有订单行 {@code FOR UPDATE} 会把内层 UPDATE 锁到死等。取舍与后果见
     * {@code AdminService#refundOrder} 的注释。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderIdStr = :orderIdStr")
    Optional<Payment> findByOrderIdStrForUpdate(@Param("orderIdStr") String orderIdStr);

    Optional<Payment> findByTransactionId(String transactionId);

    /** 账务：按支付记录创建时间范围取全量（对账差异聚合使用） */
    java.util.List<Payment> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
