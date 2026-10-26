package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.SubscriptionView;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.*;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户资产自助查询服务（U1「用户密钥简单管理」）。
 *
 * <p>只读：当前登录用户按归属（{@code customerId = users.id}）查看本人名下的
 * License、订阅与订单。访客账户（购买/兑换时按邮箱自动建立）先经
 * {@code POST /api/account/password/reset} 设密码认领，即可登录查看。
 *
 * <p>出参口径：License 复用 {@link LicenseResponse#adminView} 脱敏视图——
 * {@code licenseKey} 完整回显（用户激活软件所需），{@code signedToken} 不返回
 * （内部离线验签令牌，与管理端同口径）。
 */
@Service
@RequiredArgsConstructor
public class AccountAssetService {

    private final LicenseRepository licenseRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    /** plan-4.1：退款折算下限（billing.refund.min-amount），用于订单列表的退款入口显隐 */
    private final BillingProperties billingProperties;
    /** 订阅自助取消：只消费 {@code cancelSubscriptionAtPeriodEnd}，不参与下单/退款 */
    private final List<PaymentStrategy> paymentStrategies;
    /** 硬约束 16：渠道调用在事务外，落库单独包短事务 */
    private final TransactionOperations transactionOperations;

    /** 本人名下 License 列表，签发时间倒序。 */
    @Transactional(readOnly = true)
    public List<LicenseResponse> listMyLicenses(UUID userId) {
        // E3：全部 License 同属本人，单次解析邮箱回填 customerEmail
        String email = userRepository.findById(userId).map(User::getEmail).orElse(null);
        return licenseRepository.findByCustomerIdOrderByIssuedAtDesc(userId).stream()
            .map(l -> LicenseResponse.adminView(l, email))
            .toList();
    }

    /** 本人名下订阅列表，创建时间倒序；产品 SKU/名称按 {@code productId} 批量解析。 */
    @Transactional(readOnly = true)
    public List<SubscriptionView> listMySubscriptions(UUID userId) {
        List<Subscription> subscriptions = subscriptionRepository
            .findByCustomerIdOrderByCreatedAtDesc(userId);
        if (subscriptions.isEmpty()) {
            return List.of();
        }

        // 批量取产品，避免逐条 findById 的 N+1
        List<UUID> productIds = subscriptions.stream()
            .map(Subscription::getProductId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<UUID, Product> products = productRepository.findAllById(productIds).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));

        return subscriptions.stream()
            .map(s -> toView(s, s.getProductId() != null ? products.get(s.getProductId()) : null))
            .toList();
    }

    /**
     * 订阅自助取消（期末生效）：渠道侧置「本周期结束即停」，本地同步打
     * {@code cancel_at_period_end} 标记；权益保留到 {@code currentPeriodEnd}，到期后由渠道
     * 的取消事件经 {@code SubscriptionService} 走期末失效链路——**不在本方法内提前停权**
     * （用户已付到期的部分不能因为点了取消就当场失效）。
     *
     * <p>归属校验与退款同口径：他人订阅按 {@code SUBSCRIPTION_NOT_FOUND} 返回，不泄露存在性。
     * 渠道未确认取消一律抛错、**不落库**（界面显示已取消而渠道仍在扣款＝静默资损）。
     *
     * <p>事务边界承继硬约束 16：本方法**不开事务**，渠道 HTTP 调用在事务外完成，
     * 确认后由 {@code TransactionOperations} 只包住这一次落库。
     */
    public SubscriptionView cancelMySubscription(UUID userId, UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null || !Objects.equals(sub.getCustomerId(), userId)) {
            throw new BusinessException("SUBSCRIPTION_NOT_FOUND", "订阅不存在");
        }
        // 终态与「已标记期末取消」都幂等返回：不重复打扰渠道
        if (sub.getStatus() == Subscription.SubscriptionStatus.CANCELED
                || sub.getStatus() == Subscription.SubscriptionStatus.EXPIRED
                || Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
            return toView(sub, product(sub));
        }
        if (sub.getStatus() == Subscription.SubscriptionStatus.PENDING) {
            throw new BusinessException("SUBSCRIPTION_NOT_CANCELABLE", "订阅尚未生效，暂不支持取消");
        }

        PaymentStrategy strategy = paymentStrategies.stream()
                .filter(s -> s.getPaymentMethod() == sub.getProvider())
                .findFirst()
                .orElse(null);
        if (strategy == null) {
            throw new BusinessException("SUBSCRIPTION_PROVIDER_UNSUPPORTED",
                    "该订阅渠道不支持自助取消，请联系客服");
        }
        if (!strategy.cancelSubscriptionAtPeriodEnd(sub.getProviderSubscriptionId())) {
            throw new BusinessException("SUBSCRIPTION_CANCEL_FAILED", "渠道未确认取消，请稍后重试");
        }

        return transactionOperations.execute(status -> {
            Subscription fresh = subscriptionRepository.findById(subscriptionId).orElseThrow();
            fresh.setCancelAtPeriodEnd(true);
            subscriptionRepository.save(fresh);
            return toView(fresh, product(fresh));
        });
    }

    /** 订阅关联产品（未关联返回 null，视图里产品字段即为空）。 */
    private Product product(Subscription subscription) {
        return subscription.getProductId() == null ? null
                : productRepository.findById(subscription.getProductId()).orElse(null);
    }

    /**
     * 本人名下订单列表，下单时间倒序；映射复用 {@link OrderService}（AdminService 同口径）。
     *
     * <p>plan-4.1：额外回填 {@code refundable} / {@code refundableAmount}，作为前端
     * 「申请退款」按钮的显隐依据与金额提示。按订单**批量**取 License（防 N+1），
     * 再交给 {@link RefundPolicy} 折算；管理端列表不填这两个字段（口径隔离）。
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> listMyOrders(UUID userId) {
        List<Order> orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(userId);
        if (orders.isEmpty()) {
            return List.of();
        }

        // 按订单归组；兑换码签发的 License 无订单（order 为 null），必须跳过，
        // 否则分组时空指针——它们本就不属于任何订单，无「订单退款」语义。
        Map<UUID, List<License>> licensesByOrder = licenseRepository
            .findByOrderIdIn(orders.stream().map(Order::getId).toList()).stream()
            .filter(license -> license.getOrder() != null && license.getOrder().getId() != null)
            .collect(Collectors.groupingBy(license -> license.getOrder().getId()));

        LocalDateTime now = LocalDateTime.now();
        BigDecimal minAmount = billingProperties.getRefund().getMinAmount();
        return orders.stream()
            .map(order -> {
                OrderResponse response = orderService.mapToResponse(order);
                Optional<RefundPolicy.Quote> quote = RefundPolicy.quote(
                    order, licensesByOrder.getOrDefault(order.getId(), List.of()), now, minAmount);
                response.setRefundable(quote.isPresent());
                quote.ifPresent(q -> response.setRefundableAmount(q.amount()));
                return response;
            })
            .toList();
    }

    private static SubscriptionView toView(Subscription subscription, Product product) {
        return SubscriptionView.builder()
            .id(subscription.getId())
            .provider(subscription.getProvider() != null ? subscription.getProvider().name() : null)
            .status(subscription.getStatus() != null ? subscription.getStatus().name() : null)
            .productSku(product != null ? product.getSku() : null)
            .productName(product != null ? product.getName() : null)
            .licenseId(subscription.getLicenseId())
            .currentPeriodStart(subscription.getCurrentPeriodStart())
            .currentPeriodEnd(subscription.getCurrentPeriodEnd())
            .cancelAtPeriodEnd(subscription.getCancelAtPeriodEnd())
            .createdAt(subscription.getCreatedAt())
            .build();
    }
}
