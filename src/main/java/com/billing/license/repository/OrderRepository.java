package com.billing.license.repository;

import com.billing.license.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByOrderNumber(String orderNumber);
    java.util.List<Order> findByStatus(Order.OrderStatus status);
    // 用户自助查询：本人名下订单，下单时间倒序（最新的在前）
    java.util.List<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /** 账务：按订单创建时间范围取订单（收入/趋势/对账聚合使用） */
    java.util.List<Order> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
