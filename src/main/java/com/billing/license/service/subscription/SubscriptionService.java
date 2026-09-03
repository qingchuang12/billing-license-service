package com.billing.license.service.subscription;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Product;
import com.billing.license.entity.Subscription;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.SubscriptionRepository;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 订阅生命周期服务（B18，Q1 托管 Paddle/Stripe Billing）。
 *
 * 设计要点：
 * - 渠道负责周期扣款与税务；本服务只做「对账 + License 联动」。
 * - 首充：WebhookController 先走 fulfillOrder 签发 License（订单置 PAID），本服务随后
 *   按订单号找到已签发 License 并绑定到订阅记录（不重复签发）。
 * - 续费（invoice.paid / transaction.billed）：延长已绑定 License 的过期时间。
 * - 取消（subscription.canceled / customer.subscription.deleted）：作废已绑定 License。
 * - 双保险：fulfillOrder 的订单级幂等 + 「按订单号找已有 License」保证事件乱序也不重复签发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final OrderRepository orderRepository;
    private final LicenseRepository licenseRepository;
    private final ProductRepository productRepository;
    private final LicenseService licenseService;
    private final RedeemCodeService redeemCodeService;

    @Transactional
    public void processSubscriptionEvent(WebhookPayload payload, PaymentMethod method) {
        String subId = payload.getSubscriptionId();
        if (subId == null || subId.isEmpty()) {
            return;
        }

        Subscription sub = subscriptionRepository
            .findByProviderAndProviderSubscriptionId(method.name(), subId)
            .orElse(null);

        String eventType = payload.getEventType() != null ? payload.getEventType() : "";
        boolean paymentSuccess = "SUCCESS".equals(payload.getStatus());
        boolean canceled = eventType.toLowerCase().contains("cancel")
            || "CANCELLED".equals(payload.getStatus());
        boolean pastDue = eventType.toLowerCase().contains("past_due")
            || "PAST_DUE".equals(payload.getStatus());

        if (sub == null) {
            // 首充：需要订单号建立订阅与 License 的绑定
            if (payload.getOrderId() == null || payload.getOrderId().isEmpty()) {
                log.warn("订阅事件缺少订单号且无已有订阅记录，无法落库：provider={}, subId={}, event={}",
                    method.name(), subId, eventType);
                return;
            }
            Order order = orderRepository.findByOrderNumber(payload.getOrderId()).orElse(null);
            if (order == null) {
                log.warn("订阅事件对应的订单不存在：orderNumber={}", payload.getOrderId());
                return;
            }
            UUID productId = order.getOrderItems().isEmpty()
                ? null
                : order.getOrderItems().get(0).getProduct().getId();
            sub = Subscription.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .productId(productId)
                .provider(method.name())
                .providerSubscriptionId(subId)
                .status(Subscription.SubscriptionStatus.PENDING)
                .build();
            sub = subscriptionRepository.save(sub);
            log.info("创建订阅记录：provider={}, subId={}, orderId={}", method.name(), subId, order.getId());
        }

        if (payload.getCurrentPeriodStart() != null) {
            sub.setCurrentPeriodStart(payload.getCurrentPeriodStart());
        }
        if (payload.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(payload.getCurrentPeriodEnd());
        }

        if (paymentSuccess) {
            sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            sub.setCancelAtPeriodEnd(false);
            bindOrRenewLicense(sub);
        } else if (canceled) {
            sub.setStatus(Subscription.SubscriptionStatus.CANCELED);
            expireLicense(sub);
        } else if (pastDue) {
            // 宽限期内暂不作废 License，仅标记状态，待后续逾期再处理
            sub.setStatus(Subscription.SubscriptionStatus.PAST_DUE);
            log.info("订阅进入宽限期（past_due）：subId={}，License 暂不作废", subId);
        }

        subscriptionRepository.save(sub);
    }

    /**
     * 绑定首充 License 或续期已绑定 License。
     * 首充：fulfillOrder 已按订单签发 License，本方法按订单号找到并绑定，不重复签发；
     * 续费：延长已绑定 License 的过期时间（避免重复签发）。
     */
    private void bindOrRenewLicense(Subscription sub) {
        List<License> existing = licenseRepository.findByOrderId(sub.getOrderId());
        if (existing.isEmpty()) {
            // 兑换码场景（无机器码）：首充已生成兑换码，无可续 License，仅保留订阅记录
            return;
        }

        License license = existing.get(0);

        if (sub.getLicenseId() == null) {
            // 首次：建立绑定，不延长有效期（首期已由 fulfillOrder 设定）
            sub.setLicenseId(license.getId());
            if (license.getStatus() != License.LicenseStatus.ACTIVE) {
                license.setStatus(License.LicenseStatus.ACTIVE);
                licenseRepository.save(license);
            }
            log.info("订阅绑定首充 License：subId={}, licenseId={}", sub.getProviderSubscriptionId(), license.getId());
            return;
        }

        // 续费：延长已绑定 License 的有效期（从较晚的「当前过期时间 / 现在」起算，不丢失剩余时长）
        Product product = license.getProduct();
        int days = (product != null && product.getLicenseDurationDays() != null)
            ? product.getLicenseDurationDays() : 31;
        LocalDateTime base = (license.getExpiresAt() != null && license.getExpiresAt().isAfter(LocalDateTime.now()))
            ? license.getExpiresAt() : LocalDateTime.now();
        license.setExpiresAt(base.plusDays(days));
        license.setStatus(License.LicenseStatus.ACTIVE);
        licenseRepository.save(license);
        log.info("订阅续期 License：subId={}, licenseId={}, 新过期时间={}",
            sub.getProviderSubscriptionId(), license.getId(), license.getExpiresAt());
    }

    private void expireLicense(Subscription sub) {
        if (sub.getLicenseId() == null) {
            return;
        }
        licenseRepository.findById(sub.getLicenseId()).ifPresent(license -> {
            license.setStatus(License.LicenseStatus.EXPIRED);
            license.setExpiresAt(LocalDateTime.now());
            licenseRepository.save(license);
            log.info("订阅取消，作废 License：subId={}, licenseId={}", sub.getProviderSubscriptionId(), license.getId());
        });
    }
}
