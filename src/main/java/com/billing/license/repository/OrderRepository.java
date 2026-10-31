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

    /**
     * #12：OSIV 关闭后事务外不得再触发懒加载，故「要在事务外读行项」的链路必须预加载。
     *
     * <p>命中两条链路（都是按硬约束 16 必须留在事务外、不能靠加事务解决的）：
     * <ul>
     *   <li>收银台选渠道：{@code selectProvider} 要按行项查套餐周期做渠道能力校验，
     *       随后支付策略的 {@code resolveRecurringProduct} 也要读行项；</li>
     *   <li>用户自助退款：{@code RefundPolicy#isSubscriptionOrder} 要读行项判定是否订阅订单。</li>
     * </ul>
     * 这里一次性 join fetch 出行项与其产品，而不是让它们随后各自懒加载。
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems oi LEFT JOIN FETCH oi.product WHERE o.id = :id")
    Optional<Order> findWithItemsById(@Param("id") UUID id);

    /** 同 {@link #findWithItemsById}，按业务订单号取（自助退款链路用）。 */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems oi LEFT JOIN FETCH oi.product WHERE o.orderNumber = :orderNumber")
    Optional<Order> findWithItemsByOrderNumber(@Param("orderNumber") String orderNumber);

    java.util.List<Order> findByStatus(Order.OrderStatus status);

    /** #9（管理端分页）：按状态过滤并分页，替代「无过滤即 findAll() 全表入内存」。 */
    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);
    // 用户自助查询：本人名下订单，下单时间倒序（最新的在前）
    java.util.List<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /** 账务：按订单创建时间范围取订单（收入/趋势/对账聚合使用） */
    java.util.List<Order> findByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /**
     * 账务分产品聚合：一次 join fetch 出行项与其产品，避免「订单 → 行项 → 产品」逐级懒加载。
     *
     * <p>只有 {@code AccountingService#getByProduct} 需要行项；收入/渠道/趋势/对账四个方法只读
     * 订单标量字段，继续用上面的无预加载版本，不为一处需求让所有账务查询都背一次 join。
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.orderItems oi LEFT JOIN FETCH oi.product "
        + "WHERE o.createdAt BETWEEN :from AND :to")
    java.util.List<Order> findByCreatedAtBetweenWithItems(@Param("from") java.time.LocalDateTime from,
                                                          @Param("to") java.time.LocalDateTime to);

    // plan-7.0 账户基础功能 / P1：管理端用户详情的名下订单计数
    long countByCustomerId(UUID customerId);

    /**
     * P16：账号页订单列表批量取套餐 SKU（「继续支付」需要它把收银台档位预选回来）。
     * 返回 Object[]{orderId, sku}；一单一行，避免逐单懒加载行项。
     */
    @Query("SELECT oi.order.id, p.sku FROM OrderItem oi JOIN oi.product p WHERE oi.order.id IN :orderIds")
    java.util.List<Object[]> findProductSkuByOrderIds(@Param("orderIds") java.util.Collection<UUID> orderIds);
}
