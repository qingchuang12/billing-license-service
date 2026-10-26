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
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.SubscriptionLookup;
import com.billing.license.service.payment.strategy.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订阅生命周期服务（B18，Q1 托管 Paddle/Stripe Billing）。
 *
 * 设计要点：
 * - 渠道负责周期扣款与税务；本服务只做「对账 + License 联动」。
 * - 首充：WebhookController 先走 fulfillOrder 签发 License（订单置 PAID），本服务随后
 *   按订单号找到已签发 License 并绑定到订阅记录（不重复签发）。
 * - 续费（invoice.paid / transaction.billed）：延长已绑定 License 的过期时间。
 * - 取消（subscription.canceled / customer.subscription.deleted）：停止续费，权益保留到已付周期结束（期末失效）。
 * - 双保险：fulfillOrder 的订单级幂等 + 「按订单号找已有 License」保证事件乱序也不重复签发。
 * - A10 续费幂等（2026-09-24 根治）：续期以渠道返回的权威周期结束时间 currentPeriodEnd 为基准，
 *   取 max(现有过期时间, currentPeriodEnd)，与投递顺序、是否重复投递（含两个不同 eventId）无关，
 *   彻底杜绝重复延长。事件级去重由 WebhookController(B18) 的 payment_events DB 唯一约束统一负责，
 *   本服务不再另设内存去重层。
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

    /** #26：首充回调丢失时反查渠道订阅用（只消费 lookupSubscription，不参与下单/退款） */
    private final List<PaymentStrategy> paymentStrategies;

    /** 编程式事务：渠道 HTTP 查询留在事务外，落库与 License 联动仍在事务内 */
    private final TransactionOperations transactionOperations;

    /**
     * 处理订阅事件（入口不开事务）。
     *
     * <p>#26：首充回调丢失的自愈路径涉及渠道 HTTP 查询，按硬约束「外部调用在事务外」，
     * 反查必须发生在开事务之前；拿到订单号后再由 {@link #applyEvent} 在事务内落库。
     */
    public void processSubscriptionEvent(WebhookPayload payload, PaymentMethod method) {
        String subId = payload.getSubscriptionId();
        if (subId == null || subId.isEmpty()) {
            return;
        }

        if (needsProviderRepair(method, subId, payload)) {
            Optional<SubscriptionLookup> repaired = lookupOnProvider(method, subId);
            if (repaired.isEmpty()) {
                log.error("订阅事件无法匹配订阅记录，渠道反查也未取到订单号，需人工补录：provider={}, subId={}, event={}",
                    method.name(), subId, payload.getEventType());
                return;
            }
            SubscriptionLookup lookup = repaired.get();
            payload.setOrderId(lookup.getOrderId());
            if (payload.getCurrentPeriodStart() == null) {
                payload.setCurrentPeriodStart(lookup.getCurrentPeriodStart());
            }
            if (payload.getCurrentPeriodEnd() == null) {
                payload.setCurrentPeriodEnd(lookup.getCurrentPeriodEnd());
            }
            log.warn("订阅记录缺失已按渠道查询自动重修：provider={}, subId={}, orderId={}, active={}",
                method.name(), subId, lookup.getOrderId(), lookup.isActive());
        }

        transactionOperations.executeWithoutResult(status -> applyEvent(payload, method));
    }

    /**
     * #26：是否需要走渠道反查重修——本地查不到订阅记录，且事件自身也没带订单号。
     * 判定在事务外完成（只读一次库），结果不影响后续事务内的幂等判断。
     */
    private boolean needsProviderRepair(PaymentMethod method, String subId, WebhookPayload payload) {
        if (payload.getOrderId() != null && !payload.getOrderId().isEmpty()) {
            return false;
        }
        return subscriptionRepository.findByProviderAndProviderSubscriptionId(method, subId).isEmpty();
    }

    /** 事务内：订阅事件落库与 License 联动 */
    private void applyEvent(WebhookPayload payload, PaymentMethod method) {
        String subId = payload.getSubscriptionId();

        Subscription sub = subscriptionRepository
            .findByProviderAndProviderSubscriptionId(method, subId)
            .orElse(null);

        String eventType = payload.getEventType() != null ? payload.getEventType() : "";
        boolean paymentSuccess = "SUCCESS".equals(payload.getStatus());
        boolean canceled = eventType.toLowerCase().contains("cancel")
            || "CANCELLED".equals(payload.getStatus());
        boolean pastDue = eventType.toLowerCase().contains("past_due")
            || "PAST_DUE".equals(payload.getStatus());

        if (sub == null) {
            // 首充：需要订单号建立订阅与 License 的绑定（订单号可能来自 #26 的渠道反查重修）
            Order order = orderRepository.findByOrderNumber(payload.getOrderId()).orElse(null);
            if (order == null) {
                log.error("订阅事件对应的订单不存在，需人工核查：orderNumber={}, subId={}", payload.getOrderId(), subId);
                return;
            }
            UUID productId = order.getOrderItems().isEmpty()
                ? null
                : order.getOrderItems().get(0).getProduct().getId();
            sub = Subscription.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .productId(productId)
                .provider(method)
                .providerSubscriptionId(subId)
                .status(Subscription.SubscriptionStatus.PENDING)
                .build();
            sub = subscriptionRepository.save(sub);
            log.info("创建订阅记录：provider={}, subId={}, orderId={}", method.name(), subId, order.getId());
        }

        // 终态保护：已取消/已过期的订阅不再被迟到的成功事件复活（渠道可能乱序投递不同 eventId）
        boolean terminal = sub.getStatus() == Subscription.SubscriptionStatus.CANCELED
            || sub.getStatus() == Subscription.SubscriptionStatus.EXPIRED;
        if (terminal && !canceled) {
            log.info("订阅已处于终态，忽略后续非取消事件：subId={}, status={}, event={}",
                subId, sub.getStatus(), eventType);
            return;
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
            sub.setCancelAtPeriodEnd(true);
            expireLicense(sub);
        } else if (pastDue) {
            // 宽限期内暂不作废 License，仅标记状态，待后续逾期再处理
            sub.setStatus(Subscription.SubscriptionStatus.PAST_DUE);
            log.info("订阅进入宽限期（past_due）：subId={}，License 暂不作废", subId);
        }

        subscriptionRepository.save(sub);
    }

    /**
     * #26：按渠道反查订阅，取回订单号用于补建缺失的订阅记录。
     *
     * <p>实现侧（Stripe / Paddle）保证不抛异常，这里仍对 RuntimeException 兜底：
     * 重修是补救路径，失败只应退回人工告警，绝不能打断本次回调处理。
     */
    private Optional<SubscriptionLookup> lookupOnProvider(PaymentMethod method, String subId) {
        try {
            return paymentStrategies.stream()
                .filter(s -> s.getPaymentMethod() == method)
                .findFirst()
                .flatMap(s -> s.lookupSubscription(subId));
        } catch (RuntimeException e) {
            log.warn("渠道订阅查询不可用，跳过自动重修：provider={}, subId={}, reason={}",
                method.name(), subId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 绑定首充 License 或续期已绑定 License。
     * 首充：fulfillOrder 已按订单签发 License，本方法按订单号找到并绑定，不重复签发；
     * 续费：延长已绑定 License 的过期时间（避免重复签发）。
     */
    private void bindOrRenewLicense(Subscription sub) {
        License license;
        if (sub.getLicenseId() != null) {
            // 已绑定：续费作用在这张证上，不再按订单号反查（兑换码签发的证可能不挂订单）
            license = licenseRepository.findById(sub.getLicenseId()).orElse(null);
            if (license == null) {
                log.warn("订阅已绑定 License 但记录不存在，跳过续期：subId={}, licenseId={}",
                    sub.getProviderSubscriptionId(), sub.getLicenseId());
                return;
            }
        } else {
            // 首充：fulfillOrder 已按订单签发 License，这里只建立绑定、不延长有效期
            List<License> existing = licenseRepository.findByOrderId(sub.getOrderId());
            if (existing.isEmpty()) {
                // 兑换码场景（无机器码）：首充生成的是兑换码，用户尚未兑换，暂无 License 可绑
                return;
            }
            license = existing.get(0);
            sub.setLicenseId(license.getId());
            if (license.getStatus() != License.LicenseStatus.ACTIVE) {
                license.setStatus(License.LicenseStatus.ACTIVE);
                licenseRepository.save(license);
            }
            log.info("订阅绑定首充 License：subId={}, licenseId={}", sub.getProviderSubscriptionId(), license.getId());
            return;
        }

        // 续费：延长已绑定 License 的有效期（A10 幂等根治）。
        // 以渠道返回的权威周期结束时间 currentPeriodEnd 为基准，取 max(现有过期时间, currentPeriodEnd)：
        // 同一周期无论被投递一次还是多次（含 subscription.updated + transaction.billed 两个不同 eventId）
        // 都收敛到同一目标值，绝不累加延长。
        // Paddle 现对 subscription.* 与 transaction.billed/completed 都解析 current_billing_period
        // 写入 currentPeriodEnd（见 PaddleStrategy.parseWebhookPayload），生产路径周期信息始终可用。
        Product product = license.getProduct();
        int days = (product != null && product.getLicenseDurationDays() != null)
            ? product.getLicenseDurationDays() : 31;
        LocalDateTime end;
        if (sub.getCurrentPeriodEnd() != null) {
            LocalDateTime existingExpiry = (license.getExpiresAt() != null)
                ? license.getExpiresAt() : LocalDateTime.now();
            end = sub.getCurrentPeriodEnd().isAfter(existingExpiry)
                ? sub.getCurrentPeriodEnd() : existingExpiry;
        } else {
            // 兜底：渠道未返回周期（极罕见，生产路径已消除）。以现有过期时间为锚累加，避免从现在起算丢失剩余时长。
            end = ((license.getExpiresAt() != null && license.getExpiresAt().isAfter(LocalDateTime.now()))
                ? license.getExpiresAt() : LocalDateTime.now()).plusDays(days);
        }
        license.setExpiresAt(end);
        license.setStatus(License.LicenseStatus.ACTIVE);
        licenseRepository.save(license);
        log.info("订阅续期 License：subId={}, licenseId={}, 新过期时间={}",
            sub.getProviderSubscriptionId(), license.getId(), license.getExpiresAt());
    }

    /**
     * 取消订阅的权益处理（期末失效）：渠道在 currentPeriodEnd 之前已收款，
     * 故保留权益到该时点、仅停止续费；无周期信息或周期已过（立即取消）才当场作废。
     */
    private void expireLicense(Subscription sub) {
        if (sub.getLicenseId() == null) {
            return;
        }
        License license = licenseRepository.findById(sub.getLicenseId()).orElse(null);
        if (license == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = sub.getCurrentPeriodEnd();
        if (periodEnd != null && periodEnd.isAfter(now)) {
            license.setStatus(License.LicenseStatus.ACTIVE);
            license.setExpiresAt(periodEnd);
            licenseRepository.save(license);
            log.info("订阅取消（期末失效）：subId={}, licenseId={}, 权益保留至 {}",
                sub.getProviderSubscriptionId(), license.getId(), periodEnd);
            return;
        }
        license.setStatus(License.LicenseStatus.EXPIRED);
        license.setExpiresAt(now);
        licenseRepository.save(license);
        log.info("订阅取消（立即失效）：subId={}, licenseId={}", sub.getProviderSubscriptionId(), license.getId());
    }
}
