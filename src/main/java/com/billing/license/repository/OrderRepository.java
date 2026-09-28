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

    // plan-1.0 / S1：按机器码取近期订单，用于捞「机器码只落在订单上、未写进 licenses.machine_code」的签发件
    // （管理端补签发与收银台轮询补偿走 issueLicensesForOrder，只读订单机器码；{@code License#getMachineCode()} 有同款回落）
    java.util.List<Order> findByMachineCodeAndCreatedAtAfter(String machineCode,
                                                              java.time.LocalDateTime after);

    // plan-7.0 账户基础功能 / P1：管理端用户详情的名下订单计数
    long countByCustomerId(UUID customerId);
}
