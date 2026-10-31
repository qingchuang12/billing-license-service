package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.accounting.ManualRefundView;
import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentAttempt;
import com.billing.license.entity.RefundRequest;
import com.billing.license.entity.Subscription;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.RefundRequestRepository;
import com.billing.license.repository.SubscriptionRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.RefundResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 退款编排：渠道 HTTP 一律在事务外，退款意图/结果落 {@link RefundRequest} outbox。
 *
 * <p>状态机：PENDING → CHANNEL_SUCCEEDED → SETTLED；渠道明确拒绝为 FAILED，
 * 结果未知为 UNKNOWN。后台任务会反查 UNKNOWN 并补偿 CHANNEL_SUCCEEDED 的本地结算，
 * 避免「钱已退、权益还在」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentService paymentService;
    private final RefundSettlementService refundSettlementService;
    private final RefundRequestRepository refundRequestRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final EmailNotificationService emailNotificationService;
    private final CheckoutSessionRepository checkoutSessionRepository;

    /** 生产可对所有渠道开启人工审核；Creem 因渠道限制始终需要审核。 */
    @Value("${billing.refund.require-approval:false}")
    private boolean requireApproval;

    @Autowired
    @Lazy
    private RefundService self;

    public OrderResponse refundOrder(String orderNumber, String reason) {
        return refundOrder(orderNumber, reason, null);
    }

    public OrderResponse refundOrder(String orderNumber, String reason, BigDecimal refundAmount) {
        RefundPlan plan = self.prepareRefund(orderNumber, reason, refundAmount);
        if (plan.approvalRequired()) {
            sendRefundStatus(orderNumber, "退款待审核", "Refund pending review",
                plan.pendingMessage(), true);
            String code = plan.method() == PaymentMethod.CREEM
                ? "CREEM_REFUND_PENDING_REVIEW" : "REFUND_PENDING_REVIEW";
            throw new BusinessException(code, plan.pendingMessage());
        }
        return executePlan(plan);
    }

    /**
     * 审批通过后的渠道执行腿（全额自动退 / 支付宝全额或部分退）。
     * 渠道 HTTP 不在事务内，成功后走 markChannelSucceeded + settleRefund。
     */
    private OrderResponse executePlan(RefundPlan plan) {
        String orderNumber = plan.orderNumber();
        String reason = plan.reason();
        Order channelOrder = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));

        RefundResult result = callChannel(plan, channelOrder, plan.channelAmount(), plan.refundRequestId());
        boolean degraded = false;
        if (!result.isSuccess() && !plan.fullRefund()
                && result.outcome() == RefundResult.Outcome.UNSUPPORTED) {
            log.warn("渠道明确不支持部分退款，按既定政策改发全额退：provider={}, orderNumber={}, partial={}",
                plan.method(), orderNumber, plan.channelAmount());
            String fullRequestId = plan.refundRequestId() + "-full";
            // 先落「本次实际尝试的是全额退」：若全额退调用超时，后台反查既要知道请求号 -full，
            // 也要按全额结算（否则会把全额退记成部分退，剩余权益差价永远不退）。
            self.markFullRefundAttempt(plan.requestId(), fullRequestId, plan.totalAmount());
            RefundResult full = callChannel(plan, channelOrder, plan.totalAmount(), fullRequestId);
            if (full.isSuccess()) {
                result = full;
                degraded = true;
            }
        }

        if (!result.isSuccess()) {
            boolean unknown = result.outcome() == RefundResult.Outcome.UNKNOWN;
            self.markAttemptFailed(plan.requestId(), result, unknown);
            sendRefundStatus(orderNumber, "退款失败", "Refund failed",
                result.message(), true);
            throw new BusinessException(unknown ? "REFUND_RESULT_UNKNOWN" : "REFUND_FAILED",
                unknown
                    ? "支付渠道退款结果未知，已进入自动反查；请勿重复发起。orderNumber=" + orderNumber
                    : "支付渠道退款未成功（订单保持已支付）。请到支付渠道控制台手动退款，或确认渠道配置后重试。orderNumber=" + orderNumber);
        }

        BigDecimal actualAmount = degraded ? plan.totalAmount() : plan.channelAmount();
        String actualRequestId = degraded ? plan.refundRequestId() + "-full" : plan.refundRequestId();
        self.markChannelSucceeded(plan.requestId(), result.channelRefundId(), degraded,
            actualAmount, actualRequestId);
        OrderResponse response;
        try {
            response = self.settleRefund(plan.requestId());
        } catch (Exception e) {
            // 钱已经退成功，本地结算失败只能靠后台重试；不能向调用方谎报“退款失败”。
            log.error("渠道已退款但本地结算失败，已进入自动重试：requestId={}, orderNumber={}",
                plan.requestId(), orderNumber, e);
            throw new BusinessException("REFUND_CHANNEL_DONE_SETTLEMENT_PENDING",
                "渠道已确认退款，本地权益结算失败，系统将自动重试。orderNumber=" + orderNumber);
        }
        // 邮件失败不影响退款事实，更不能把已完成的结算报成“待结算”
        try {
            sendRefundEmail(orderNumber, actualAmount, reason);
        } catch (Exception e) {
            log.warn("退款通知邮件发送失败：orderNumber={}", orderNumber, e);
        }
        // 订阅订单退款后必须停掉后续续费，否则用户被退款后仍会被渠道继续扣款
        try {
            cancelSubscriptionAfterRefund(orderNumber);
        } catch (Exception e) {
            log.error("退款已成功但订阅取消失败，需人工到渠道后台停止续费：orderNumber={}", orderNumber, e);
        }
        return response;
    }

    /**
     * 管理员审核通过：
     * - Creem 全额：自动调用 Creem 退款 API；
     * - Creem 部分：转 MANUAL_REQUIRED，等待运营转账并登记流水；
     * - 其他渠道（已配置审批时）：按原自动退款链路执行。
     */
    public ManualRefundView approveRefund(UUID requestId, UUID reviewer, String note) {
        // 先认领：APPROVING 状态下其它并发审批/重复点击会被拒绝，避免渠道重复退款
        RefundRequest req = self.claimForApproval(requestId);
        Order order = orderRepository.findByOrderNumber(req.getOrderNumber()).orElse(null);
        if (order == null) {
            self.markApprovalStuck(requestId, "审批执行时订单不存在");
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + req.getOrderNumber());
        }
        boolean fullRefund = order.getTotalAmount() == null
            || req.getAmount() == null
            || req.getAmount().compareTo(order.getTotalAmount()) >= 0;

        if (req.getProvider() == PaymentMethod.CREEM && !fullRefund) {
            self.markManualRequired(requestId, reviewer, note);
            sendRefundStatus(req.getOrderNumber(), "退款已审核，等待转账", "Refund approved, awaiting transfer",
                "审批已通过，运营将通过转账方式退款；完成后系统自动结算。", true);
            return ManualRefundView.from(refundRequestRepository.findById(requestId).orElseThrow());
        }

        // 全额（或非 Creem 渠道）：记录审核人后走自动退款；状态保持 APPROVING 直到渠道结果落库
        self.markReviewed(requestId, reviewer, note, null);
        RefundPlan plan = new RefundPlan(requestId, req.getOrderNumber(), req.getProvider(),
            req.getChannelPaymentId(), req.getAmount(), req.getAmount(), order.getTotalAmount(),
            fullRefund, req.getRefundRequestId(), false, false, null, req.getReason());
        executePlan(plan);
        return ManualRefundView.from(refundRequestRepository.findById(requestId).orElseThrow());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundRequest claimForApproval(UUID requestId) {
        RefundRequest req = refundRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        if (req.getStatus() != RefundRequest.Status.PENDING_REVIEW) {
            throw new BusinessException("REFUND_NOT_PENDING_REVIEW",
                "退款请求不在待审核状态（可能已被其他管理员处理）");
        }
        req.setStatus(RefundRequest.Status.APPROVING);
        return refundRequestRepository.save(req);
    }

    /** 审核拒绝：资金未退、权益不变。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ManualRefundView rejectRefund(UUID requestId, UUID reviewer, String note) {
        RefundRequest req = refundRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        if (req.getStatus() != RefundRequest.Status.PENDING_REVIEW) {
            throw new BusinessException("REFUND_NOT_PENDING_REVIEW", "退款请求不在待审核状态");
        }
        req.setStatus(RefundRequest.Status.REJECTED);
        req.setReviewedBy(reviewer != null ? reviewer.toString() : null);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewNote(note);
        req.setLastError("审核拒绝：" + (note != null ? note : ""));
        refundRequestRepository.save(req);
        sendRefundStatus(req.getOrderNumber(), "退款申请被拒绝", "Refund request rejected",
            note != null ? note : "审核未通过", false);
        return ManualRefundView.from(req);
    }

    /**
     * 部分退款转账确认：运营已完成线下/网银转账并在管理端登记流水，
     * 系统据此结算权益与退款账本（不调用 Creem API）。
     */
    public OrderResponse confirmManualTransfer(UUID requestId, String transferChannel,
                                               String transferReference, UUID reviewer, String note) {
        RefundRequest req = refundRequestRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        if (req.getStatus() != RefundRequest.Status.MANUAL_REQUIRED) {
            throw new BusinessException("REFUND_NOT_MANUAL_REQUIRED", "退款请求不在转账待确认状态");
        }
        if (transferChannel == null || transferChannel.isBlank()
                || transferReference == null || transferReference.isBlank()) {
            throw new BusinessException("TRANSFER_INFO_REQUIRED", "转账渠道与转账流水号必填");
        }
        self.markTransferConfirmed(requestId, transferChannel, transferReference, reviewer, note);
        OrderResponse response = self.settleRefund(requestId, transferReference);
        try {
            sendRefundEmail(req.getOrderNumber(), req.getAmount(), req.getReason());
        } catch (Exception e) {
            log.warn("人工转账退款通知邮件发送失败：orderNumber={}", req.getOrderNumber(), e);
        }
        try {
            cancelSubscriptionAfterRefund(req.getOrderNumber());
        } catch (Exception e) {
            log.error("人工转账退款后订阅取消失败：orderNumber={}", req.getOrderNumber(), e);
        }
        return response;
    }

    private void cancelSubscriptionAfterRefund(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
        if (order == null) {
            return;
        }
        for (Subscription sub : subscriptionRepository.findByOrderId(order.getId())) {
            if (sub.getStatus() == Subscription.SubscriptionStatus.CANCELED
                    || sub.getStatus() == Subscription.SubscriptionStatus.EXPIRED
                    || Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
                continue;
            }
            PaymentStrategy strategy;
            try {
                strategy = paymentServiceFactory.getStrategyForExistingPayment(sub.getProvider());
            } catch (Exception e) {
                log.error("退款后取消订阅失败（渠道不可用）：subId={}", sub.getProviderSubscriptionId(), e);
                continue;
            }
            if (strategy.cancelSubscriptionAtPeriodEnd(sub.getProviderSubscriptionId())) {
                self.markSubscriptionCancelScheduled(sub.getId());
                log.info("退款后已安排订阅期末取消：orderNumber={}, subId={}",
                    orderNumber, sub.getProviderSubscriptionId());
            } else {
                log.error("退款已成功但渠道未确认订阅取消，需人工停止续费：orderNumber={}, subId={}",
                    orderNumber, sub.getProviderSubscriptionId());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubscriptionCancelScheduled(UUID subscriptionId) {
        subscriptionRepository.findById(subscriptionId).ifPresent(sub -> {
            sub.setCancelAtPeriodEnd(true);
            subscriptionRepository.save(sub);
        });
    }

    /**
     * 事务一：锁定订单/收款尝试，校验并持久化退款意图。渠道调用不在本事务内。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundPlan prepareRefund(String orderNumber, String reason, BigDecimal refundAmount) {
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));

        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                || order.getPaymentStatus() == Order.PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException("ALREADY_REFUNDED", "Order already refunded: " + orderNumber);
        }

        RefundRequest existing = refundRequestRepository.findByOrderId(order.getId()).orElse(null);

        PaymentAttempt chargeAttempt = null;
        try {
            chargeAttempt = paymentService.lockLatestSuccessfulAttempt(order.getId().toString());
        } catch (Exception e) {
            log.warn("锁定订单收款尝试失败，本次退款按订单字段继续：orderNumber={}", orderNumber, e);
        }

        PaymentMethod method = order.getPaymentProvider();
        if (method == null && chargeAttempt != null) {
            method = chargeAttempt.getMethod();
        }
        if (method == null) {
            throw new BusinessException("REFUND_CHANNEL_UNRESOLVED",
                "无法解析订单的支付渠道，无法发起渠道退款：orderNumber=" + orderNumber);
        }

        String channelPaymentId = chargeAttempt != null && chargeAttempt.getPaymentId() != null
            ? chargeAttempt.getPaymentId() : order.getPaymentIntentId();

        BigDecimal total = order.getTotalAmount();
        BigDecimal requested = refundAmount != null ? refundAmount : total;
        if (requested == null || requested.signum() <= 0) {
            throw new BusinessException("INVALID_REFUND_AMOUNT", "非法退款金额: " + refundAmount);
        }
        boolean fullRefund = total == null || requested.compareTo(total) >= 0;
        BigDecimal channelAmount = fullRefund ? total : requested;

        boolean approvalRequired = requireApproval || method == PaymentMethod.CREEM;
        boolean manualRequired = method == PaymentMethod.CREEM && !fullRefund;
        String pendingCode = method == PaymentMethod.CREEM
            ? "CREEM_REFUND_PENDING_REVIEW" : "REFUND_PENDING_REVIEW";
        String pendingMessage = !approvalRequired ? null
            : (method == PaymentMethod.CREEM
                ? (fullRefund
                    ? "Creem 全额退款需人工审核；审批通过后系统自动调用 Creem 退款 API。orderNumber=" + orderNumber
                    : "Creem 部分退款需人工审核；审批通过后请通过转账退款，并在管理端登记转账流水。orderNumber=" + orderNumber)
                : "退款需人工审核；审批通过后系统自动调用渠道退款 API。orderNumber=" + orderNumber);
        if (existing != null) {
            if (existing.getStatus() == RefundRequest.Status.PENDING_REVIEW) {
                throw new BusinessException(pendingCode, pendingMessage);
            }
            if (existing.getStatus() == RefundRequest.Status.MANUAL_REQUIRED) {
                throw new BusinessException("CREEM_PARTIAL_REFUND_MANUAL",
                    "该部分退款已审批通过，正在等待运营转账确认；请勿重复发起。orderNumber=" + orderNumber);
            }
            if (existing.getStatus() != RefundRequest.Status.FAILED
                    && existing.getStatus() != RefundRequest.Status.REJECTED) {
                throw new BusinessException("REFUND_IN_PROGRESS",
                    "该订单已有退款正在处理或等待结算，请勿重复发起：orderNumber=" + orderNumber);
            }
        }

        String refundRequestId = "RF-" + order.getId();
        RefundRequest req = existing != null ? existing : RefundRequest.builder()
            .orderId(order.getId())
            .orderNumber(orderNumber)
            .build();
        req.setProvider(method);
        req.setChannelPaymentId(channelPaymentId);
        req.setRefundRequestId(refundRequestId);
        req.setRequestedAmount(channelAmount);
        req.setAmount(channelAmount);
        req.setCurrency(order.getCurrency());
        req.setStatus(approvalRequired
            ? RefundRequest.Status.PENDING_REVIEW : RefundRequest.Status.PENDING);
        req.setChannelRequestId(refundRequestId);
        req.setAttempts((existing != null ? existing.getAttempts() : 0) + 1);
        req.setDegraded(false);
        req.setReason(reason);
        req.setLastError(pendingMessage);
        try {
            req = refundRequestRepository.saveAndFlush(req);
        } catch (DataIntegrityViolationException e) {
            // 唯一键 order_id：另一个并发退款已先落库
            throw new BusinessException("REFUND_IN_PROGRESS",
                "该订单已有退款正在处理，请勿重复发起：orderNumber=" + orderNumber);
        }

        return new RefundPlan(req.getId(), orderNumber, method, channelPaymentId,
            channelAmount, channelAmount, total, fullRefund, refundRequestId,
            approvalRequired, manualRequired, pendingMessage, reason);
    }

    /** 只反查，不再发起退款：PENDING/UNKNOWN 状态的唯一安全动作。 */
    private RefundResult queryChannel(RefundRequest req, Order order) {
        String requestId = req.getChannelRequestId() != null
            ? req.getChannelRequestId() : req.getRefundRequestId();
        try {
            return paymentServiceFactory.getStrategyForExistingPayment(req.getProvider())
                .queryRefund(order, req.getChannelPaymentId(), requestId);
        } catch (Exception e) {
            log.error("退款结果反查异常：provider={}, orderNumber={}", req.getProvider(), req.getOrderNumber(), e);
            return RefundResult.unknown("退款反查异常：" + e.getClass().getSimpleName());
        }
    }

    private RefundResult callChannel(RefundPlan plan, Order order, BigDecimal amount, String requestId) {
        PaymentStrategy strategy;
        RefundResult result;
        try {
            strategy = paymentServiceFactory.getStrategyForExistingPayment(plan.method());
            result = strategy.refund(order, plan.channelPaymentId(), amount, requestId);
        } catch (Exception e) {
            log.error("调用支付渠道退款异常：provider={}, orderNumber={}", plan.method(), plan.orderNumber(), e);
            return RefundResult.unknown("退款调用异常：" + e.getClass().getSimpleName());
        }
        if (result.outcome() == RefundResult.Outcome.UNKNOWN) {
            if (!strategy.supportsRefundQuery()) {
                log.error("渠道不支持退款反查，结果需人工到渠道后台核对：provider={}, orderNumber={}, requestId={}",
                    plan.method(), plan.orderNumber(), requestId);
                return result;
            }
            log.warn("渠道退款结果未知，先查询：provider={}, orderNumber={}, requestId={}",
                plan.method(), plan.orderNumber(), requestId);
            try {
                result = strategy.queryRefund(order, plan.channelPaymentId(), requestId);
            } catch (Exception e) {
                log.error("退款结果反查异常：provider={}, orderNumber={}", plan.method(), plan.orderNumber(), e);
                return RefundResult.unknown("退款反查异常：" + e.getClass().getSimpleName());
            }
        }
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFullRefundAttempt(UUID requestId, String channelRequestId, BigDecimal totalAmount) {
        RefundRequest req = refundRequestRepository.findById(requestId).orElse(null);
        if (req != null && req.getStatus() != RefundRequest.Status.SETTLED) {
            req.setChannelRequestId(channelRequestId);
            req.setAmount(totalAmount);
            req.setDegraded(true);
            refundRequestRepository.save(req);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markChannelSucceeded(UUID requestId, String channelRefundId, boolean degraded,
                                     BigDecimal actualAmount, String channelRequestId) {
        RefundRequest req = refundRequestRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        if (req.getStatus() == RefundRequest.Status.SETTLED) {
            return;
        }
        req.setStatus(RefundRequest.Status.CHANNEL_SUCCEEDED);
        req.setChannelRefundId(channelRefundId);
        req.setDegraded(degraded);
        req.setAmount(actualAmount);
        req.setChannelRequestId(channelRequestId);
        req.setChannelSucceededAt(LocalDateTime.now());
        req.setLastError(null);
        refundRequestRepository.save(req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResponse settleRefund(UUID requestId) {
        return settleInternal(requestId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResponse settleRefund(UUID requestId, String settlementTxnId) {
        return settleInternal(requestId, settlementTxnId);
    }

    private OrderResponse settleInternal(UUID requestId, String settlementTxnId) {
        RefundRequest req = refundRequestRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        if (req.getStatus() == RefundRequest.Status.SETTLED) {
            return orderRepository.findByOrderNumber(req.getOrderNumber())
                .map(orderService::mapToResponse)
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND",
                    "Order not found: " + req.getOrderNumber()));
        }

        Order order = orderRepository.findByOrderNumberForUpdate(req.getOrderNumber())
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND",
                "Order not found: " + req.getOrderNumber()));
        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                || order.getPaymentStatus() == Order.PaymentStatus.PARTIALLY_REFUNDED) {
            // 渠道回调已先结算：只把 outbox 收口，不重复入账
            req.setStatus(RefundRequest.Status.SETTLED);
            req.setSettledAt(LocalDateTime.now());
            refundRequestRepository.save(req);
            return orderService.mapToResponse(order);
        }

        String originalChannelId = settlementTxnId != null ? settlementTxnId
            : (req.getTransferReference() != null ? req.getTransferReference() : req.getChannelPaymentId());
        refundSettlementService.settle(order, req.getProvider(), req.getRefundRequestId(), req.getAmount(),
            req.getCurrency() != null ? req.getCurrency().code() : null,
            req.getReason(), originalChannelId);

        if (req.isDegraded()) {
            order.setMetadata(appendMetadata(order.getMetadata(), "refundDegraded",
                "渠道未受理部分退款，已按全额退（原折算额 " + req.getRequestedAmount().toPlainString() + "）"));
            orderRepository.save(order);
        }
        req.setStatus(RefundRequest.Status.SETTLED);
        req.setSettledAt(LocalDateTime.now());
        refundRequestRepository.save(req);
        return orderService.mapToResponse(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAttemptFailed(UUID requestId, RefundResult result, boolean unknown) {
        RefundRequest req = refundRequestRepository.findById(requestId).orElse(null);
        if (req == null) {
            return;
        }
        req.setStatus(unknown ? RefundRequest.Status.UNKNOWN : RefundRequest.Status.FAILED);
        req.setLastError(result != null ? result.message() : "unknown");
        refundRequestRepository.save(req);

        Order order = orderRepository.findByOrderNumberForUpdate(req.getOrderNumber()).orElse(null);
        if (order == null || order.getPaymentStatus() == Order.PaymentStatus.REFUNDED) {
            return;
        }
        order.markRefundFailed();
        if (req.getRequestedAmount() != null
                && order.getTotalAmount() != null
                && req.getRequestedAmount().compareTo(order.getTotalAmount()) < 0) {
            order.setMetadata(appendMetadata(order.getMetadata(), "refundAttemptedAmount",
                req.getRequestedAmount().toPlainString()));
        }
        orderRepository.save(order);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchRefundError(UUID requestId, String message) {
        RefundRequest req = refundRequestRepository.findById(requestId).orElse(null);
        if (req != null) {
            req.setLastError(message);
            refundRequestRepository.save(req);
        }
    }

    /**
     * 后台收口：CHANNEL_SUCCEEDED 重试本地结算；PENDING/UNKNOWN 反查渠道后再决定。
     */
    @Scheduled(fixedDelayString = "${billing.refund.retry-interval-ms:60000}")
    public void retryPendingRefunds() {
        // 审批认领后进程中断/DB 写入失败：超过 10 分钟回到 UNKNOWN，交给渠道反查收口
        for (RefundRequest req : refundRequestRepository.findTop50ByStatusInOrderByUpdatedAtAsc(
                List.of(RefundRequest.Status.APPROVING))) {
            if (req.getUpdatedAt() != null && req.getUpdatedAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
                self.markApprovalStuck(req.getId(), "审批执行超过 10 分钟未落库，转人工/渠道反查");
            }
        }
        for (RefundRequest req : refundRequestRepository.findTop50ByStatusInOrderByUpdatedAtAsc(
                List.of(RefundRequest.Status.CHANNEL_SUCCEEDED))) {
            try {
                self.settleRefund(req.getId());
            } catch (Exception e) {
                log.error("退款本地结算重试失败：requestId={}, orderNumber={}",
                    req.getId(), req.getOrderNumber(), e);
            }
        }

        // Creem 部分退款由运营在 Dashboard 操作；收到 refund.created 后订单已变退款态，
        // 这里把 outbox 收口，避免人工队列长期挂着已完成的单。
        for (RefundRequest req : refundRequestRepository.findTop50ByStatusInOrderByUpdatedAtAsc(
                List.of(RefundRequest.Status.MANUAL_REQUIRED))) {
            Order order = orderRepository.findByOrderNumber(req.getOrderNumber()).orElse(null);
            if (order != null && (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                    || order.getPaymentStatus() == Order.PaymentStatus.PARTIALLY_REFUNDED)) {
                self.markRefundSettled(req.getId());
            }
        }

        for (RefundRequest req : refundRequestRepository.findTop50ByStatusInOrderByUpdatedAtAsc(
                List.of(RefundRequest.Status.PENDING, RefundRequest.Status.UNKNOWN))) {
            if (req.getUpdatedAt() != null && req.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(2))) {
                continue;
            }
            try {
                PaymentStrategy strategy = paymentServiceFactory.getStrategyForExistingPayment(req.getProvider());
                if (!strategy.supportsRefundQuery()) {
                    // 不支持的渠道不能无限轮询；保留 UNKNOWN 等人工用渠道后台核对
                    continue;
                }
                resolveByQuery(req);
            } catch (Exception e) {
                log.error("退款结果反查重试失败：requestId={}, orderNumber={}",
                    req.getId(), req.getOrderNumber(), e);
            }
        }
    }

    /** 管理端人工退款队列（当前仅 Creem 部分退款会进入）。 */
    @Transactional(readOnly = true)
    public List<ManualRefundView> listManualRefunds() {
        return refundRequestRepository.findByStatusInOrderByUpdatedAtAsc(
                List.of(RefundRequest.Status.PENDING_REVIEW, RefundRequest.Status.MANUAL_REQUIRED)).stream()
            .map(ManualRefundView::from)
            .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReviewed(UUID requestId, UUID reviewer, String note, RefundRequest.Status status) {
        RefundRequest req = refundRequestRepository.findById(requestId).orElse(null);
        if (req == null) {
            return;
        }
        req.setReviewedBy(reviewer != null ? reviewer.toString() : null);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewNote(note);
        if (status != null) {
            req.setStatus(status);
        }
        refundRequestRepository.save(req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markManualRequired(UUID requestId, UUID reviewer, String note) {
        RefundRequest req = refundRequestRepository.findById(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        req.setStatus(RefundRequest.Status.MANUAL_REQUIRED);
        req.setReviewedBy(reviewer != null ? reviewer.toString() : null);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewNote(note);
        req.setLastError("审批通过：等待运营通过转账方式退款并登记转账流水");
        refundRequestRepository.save(req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTransferConfirmed(UUID requestId, String transferChannel, String transferReference,
                                      UUID reviewer, String note) {
        RefundRequest req = refundRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new BusinessException("REFUND_REQUEST_NOT_FOUND", "退款请求不存在"));
        if (req.getStatus() != RefundRequest.Status.MANUAL_REQUIRED) {
            throw new BusinessException("REFUND_NOT_MANUAL_REQUIRED",
                "退款请求不在转账待确认状态（可能已被处理）");
        }
        req.setTransferChannel(transferChannel);
        req.setTransferReference(transferReference);
        req.setReviewedBy(reviewer != null ? reviewer.toString() : null);
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewNote(note);
        req.setStatus(RefundRequest.Status.CHANNEL_SUCCEEDED);
        req.setChannelRefundId(transferReference);
        req.setChannelRequestId(transferReference);
        req.setChannelSucceededAt(LocalDateTime.now());
        req.setLastError(null);
        refundRequestRepository.save(req);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markApprovalStuck(UUID requestId, String message) {
        RefundRequest req = refundRequestRepository.findById(requestId).orElse(null);
        if (req != null && req.getStatus() == RefundRequest.Status.APPROVING) {
            req.setStatus(RefundRequest.Status.UNKNOWN);
            req.setLastError(message);
            refundRequestRepository.save(req);
            log.error("退款审批认领后未完成，已转 UNKNOWN 待反查：requestId={}, orderNumber={}",
                requestId, req.getOrderNumber());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundSettled(UUID requestId) {
        RefundRequest req = refundRequestRepository.findById(requestId).orElse(null);
        if (req != null && req.getStatus() != RefundRequest.Status.SETTLED) {
            req.setStatus(RefundRequest.Status.SETTLED);
            req.setSettledAt(LocalDateTime.now());
            req.setLastError(null);
            refundRequestRepository.save(req);
            log.info("人工退款已收口：requestId={}, orderNumber={}", requestId, req.getOrderNumber());
        }
    }

    private void resolveByQuery(RefundRequest req) {
        Order order = orderRepository.findByOrderNumber(req.getOrderNumber()).orElse(null);
        if (order == null) {
            return;
        }
        RefundResult result = queryChannel(req, order);
        if (result.isSuccess()) {
            String channelRequestId = req.getChannelRequestId() != null
                ? req.getChannelRequestId() : req.getRefundRequestId();
            self.markChannelSucceeded(req.getId(), result.channelRefundId(), req.isDegraded(),
                req.getAmount(), channelRequestId);
            self.settleRefund(req.getId());
        } else if (result.outcome() == RefundResult.Outcome.REJECTED
                || result.outcome() == RefundResult.Outcome.UNSUPPORTED) {
            self.markAttemptFailed(req.getId(), result, false);
        } else {
            self.touchRefundError(req.getId(), result.message());
        }
    }

    private void sendRefundStatus(String orderNumber, String statusZh, String statusEn,
                                  String detail, boolean notifyOps) {
        try {
            Order order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
            if (order != null && order.getEmail() != null && !order.getEmail().isEmpty()) {
                String locale = checkoutSessionRepository.findByOrderNumber(orderNumber)
                    .map(CheckoutSession::getLocale).orElse(null);
                emailNotificationService.sendRefundStatusEmail(order.getEmail(), orderNumber,
                    statusZh, statusEn, detail, locale);
            }
            if (notifyOps) {
                emailNotificationService.sendRefundOpsEmail(orderNumber, statusZh, detail);
            }
        } catch (Exception e) {
            log.warn("退款状态通知发送失败：orderNumber={}", orderNumber, e);
        }
    }

    private void sendRefundEmail(String orderNumber, BigDecimal refundedAmount, String reason) {
        Order order = orderRepository.findByOrderNumber(orderNumber).orElse(null);
        if (order == null || order.getEmail() == null || order.getEmail().isEmpty()) {
            return;
        }
        String currency = order.getCurrency() != null ? " " + order.getCurrency().code() : "";
        String locale = checkoutSessionRepository.findByOrderNumber(orderNumber)
            .map(CheckoutSession::getLocale).orElse(null);
        boolean en = EmailNotificationService.preferEn(locale);
        String detail = (en ? "Refund processed: " : "退款已处理：") + refundedAmount.toPlainString() + currency
            + (reason != null && !reason.isBlank()
                ? (en ? " (" + reason + ")" : "（" + reason + "）") : "");
        emailNotificationService.sendRefundProcessedEmail(order.getEmail(), orderNumber, detail, locale);
    }

    private static String appendMetadata(String metadata, String key, String value) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = metadata == null || metadata.isBlank()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata);
            node.put(key, value);
            return node.toString();
        } catch (Exception e) {
            return "{\"" + key + "\":\"" + value.replace("\"", "\\\"") + "\"}";
        }
    }

    public record RefundPlan(UUID requestId, String orderNumber, PaymentMethod method,
                             String channelPaymentId, BigDecimal requestedAmount, BigDecimal channelAmount,
                             BigDecimal totalAmount, boolean fullRefund, String refundRequestId,
                             boolean approvalRequired, boolean manualRequired,
                             String pendingMessage, String reason) {
    }
}
