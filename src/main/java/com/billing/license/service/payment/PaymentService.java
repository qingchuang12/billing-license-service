package com.billing.license.service.payment;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.PaymentAttempt;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentAttemptRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentCloseResult;
import com.billing.license.service.payment.strategy.PaymentLinkContext;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentQueryResult;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 支付核心（#55）：渠道下单、支付尝试、不可变资金账。
 *
 * <p>分工固定：
 * <ul>
 *   <li>{@link PaymentAttempt} 记录每次选渠道/回调的会话状态，可多行、可失败；</li>
 *   <li>{@link PaymentLedgerEntry} 只记已确认的收款/退款，按幂等键唯一，只增不改；</li>
 *   <li>订单只保留支付聚合（状态、实际收款渠道），不再充当资金流水。</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 复式记账科目（账户表暂不建表；科目字符串是唯一事实源，后续可迁移为 account 表）
    public static final String ACCOUNT_CHANNEL_CLEARING = "CHANNEL_CLEARING";
    public static final String ACCOUNT_REVENUE = "REVENUE";
    public static final String ACCOUNT_REVENUE_REFUND = "REVENUE_REFUND";
    public static final String ACCOUNT_FEE_EXPENSE = "FEE_EXPENSE";

    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentAttemptRepository attemptRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final OrderRepository orderRepository;

    public PaymentService(PaymentServiceFactory paymentServiceFactory,
                          PaymentAttemptRepository attemptRepository,
                          PaymentLedgerRepository ledgerRepository,
                          OrderRepository orderRepository) {
        this.paymentServiceFactory = paymentServiceFactory;
        this.attemptRepository = attemptRepository;
        this.ledgerRepository = ledgerRepository;
        this.orderRepository = orderRepository;
    }

    /** 向渠道发起下单（无产品级回跳上下文）。 */
    public PaymentResponse requestPayment(Order order, PaymentMethod method) {
        return requestPayment(order, method, PaymentLinkContext.empty());
    }

    /** 带产品级回跳/支付形态上下文的下单。 */
    public PaymentResponse requestPayment(Order order, PaymentMethod method, PaymentLinkContext context) {
        logger.info("向渠道发起支付创建：orderId={}, method={}", order.getOrderNo(), method);
        return paymentServiceFactory.getStrategy(method).createPayment(order, context);
    }

    /**
     * 记录一次支付尝试。相同 paymentId 的重复选择只更新状态，不新增行；
     * 已成功的尝试绝不覆盖。
     */
    @Transactional
    public PaymentAttempt recordAttempt(Order order, PaymentMethod method, PaymentResponse response) {
        PaymentAttempt attempt = response.getPaymentId() == null ? null
            : attemptRepository.findByPaymentId(response.getPaymentId()).orElse(null);
        if (attempt != null && attempt.getStatus() == PaymentStatus.SUCCESS) {
            return attempt;
        }
        if (attempt == null) {
            attempt = PaymentAttempt.builder()
                .orderIdStr(order.getId().toString())
                .orderNumber(order.getOrderNo())
                .paymentId(response.getPaymentId() == null
                    ? "local_" + order.getOrderNo() + "_" + System.currentTimeMillis()
                    : response.getPaymentId())
                .build();
        }
        attempt.setOrderIdStr(order.getId().toString());
        attempt.setOrderNumber(order.getOrderNo());
        attempt.setAmount(order.getAmount());
        attempt.setCurrency(order.getCurrency());
        attempt.setMethod(method);
        attempt.setStatus(toStatus(response.getStatus()));
        if (response.getExtraParams() != null) {
            try {
                attempt.setMetadata(objectMapper.writeValueAsString(response.getExtraParams()));
            } catch (Exception e) {
                logger.warn("序列化支付尝试元数据失败", e);
            }
        }
        return attemptRepository.save(attempt);
    }

    public PaymentQueryResult queryPaymentDetail(String paymentId, PaymentMethod method) {
        return paymentServiceFactory.getStrategyForExistingPayment(method).queryPaymentDetail(paymentId);
    }

    public PaymentStatus queryPaymentStatus(String paymentId, PaymentMethod method) {
        return paymentServiceFactory.getStrategyForExistingPayment(method).queryPayment(paymentId);
    }

    /** #50：关闭已过期未付款交易（渠道 HTTP，必须在事务外调用）。 */
    public PaymentCloseResult closeUnpaidPayment(String paymentId, PaymentMethod method) {
        return paymentServiceFactory.getStrategyForExistingPayment(method).closeUnpaidPayment(paymentId);
    }

    /**
     * 定位本次回调对应的支付尝试：paymentId → transactionId → 该订单最近一次未终态尝试。
     * 有渠道时校验归属；不一致抛 {@code WEBHOOK_PAYMENT_MISMATCH}。
     */
    public PaymentAttempt findAttemptForCharge(String paymentId, String transactionId,
                                               String orderNumber, PaymentMethod expectedMethod) {
        PaymentAttempt attempt = locateAttempt(paymentId, transactionId, orderNumber);
        if (attempt == null) {
            return null;
        }
        if (expectedMethod != null && attempt.getMethod() != null && attempt.getMethod() != expectedMethod) {
            throw new BusinessException("WEBHOOK_PAYMENT_MISMATCH",
                "回调渠道与支付尝试渠道不一致：attempt=" + attempt.getMethod() + ", webhook=" + expectedMethod);
        }
        return attempt;
    }

    /** 回调没有对应尝试时补建一条（例如渠道先回调、或建单时未能落库）。 */
    @Transactional
    public PaymentAttempt ensureAttemptForWebhook(Order order, WebhookPayload data, PaymentMethod method) {
        PaymentAttempt attempt = findAttemptForCharge(data.getPaymentId(), data.getTransactionId(),
            order.getOrderNo(), method);
        if (attempt != null) {
            return attempt;
        }
        String paymentId = data.getPaymentId() != null && !data.getPaymentId().isBlank()
            ? data.getPaymentId()
            : "webhook_" + order.getOrderNo() + "_" + (data.getWebhookEventId() != null
                ? data.getWebhookEventId() : java.util.UUID.randomUUID());
        if (paymentId.length() > 128) {
            paymentId = paymentId.substring(0, 128);
        }
        PaymentAttempt created = PaymentAttempt.builder()
            .orderIdStr(order.getId().toString())
            .orderNumber(order.getOrderNo())
            .paymentId(paymentId)
            .transactionId(data.getTransactionId())
            .amount(data.getAmount() != null ? data.getAmount() : order.getTotalAmount())
            .currency(order.getCurrency())
            .method(method)
            .status(PaymentStatus.PENDING)
            .build();
        logger.warn("回调未找到支付尝试，已补建：orderNumber={}, paymentId={}", order.getOrderNo(), paymentId);
        return attemptRepository.save(created);
    }

    @Transactional
    public PaymentAttempt updateAttemptStatus(PaymentAttempt attempt, PaymentStatus status,
                                              String transactionId) {
        if (attempt == null) {
            return null;
        }
        if (attempt.getStatus() == PaymentStatus.SUCCESS && status == PaymentStatus.PENDING) {
            return attempt;
        }
        attempt.setStatus(status);
        if (transactionId != null) {
            attempt.setTransactionId(transactionId);
        }
        if (status == PaymentStatus.SUCCESS && attempt.getPaidAt() == null) {
            attempt.setPaidAt(LocalDateTime.now());
        }
        return attemptRepository.save(attempt);
    }

    /**
     * 记录已确认收款（幂等）。同时把订单的实际收款渠道落为该方法，
     * 供管理端退款按真实成交渠道定位。
     */
    @Transactional
    public PaymentLedgerEntry recordCharge(Order order, PaymentAttempt attempt, PaymentMethod method,
                                           BigDecimal amount, Currency currency, String transactionId,
                                           String idempotencyKey, String metadata) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("INVALID_CHARGE_AMOUNT", "收款金额非法");
        }
        String key = normalizeKey(idempotencyKey, "charge:" + transactionId);
        Optional<PaymentLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (attempt != null) {
            attempt.setStatus(PaymentStatus.SUCCESS);
            if (transactionId != null) {
                attempt.setTransactionId(transactionId);
            }
            if (attempt.getPaidAt() == null) {
                attempt.setPaidAt(LocalDateTime.now());
            }
            attemptRepository.save(attempt);
        }
        order.setPaymentProvider(method);
        orderRepository.save(order);
        PaymentLedgerEntry entry = PaymentLedgerEntry.builder()
            .orderIdStr(order.getId().toString())
            .orderNumber(order.getOrderNo())
            .attemptId(attempt != null ? attempt.getId() : null)
            .entryType(PaymentLedgerEntry.EntryType.CHARGE)
            .debitAccount(ACCOUNT_CHANNEL_CLEARING)
            .creditAccount(ACCOUNT_REVENUE)
            .amount(amount)
            .currency(currency != null ? currency : order.getCurrency())
            .provider(method)
            .channelTxnId(transactionId)
            .idempotencyKey(key)
            .occurredAt(LocalDateTime.now())
            .metadata(metadata)
            .build();
        return ledgerRepository.save(entry);
    }

    /** 记录已确认退款（幂等）。退款不改写任何收款流水。 */
    @Transactional
    public PaymentLedgerEntry recordRefund(Order order, PaymentMethod method, String refundId,
                                           BigDecimal amount, Currency currency, Long attemptId,
                                           String channelTxnId, String metadata) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("INVALID_REFUND_AMOUNT", "退款金额非法");
        }
        String key = "refund:" + (refundId != null && !refundId.isBlank()
            ? refundId : order.getId() + ":" + amount.toPlainString());
        Optional<PaymentLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentLedgerEntry entry = PaymentLedgerEntry.builder()
            .orderIdStr(order.getId().toString())
            .orderNumber(order.getOrderNo())
            .attemptId(attemptId)
            .entryType(PaymentLedgerEntry.EntryType.REFUND)
            .debitAccount(ACCOUNT_REVENUE_REFUND)
            .creditAccount(ACCOUNT_CHANNEL_CLEARING)
            .amount(amount)
            .currency(currency != null ? currency : order.getCurrency())
            .provider(method)
            .channelTxnId(channelTxnId)
            .idempotencyKey(key)
            .occurredAt(LocalDateTime.now())
            .metadata(metadata)
            .build();
        return ledgerRepository.save(entry);
    }

    /**
     * 记录渠道手续费（复式分录：借 FEE_EXPENSE / 贷 CHANNEL_CLEARING）。
     * 手续费与订单无强关联（可能是月度账单汇总），orderIdStr 允许用渠道侧汇总标识。
     */
    @Transactional
    public PaymentLedgerEntry recordFee(String orderIdStr, String orderNumber, PaymentMethod method,
                                        BigDecimal fee, Currency currency, String channelTxnId,
                                        String idempotencyKey, String metadata) {
        if (fee == null || fee.signum() <= 0) {
            throw new BusinessException("INVALID_FEE_AMOUNT", "手续费金额非法");
        }
        String key = normalizeKey(idempotencyKey,
            "fee:" + method + ":" + (channelTxnId != null ? channelTxnId : fee.toPlainString()));
        Optional<PaymentLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        String ledgerOrderId = orderIdStr != null && !orderIdStr.isBlank()
            ? orderIdStr
            : ("CHANNEL_FEE:" + method + ":" + (channelTxnId != null ? channelTxnId : "unknown"));
        if (ledgerOrderId.length() > 64) {
            ledgerOrderId = ledgerOrderId.substring(0, 64);
        }
        PaymentLedgerEntry entry = PaymentLedgerEntry.builder()
            .orderIdStr(ledgerOrderId)
            .orderNumber(orderNumber)
            .entryType(PaymentLedgerEntry.EntryType.FEE)
            .debitAccount(ACCOUNT_FEE_EXPENSE)
            .creditAccount(ACCOUNT_CHANNEL_CLEARING)
            .amount(fee)
            .currency(currency)
            .provider(method)
            .channelTxnId(channelTxnId)
            .idempotencyKey(key)
            .occurredAt(LocalDateTime.now())
            .metadata(metadata)
            .build();
        return ledgerRepository.save(entry);
    }

    /**
     * 冲正：交换原分录借贷方向生成新分录，绝不修改/删除原分录。
     * 幂等键默认 {@code reversal:<originalId>}，重复调用返回同一条冲正。
     */
    @Transactional
    public PaymentLedgerEntry recordReversal(Long originalId, String reason, String idempotencyKey) {
        PaymentLedgerEntry original = ledgerRepository.findById(originalId)
            .orElseThrow(() -> new BusinessException("LEDGER_ENTRY_NOT_FOUND",
                "原分账不存在: " + originalId));
        String key = normalizeKey(idempotencyKey, "reversal:" + originalId);
        Optional<PaymentLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        String debit = original.getCreditAccount() != null
            ? original.getCreditAccount() : defaultCredit(original.getEntryType());
        String credit = original.getDebitAccount() != null
            ? original.getDebitAccount() : defaultDebit(original.getEntryType());
        PaymentLedgerEntry entry = PaymentLedgerEntry.builder()
            .orderIdStr(original.getOrderIdStr())
            .orderNumber(original.getOrderNumber())
            .attemptId(original.getAttemptId())
            .entryType(PaymentLedgerEntry.EntryType.REVERSAL)
            .debitAccount(debit)
            .creditAccount(credit)
            .amount(original.getAmount())
            .currency(original.getCurrency())
            .provider(original.getProvider())
            .channelTxnId(original.getChannelTxnId())
            .reversalOf(original.getId())
            .idempotencyKey(key)
            .occurredAt(LocalDateTime.now())
            .metadata(reason)
            .build();
        return ledgerRepository.save(entry);
    }

    private static String defaultDebit(PaymentLedgerEntry.EntryType type) {
        return switch (type) {
            case CHARGE -> ACCOUNT_CHANNEL_CLEARING;
            case REFUND -> ACCOUNT_REVENUE_REFUND;
            case FEE -> ACCOUNT_FEE_EXPENSE;
            case REVERSAL -> ACCOUNT_CHANNEL_CLEARING;
        };
    }

    private static String defaultCredit(PaymentLedgerEntry.EntryType type) {
        return switch (type) {
            case CHARGE -> ACCOUNT_REVENUE;
            case REFUND -> ACCOUNT_CHANNEL_CLEARING;
            case FEE -> ACCOUNT_CHANNEL_CLEARING;
            case REVERSAL -> ACCOUNT_REVENUE;
        };
    }

    public Optional<PaymentLedgerEntry> findLatestCharge(String orderIdStr) {
        return ledgerRepository.findByOrderIdStrOrderByOccurredAtDesc(orderIdStr).stream()
            .filter(e -> e.getEntryType() == PaymentLedgerEntry.EntryType.CHARGE)
            .findFirst();
    }

    public List<PaymentLedgerEntry> ledgerForOrder(String orderIdStr) {
        return ledgerRepository.findByOrderIdStrOrderByOccurredAtDesc(orderIdStr);
    }

    /** 锁住该订单最近一次成功收款尝试（退款串行化令牌）。 */
    @Transactional
    public PaymentAttempt lockLatestSuccessfulAttempt(String orderIdStr) {
        List<PaymentAttempt> rows = attemptRepository.findForUpdateByOrderAndStatus(
            orderIdStr, PaymentStatus.SUCCESS);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PaymentAttempt locateAttempt(String paymentId, String transactionId, String orderNumber) {
        if (paymentId != null) {
            Optional<PaymentAttempt> byId = attemptRepository.findByPaymentId(paymentId);
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        if (transactionId != null) {
            List<PaymentAttempt> byTxn =
                attemptRepository.findByTransactionIdOrderByCreatedAtDesc(transactionId);
            if (!byTxn.isEmpty()) {
                return byTxn.get(0);
            }
        }
        if (orderNumber != null) {
            Optional<Order> order = orderRepository.findByOrderNumber(orderNumber);
            if (order.isPresent()) {
                List<PaymentAttempt> rows = attemptRepository
                    .findAllByOrderIdStrOrderByCreatedAtDesc(order.get().getId().toString());
                return rows.isEmpty() ? null : rows.get(0);
            }
        }
        return null;
    }

    private static String normalizeKey(String preferred, String fallback) {
        String key = preferred != null && !preferred.isBlank() ? preferred : fallback;
        if (key == null || key.isBlank()) {
            throw new BusinessException("MISSING_IDEMPOTENCY_KEY", "资金流水缺少幂等键");
        }
        return key.length() > 160 ? key.substring(0, 160) : key;
    }

    private static PaymentStatus toStatus(String status) {
        if (status == null || status.isBlank()) {
            return PaymentStatus.PENDING;
        }
        try {
            return PaymentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return PaymentStatus.PENDING;
        }
    }
}
