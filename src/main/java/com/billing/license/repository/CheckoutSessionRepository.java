package com.billing.license.repository;

import com.billing.license.entity.CheckoutSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {

    Optional<CheckoutSession> findByCheckoutId(String checkoutId);

    Optional<CheckoutSession> findByOrderId(UUID orderId);

    Optional<CheckoutSession> findByOrderNumber(String orderNumber);

    /** P16：账号页订单列表批量取「继续支付」会话，避免逐单查询。 */
    List<CheckoutSession> findByOrderNumberIn(Collection<String> orderNumbers);

    /**
     * #50：后台对账扫描——未支付且久未对账的会话（不再依赖页面轮询）。
     *
     * <p>只取 PENDING（真正创建过渠道支付入口）且未超出对账观察窗的会话；
     * CREATED 从未触达渠道，过期后由清理任务直接删除；超出观察窗的留作证据、不再轮询。
     */
    @Query("SELECT s FROM CheckoutSession s WHERE s.status = :status "
        + "AND (s.lastCompensatedAt IS NULL OR s.lastCompensatedAt < :staleBefore) "
        + "AND s.expiresAt > :notBefore")
    List<CheckoutSession> findReconcileCandidates(@Param("status") CheckoutSession.Status status,
                                                  @Param("staleBefore") LocalDateTime staleBefore,
                                                  @Param("notBefore") LocalDateTime notBefore,
                                                  Pageable pageable);

    /**
     * M2 + #50：过期且**从未创建过支付入口**的会话才能删。
     *
     * <p>有支付尝试的会话是「订单 ↔ 渠道交易」的唯一关联：Webhook 丢失时靠它补偿发货，
     * 关单未确认时靠它继续观察。删掉会让已收款订单永远无法交付，故一律保留。
     */
    @Query("SELECT s.id FROM CheckoutSession s WHERE s.status <> :paid AND s.expiresAt < :cutoff "
        + "AND NOT EXISTS (SELECT 1 FROM PaymentAttempt a WHERE a.orderNumber = s.orderNumber)")
    List<UUID> findExpiredUnlinkedIds(@Param("paid") CheckoutSession.Status paid,
                                      @Param("cutoff") LocalDateTime cutoff,
                                      Pageable pageable);

    @Modifying
    @Query("DELETE FROM CheckoutSession s WHERE s.id IN :ids")
    int deleteByIds(@Param("ids") List<UUID> ids);
}
