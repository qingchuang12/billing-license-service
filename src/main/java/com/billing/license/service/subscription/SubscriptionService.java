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
import org.springframework.scheduling.annotation.Scheduled;
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
    private final SubscriptionNotificationService notificationService;

    /**
     * 订阅事件的前置补全（**必须在事务外调用**）：本地无订阅记录且事件不带订单号时，
     * 按渠道反查补出订单号与周期。返回 false 表示反查失败——调用方不得假装成功，
     * 应让渠道重投并保留 processed=false 的事件行供人工核对。
     *
     * <p>把 HTTP 从 {@link #processSubscriptionEvent} 挪到此处，是硬约束 18 的落地：
     * 该方法可能被 WebhookController 的事务包裹，事务内发渠道请求会占用 DB 连接。
     */
    public boolean enrichFromProvider(WebhookPayload payload, PaymentMethod method) {
        String subId = payload.getSubscriptionId();
        if (subId == null || subId.isEmpty()) {
            return true;
        }
        if (payload.getOrderId() != null && !payload.getOrderId().isEmpty()) {
            return true;
        }
        if (subscriptionRepository.findByProviderAndProviderSubscriptionId(method, subId).isPresent()) {
            return true;
        }
        Optional<SubscriptionLookup> repaired = lookupOnProvider(method, subId);
        if (repaired.isEmpty()) {
            return false;
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
        return true;
    }

    /** 事务内：订阅事件落库与 License 联动（不再发起任何渠道 HTTP）。 */
    public void processSubscriptionEvent(WebhookPayload payload, PaymentMethod method) {
        String subId = payload.getSubscriptionId();
        if (subId == null || subId.isEmpty()) {
            return;
        }
        transactionOperations.executeWithoutResult(status -> applyEvent(payload, method));
    }

    /** 事务内：订阅事件落库与 License 联动 */
    private void applyEvent(WebhookPayload payload, PaymentMethod method) {
        String subId = payload.getSubscriptionId();

        Subscription sub = subscriptionRepository
            .findByProviderAndProviderSubscriptionId(method, subId)
            .orElse(null);

        String eventType = payload.getEventType() != null ? payload.getEventType() : "";
        com.billing.license.service.payment.strategy.PaymentEventType kind = payload.getEffectiveEventKind();
        boolean paymentSuccess = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_PAYMENT;
        boolean canceled = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_CANCELED;
        boolean expired = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_EXPIRED;
        boolean scheduledCancel = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_SCHEDULED_CANCEL;
        boolean pastDue = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_PAST_DUE;
        boolean paused = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_PAUSED;
        boolean trialing = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_TRIALING;
        boolean syncOnly = kind == com.billing.license.service.payment.strategy.PaymentEventType.SUBSCRIPTION_ACTIVE;

        if (sub == null) {
            if (payload.getOrderId() == null || payload.getOrderId().isBlank()) {
                log.error("订阅事件既无本地记录也无订单号，需人工核查：subId={}, event={}", subId, eventType);
                return;
            }
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

        // 终态保护：取消/过期后迟到的同步事件不能复活订阅。
        // 例外：EXPIRED 后收到已确认收款的 paid 事件——渠道可能已重新扣款成功，
        // 若继续忽略会「收了钱、权益不延期」。CANCELED 仍保持不可复活（期末取消后
        // 的迟到 transaction.billed 多为旧事件重投）。
        boolean terminal = sub.getStatus() == Subscription.SubscriptionStatus.CANCELED
            || sub.getStatus() == Subscription.SubscriptionStatus.EXPIRED;
        boolean reviveExpiredByPayment = paymentSuccess
            && sub.getStatus() == Subscription.SubscriptionStatus.EXPIRED;
        if (terminal && !canceled && !expired && !reviveExpiredByPayment) {
            log.info("订阅已处于终态，忽略后续非取消事件：subId={}, status={}, event={}",
                subId, sub.getStatus(), eventType);
            return;
        }
        if (reviveExpiredByPayment) {
            log.warn("已过期订阅收到确认收款事件，按收款事实恢复并延期：subId={}, event={}",
                subId, eventType);
        }

        Subscription.SubscriptionStatus previousStatus = sub.getStatus();
        LocalDateTime previousPeriodEnd = sub.getCurrentPeriodEnd();
        boolean wasBound = sub.getLicenseId() != null;

        // 周期只前进不倒退：乱序投递的旧周期不能把已付费周期改短
        if (payload.getCurrentPeriodStart() != null
                && (sub.getCurrentPeriodStart() == null
                    || payload.getCurrentPeriodStart().isAfter(sub.getCurrentPeriodStart()))) {
            sub.setCurrentPeriodStart(payload.getCurrentPeriodStart());
        }
        if (payload.getCurrentPeriodEnd() != null
                && (sub.getCurrentPeriodEnd() == null
                    || payload.getCurrentPeriodEnd().isAfter(sub.getCurrentPeriodEnd()))) {
            sub.setCurrentPeriodEnd(payload.getCurrentPeriodEnd());
        }

        if (paymentSuccess) {
            sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            sub.setCancelAtPeriodEnd(false);
            bindOrRenewLicense(sub);
            LocalDateTime newEnd = sub.getCurrentPeriodEnd();
            boolean advanced = newEnd != null
                && (previousPeriodEnd == null || newEnd.isAfter(previousPeriodEnd));
            if (wasBound && advanced) {
                notificationService.notifyStatus(sub.getOrderId(),
                    "订阅续费成功", "Subscription renewed",
                    "本期权益已延长至 " + newEnd + "。", false);
            }
        } else if (expired) {
            sub.setStatus(Subscription.SubscriptionStatus.EXPIRED);
            sub.setCancelAtPeriodEnd(true);
            expireLicenseNow(sub);
            if (previousStatus != Subscription.SubscriptionStatus.EXPIRED) {
                notificationService.notifyStatus(sub.getOrderId(),
                    "订阅已到期", "Subscription expired",
                    "订阅已到期，相关权益已失效；如需继续使用请重新购买。", true);
            }
        } else if (canceled) {
            sub.setStatus(Subscription.SubscriptionStatus.CANCELED);
            sub.setCancelAtPeriodEnd(true);
            expireLicense(sub);
            if (previousStatus != Subscription.SubscriptionStatus.CANCELED) {
                notificationService.notifyStatus(sub.getOrderId(),
                    "订阅已取消", "Subscription canceled",
                    "订阅已停止续费，权益按已付周期处理。", false);
            }
        } else if (scheduledCancel) {
            // 期末取消不是终态：权益保留到 currentPeriodEnd，期间仍可恢复
            boolean newlyScheduled = !Boolean.TRUE.equals(sub.getCancelAtPeriodEnd());
            sub.setCancelAtPeriodEnd(true);
            if (sub.getStatus() == Subscription.SubscriptionStatus.PENDING) {
                sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            }
            log.info("订阅已安排期末取消（可恢复）：subId={}, periodEnd={}",
                subId, sub.getCurrentPeriodEnd());
            if (newlyScheduled) {
                notificationService.notifyStatus(sub.getOrderId(),
                    "订阅已安排期末取消", "Subscription scheduled to cancel",
                    "订阅将在当前周期结束后停止续费，权益保留至 " + sub.getCurrentPeriodEnd() + "。", false);
            }
        } else if (pastDue) {
            sub.setStatus(Subscription.SubscriptionStatus.PAST_DUE);
            log.info("订阅进入宽限期（past_due）：subId={}，License 暂不作废", subId);
            if (previousStatus != Subscription.SubscriptionStatus.PAST_DUE) {
                notificationService.notifyStatus(sub.getOrderId(),
                    "订阅扣款失败", "Subscription payment failed",
                    "订阅扣款未成功，请在支付渠道更新支付方式；宽限期内权益暂不作废。", true);
            }
        } else if (paused) {
            // 暂停不是退款：不吊销权益，仅记录状态；恢复由后续 active/paid 事件处理
            sub.setStatus(Subscription.SubscriptionStatus.PAST_DUE);
            log.info("订阅已暂停：subId={}，License 暂不吊销", subId);
        } else if (trialing) {
            log.info("订阅试用中（不发放付费权益）：subId={}", subId);
        } else if (syncOnly) {
            // 取消安排可逆：syncOnly 事件本身不延长权益，但渠道明确告知「未再安排取消」时，
            // 必须把本地标记复位——否则用户撤销期末取消后，到下一期扣款成功前页面一直显示将取消。
            // 仅当渠道给了明确结论（TRUE/FALSE）才动该标记，null 一律不动。
            if (Boolean.FALSE.equals(payload.getCancelScheduled())) {
                if (Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
                    sub.setCancelAtPeriodEnd(false);
                    log.info("订阅已撤销期末取消（恢复续费）：subId={}, periodEnd={}, event={}",
                        subId, sub.getCurrentPeriodEnd(), eventType);
                }
            } else if (Boolean.TRUE.equals(payload.getCancelScheduled())) {
                if (!Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
                    sub.setCancelAtPeriodEnd(true);
                    log.info("订阅已安排期末取消（同步补齐）：subId={}, periodEnd={}, event={}",
                        subId, sub.getCurrentPeriodEnd(), eventType);
                }
            }
            log.info("订阅状态同步（不延期）：subId={}, event={}", subId, eventType);
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
        boolean firstBinding = false;
        if (sub.getLicenseId() != null) {
            // 已绑定：续费作用在这张证上，不再按订单号反查（兑换码签发的证可能不挂订单）
            license = licenseRepository.findById(sub.getLicenseId()).orElse(null);
            if (license == null) {
                log.warn("订阅已绑定 License 但记录不存在，跳过续期：subId={}, licenseId={}",
                    sub.getProviderSubscriptionId(), sub.getLicenseId());
                return;
            }
        } else {
            // 首充：fulfillOrder 已按订单签发 License，这里建立绑定并校准周期
            List<License> existing = licenseRepository.findByOrderId(sub.getOrderId());
            if (existing.isEmpty()) {
                // 兑换码场景（无机器码）：首充生成的是兑换码，用户尚未兑换，暂无 License 可绑
                return;
            }
            // 换机重发后优先绑定 ACTIVE 新证，避免绑到 REISSUED 旧证
            license = existing.stream()
                .filter(l -> l.getStatus() == License.LicenseStatus.ACTIVE)
                .findFirst()
                .orElse(existing.get(0));
            sub.setLicenseId(license.getId());
            firstBinding = true;
            log.info("订阅绑定首充 License：subId={}, licenseId={}", sub.getProviderSubscriptionId(), license.getId());
        }

        // 退款撤销/换机重发后的证是终态：绝不能被迟到的订阅事件复活
        if (license.getStatus() == License.LicenseStatus.REVOKED
                || license.getStatus() == License.LicenseStatus.REISSUED) {
            log.warn("订阅关联 License 已处于终态，不复活：subId={}, licenseId={}, status={}",
                sub.getProviderSubscriptionId(), license.getId(), license.getStatus());
            return;
        }
        if (license.getOrder() != null
                && (license.getOrder().getPaymentStatus() == Order.PaymentStatus.REFUNDED
                    || license.getOrder().getStatus() == Order.OrderStatus.REFUNDED)) {
            log.warn("订阅关联订单已退款，不延期：subId={}, orderId={}",
                sub.getProviderSubscriptionId(), license.getOrder().getOrderNumber());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (firstBinding) {
            // 首充：签发时已按套餐天数给过权益，这里只用渠道确认的已付周期校准；
            // 没有周期就只建立绑定，绝不能凭空再加一个周期。
            if (sub.getCurrentPeriodEnd() != null
                    && sub.getCurrentPeriodEnd().isAfter(license.getIssuedAt() != null
                        ? license.getIssuedAt() : now)) {
                license.setExpiresAt(sub.getCurrentPeriodEnd());
                license.setStatus(License.LicenseStatus.ACTIVE);
                licenseRepository.save(license);
                licenseService.refreshTokenIfBound(license);
                log.info("订阅首充按渠道周期校准 License：subId={}, licenseId={}, expiresAt={}",
                    sub.getProviderSubscriptionId(), license.getId(), license.getExpiresAt());
            }
            return;
        }

        LocalDateTime end;
        if (sub.getCurrentPeriodEnd() != null) {
            // 续费幂等：取 max(现有过期时间, 渠道周期结束)，同一周期重复/乱序投递收敛到同一目标值
            LocalDateTime existingExpiry = (license.getExpiresAt() != null)
                ? license.getExpiresAt() : now;
            end = sub.getCurrentPeriodEnd().isAfter(existingExpiry)
                ? sub.getCurrentPeriodEnd() : existingExpiry;
        } else {
            // 兜底：渠道未返回周期（极罕见）。以现有过期时间为锚累加，避免从现在起算丢失剩余时长。
            Product product = license.getProduct();
            int days = (product != null && product.getLicenseDurationDays() != null)
                ? product.getLicenseDurationDays() : 31;
            end = ((license.getExpiresAt() != null && license.getExpiresAt().isAfter(now))
                ? license.getExpiresAt() : now).plusDays(days);
        }
        license.setExpiresAt(end);
        license.setStatus(License.LicenseStatus.ACTIVE);
        licenseRepository.save(license);
        // 续费只改 DB 日期时代客户端仍拿旧 exp：必须按新权益重签
        licenseService.refreshTokenIfBound(license);
        log.info("订阅{} License：subId={}, licenseId={}, 新过期时间={}",
            firstBinding ? "校准" : "续期", sub.getProviderSubscriptionId(), license.getId(), end);
    }

    /**
     * 取消订阅的权益处理（期末失效）：渠道在 currentPeriodEnd 之前已收款，
     * 故保留权益到该时点、仅停止续费；无周期信息或周期已过（立即取消）才当场作废。
     */
    private void expireLicense(Subscription sub) {
        License license = boundLicense(sub);
        if (license == null || isTerminal(license)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodEnd = sub.getCurrentPeriodEnd();
        if (periodEnd != null && periodEnd.isAfter(now)) {
            // 只缩短、不延长：已付周期与当前到期日取较早者，避免取消反而送时长
            LocalDateTime end = (license.getExpiresAt() != null && license.getExpiresAt().isBefore(periodEnd))
                ? license.getExpiresAt() : periodEnd;
            license.setStatus(License.LicenseStatus.ACTIVE);
            license.setExpiresAt(end);
            licenseRepository.save(license);
            licenseService.refreshTokenIfBound(license);
            log.info("订阅取消（期末失效）：subId={}, licenseId={}, 权益保留至 {}",
                sub.getProviderSubscriptionId(), license.getId(), end);
            return;
        }
        license.setStatus(License.LicenseStatus.EXPIRED);
        license.setExpiresAt(now);
        licenseRepository.save(license);
        log.info("订阅取消（立即失效）：subId={}, licenseId={}", sub.getProviderSubscriptionId(), license.getId());
    }

    /** 渠道明确到期：立即失效（过期事件里的 status 可能仍写 active，不能据 status 判活）。 */
    private void expireLicenseNow(Subscription sub) {
        License license = boundLicense(sub);
        if (license == null || isTerminal(license)) {
            return;
        }
        license.setStatus(License.LicenseStatus.EXPIRED);
        license.setExpiresAt(LocalDateTime.now());
        licenseRepository.save(license);
        log.info("订阅已到期，License 立即失效：subId={}, licenseId={}",
            sub.getProviderSubscriptionId(), license.getId());
    }

    private License boundLicense(Subscription sub) {
        if (sub.getLicenseId() == null) {
            return null;
        }
        return licenseRepository.findById(sub.getLicenseId()).orElse(null);
    }

    /**
     * 首充时 Webhook 先登记履约、后处理订阅事件，License 尚未签发 → license_id 为空。
     * 若此后没有续费事件，取消/到期都会因找不到 License 而 no-op。后台补绑兜底。
     */
    @Scheduled(fixedDelayString = "${billing.subscription.bind-retry-ms:60000}")
    public void bindPendingLicenses() {
        List<Subscription> pending = subscriptionRepository.findTop100ByLicenseIdIsNull();
        for (Subscription sub : pending) {
            try {
                transactionOperations.executeWithoutResult(status -> bindPendingLicense(sub.getId()));
            } catch (Exception e) {
                log.error("订阅补绑 License 失败：subId={}", sub.getProviderSubscriptionId(), e);
            }
        }
    }

    private void bindPendingLicense(UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null || sub.getLicenseId() != null || sub.getOrderId() == null) {
            return;
        }
        List<License> licenses = licenseRepository.findByOrderId(sub.getOrderId());
        if (licenses == null || licenses.isEmpty()) {
            return;
        }
        switch (sub.getStatus()) {
            case ACTIVE, PAST_DUE -> bindOrRenewLicense(sub);
            case CANCELED -> {
                bindOrRenewLicense(sub);
                expireLicense(sub);
            }
            case EXPIRED -> {
                bindOrRenewLicense(sub);
                expireLicenseNow(sub);
            }
            default -> sub.setLicenseId(licenses.stream()
                .filter(l -> l.getStatus() == License.LicenseStatus.ACTIVE)
                .findFirst().orElse(licenses.get(0)).getId());
        }
        subscriptionRepository.save(sub);
        log.info("订阅已补绑 License：subId={}, licenseId={}, status={}",
            sub.getProviderSubscriptionId(), sub.getLicenseId(), sub.getStatus());
    }

    private static boolean isTerminal(License license) {
        return license.getStatus() == License.LicenseStatus.REVOKED
            || license.getStatus() == License.LicenseStatus.REISSUED;
    }
}
