package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.UserRefundResponse;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户端自助退款服务（plan-4.1）。
 *
 * <p>职责只有三件：<b>归属校验</b> → <b>限流</b> → <b>折算</b>，随后交给
 * {@link AdminService#refundOrder(String, String, java.math.BigDecimal)} 复用既有退款链路。
 * 渠道调用、渠道失败态（REFUND_FAILED 独立事务）、License 吊销、退款流水、邮件通知
 * 全部继承管理端实现，此处不重复。
 *
 * <p>与管理员路径的差异仅在**金额口径**：管理员始终全额，用户端按
 * {@link RefundPolicy} 依剩余有效期线性折算（可能构成部分退款）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountRefundService {

    private final OrderRepository orderRepository;
    private final LicenseRepository licenseRepository;
    private final AdminService adminService;
    private final RateLimitService rateLimitService;
    private final BillingProperties billingProperties;

    /**
     * 为当前登录用户发起订单退款。
     *
     * @return 实退金额与退款后状态（若发生降级全额退，回报的是实际全额）
     */
    public UserRefundResponse requestRefund(UUID userId, String orderNumber, String reason) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));

        // 归属校验（安全关键）：非本人订单一律按「不存在」返回，不泄露他人订单的存在性
        if (order.getCustomerId() == null || !order.getCustomerId().equals(userId)) {
            log.warn("用户退款越权尝试：userId={}, orderNumber={}", userId, orderNumber);
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber);
        }

        // 限流：按用户维度（防刷与探测）；放在归属校验之后，避免为他人订单白耗配额
        try {
            rateLimitService.checkUserRefund(userId);
        } catch (RateLimitService.RateLimitExceededException e) {
            log.warn("用户退款触发限流：userId={}, orderNumber={}", userId, orderNumber);
            throw new BusinessException("REFUND_LIMIT", "退款申请过于频繁，请稍后再试");
        }

        // 一单只退一次：部分退款后不得再退剩余（与管理端同口径）
        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                || order.getPaymentStatus() == Order.PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException("ALREADY_REFUNDED", "该订单已退款，不可重复申请");
        }

        RefundPolicy.Quote quote = RefundPolicy.quote(
                order, licenseRepository.findByOrderId(order.getId()),
                LocalDateTime.now(), billingProperties.getRefund().getMinAmount())
            .orElseThrow(() -> new BusinessException("NOT_REFUNDABLE",
                "该订单当前不满足自助退款条件（未支付 / 未发货 / 权益已到期 / 订阅订单 / 可退金额低于下限）"));

        OrderResponse refunded = adminService.refundOrder(orderNumber, reason, quote.amount());

        // 回报「实际结果」而非申请口径：降级为全额退时 paymentStatus 为 REFUNDED
        boolean fullRefund = Order.PaymentStatus.REFUNDED.name().equals(refunded.getPaymentStatus());
        log.info("用户自助退款完成：userId={}, orderNumber={}, requested={}, fullRefund={}",
            userId, orderNumber, quote.amount(), fullRefund);
        return UserRefundResponse.builder()
            .orderNumber(orderNumber)
            .refundedAmount(fullRefund ? refunded.getTotalAmount() : quote.amount())
            .fullRefund(fullRefund)
            .paymentStatus(refunded.getPaymentStatus())
            .build();
    }
}
