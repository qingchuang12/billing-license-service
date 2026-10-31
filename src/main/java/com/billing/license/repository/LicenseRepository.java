package com.billing.license.repository;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LicenseRepository extends JpaRepository<License, UUID> {
    Optional<License> findByLicenseKey(String licenseKey);

    /**
     * 以下返回集合的查询统一预加载 {@code product}：出参视图都要读 SKU
     * （{@code LicenseResponse#adminView}、{@code LicenseService#pendingLicenses}），
     * OSIV 关闭后不预加载就是「每张证一次产品查询」。
     */
    @EntityGraph(attributePaths = {"product"})
    List<License> findByCustomerId(UUID customerId);

    // #9（管理端分页 + 状态下推）：以下分页变体供 /api/admin/licenses 使用，
    // 状态条件进 SQL 而不是先取全量再在内存筛。
    @EntityGraph(attributePaths = {"product"})
    Page<License> findByCustomerId(UUID customerId, Pageable pageable);
    @EntityGraph(attributePaths = {"product"})
    Page<License> findByCustomerIdAndStatus(UUID customerId, License.LicenseStatus status, Pageable pageable);
    @EntityGraph(attributePaths = {"product"})
    Page<License> findByOrder(Order order, Pageable pageable);
    @EntityGraph(attributePaths = {"product"})
    Page<License> findByOrderAndStatus(Order order, License.LicenseStatus status, Pageable pageable);
    @EntityGraph(attributePaths = {"product"})
    Page<License> findByStatus(License.LicenseStatus status, Pageable pageable);

    // 用户自助查询：本人名下 License，签发时间倒序（最新的在前）
    @EntityGraph(attributePaths = {"product"})
    List<License> findByCustomerIdOrderByIssuedAtDesc(UUID customerId);
    @EntityGraph(attributePaths = {"product"})
    List<License> findByOrder(Order order);
    // B13：按订单 ID 查询已签发 License，供轮询接口幂等返回
    @EntityGraph(attributePaths = {"product"})
    List<License> findByOrderId(UUID orderId);
    // plan-4.1：批量按订单取 License（用户订单列表折算可退额，避免逐单查询的 N+1）；
    // 调用方随后要按 license.getOrder() 分组，故同时预加载订单，否则每张证再一次订单查询。
    @EntityGraph(attributePaths = {"product", "order"})
    List<License> findByOrderIdIn(java.util.Collection<UUID> orderIds);
    boolean existsByLicenseKey(String licenseKey);
    // plan-1.0 / S1：支付后按机器码领取「待激活」件——绑定在本机列上、从未成功校验、仍在有效期内。
    // 状态作参数传入而非 JPQL 里写枚举全限定名：避免依赖特定 Hibernate 版本的字面量解析行为。
    @Query("select l from License l join fetch l.product where l.status = :status and l.machineCode = :machineCode "
        + "and l.signedToken is not null and l.lastVerifiedAt is null and l.issuedAt > :issuedAfter "
        + "and (l.expiresAt is null or l.expiresAt > :now)")
    List<License> findClaimableByBoundMachine(@Param("status") License.LicenseStatus status,
                                              @Param("machineCode") String machineCode,
                                              @Param("issuedAfter") java.time.LocalDateTime issuedAfter,
                                              @Param("now") java.time.LocalDateTime now);
    // plan-7.0 账户基础功能 / P1：管理端用户详情的名下许可证计数
    long countByCustomerId(UUID customerId);
}
