package com.billing.license.repository;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseRepository extends JpaRepository<License, UUID> {
    Optional<License> findByLicenseKey(String licenseKey);
    List<License> findByCustomerId(UUID customerId);
    // 用户自助查询：本人名下 License，签发时间倒序（最新的在前）
    List<License> findByCustomerIdOrderByIssuedAtDesc(UUID customerId);
    List<License> findByOrder(Order order);
    // B13：按订单 ID 查询已签发 License，供轮询接口幂等返回
    List<License> findByOrderId(UUID orderId);
    // plan-4.1：批量按订单取 License（用户订单列表折算可退额，避免逐单查询的 N+1）
    List<License> findByOrderIdIn(java.util.Collection<UUID> orderIds);
    boolean existsByLicenseKey(String licenseKey);
}
