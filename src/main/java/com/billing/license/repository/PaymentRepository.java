package com.billing.license.repository;

import com.billing.license.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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

    Optional<Payment> findByTransactionId(String transactionId);

    /** 账务：按支付记录创建时间范围取全量（对账差异聚合使用） */
    java.util.List<Payment> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
