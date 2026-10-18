package com.billing.license.repository;

import com.billing.license.entity.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * #4（资损并发保护）：带**悲观写锁**按订单号取订单，供「先判状态再改」的发货 / 退款入口使用。
     *
     * <p>{@code Order} 无 {@code @Version}、{@code licenses} 又不能对 {@code order_id} 加唯一约束
     * （换机重发会为同一订单留第二张证），故订单行的并发序列化只能靠行锁：
     * 并发双击退款 → 渠道侧退两次款；两个不同 {@code eventId} 的回调并发 → 同一订单签出两张证。
     * 与 {@link RedeemCodeRepository#findByCode} 的 B14 同一先例。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);

    java.util.List<Order> findByStatus(Order.OrderStatus status);

    /** #9（管理端分页）：按状态过滤并分页，替代「无过滤即 findAll() 全表入内存」。 */
    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);
    // 用户自助查询：本人名下订单，下单时间倒序（最新的在前）
    java.util.List<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /** 账务：按订单创建时间范围取订单（收入/趋势/对账聚合使用） */
    java.util.List<Order> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);

    // plan-7.0 账户基础功能 / P1：管理端用户详情的名下订单计数
    long countByCustomerId(UUID customerId);
}
