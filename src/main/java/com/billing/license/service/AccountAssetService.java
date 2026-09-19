package com.billing.license.service;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.SubscriptionView;
import com.billing.license.entity.Product;
import com.billing.license.entity.Subscription;
import com.billing.license.entity.User;
import com.billing.license.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    /** 本人名下订单列表，下单时间倒序；映射复用 {@link OrderService}（AdminService 同口径）。 */
    @Transactional(readOnly = true)
    public List<OrderResponse> listMyOrders(UUID userId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(userId).stream()
            .map(orderService::mapToResponse)
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
