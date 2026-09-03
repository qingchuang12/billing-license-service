package com.billing.license.repository;

import com.billing.license.entity.RedeemCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RedeemCodeRepository extends JpaRepository<RedeemCode, UUID> {
    // B14：悲观行锁，序列化同一兑换码的并发兑换，防止并发重复签发
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RedeemCode r WHERE r.code = :code")
    Optional<RedeemCode> findByCode(@Param("code") String code);

    boolean existsByCode(String code);

    // B13：按订单号查询已生成的兑换码，供轮询接口幂等返回
    List<RedeemCode> findByOrderId(String orderId);
}
