package com.billing.license.service;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Product;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户端退款折算内核（plan-4.1）：**全周期线性折算** + 资格判定。
 *
 * <p><b>口径</b>（2026-09-23 拍板）：
 * <pre>
 *   总天数   = max(license.expiresAt) − min(license.issuedAt)
 *   剩余天数 = max(license.expiresAt) − now        // 负数截为 0
 *   可退额   = 订单实付额 × 剩余天数 ÷ 总天数
 * </pre>
 * 可退窗口即 License 有效期本身——权益自然到期即不可退。故不额外设「支付后 N 天」的申请窗口：
 * 若同时设短窗口，买断件（3650 天）在第 7 天申请时折算率仍高达 99.8%，折算将退化为近全额、失去意义。
 *
 * <p>买断件并非「无到期日」：{@code pro-buyout}/{@code pro-plus-buyout} 虽为
 * {@code billing_cycle = LIFETIME}，但 {@code license_duration_days = 3650}，
 * {@code LicenseService} 一律以 {@code expiresAt = issuedAt + licenseDurationDays} 签发，
 * 故买断与订阅一条口径通吃。
 *
 * <p>纯计算、无 Spring 依赖，便于直接单测。
 */
public final class RefundPolicy {

    /**
     * 订阅类计费周期：渠道侧无「发起取消订阅」的调用能力（仅处理入向 webhook 的 canceled 事件），
     * 退款而不取消订阅会让用户拿回本期费用、渠道继续扣款，属资损，故不开放自助退款。
     */
    private static final Set<Product.BillingCycle> SUBSCRIPTION_CYCLES = Set.of(
        Product.BillingCycle.MONTHLY, Product.BillingCycle.QUARTERLY, Product.BillingCycle.YEARLY);

    /** 可退额小数位：与当前支持币种（CNY / USD）小数位一致 */
    private static final int AMOUNT_SCALE = 2;

    private RefundPolicy() {
    }

    /**
     * 折算结果。
     *
     * @param amount     本次可退金额（已按币种精度取整）
     * @param fullRefund 是否构成全额退款（全额走既有 {@code markRefunded} 路径，不产生部分退款态）
     */
    public record Quote(BigDecimal amount, boolean fullRefund) {
    }

    /**
     * 计算某订单当前的可退额。
     *
     * @param order    待退款订单
     * @param licenses 该订单名下全部 License
     * @param now      计算时点
     * @param minAmount 可退下限（订单币种），低于此值不开放自助退款；null 表示不设下限
     * @return 可退报价；<b>空</b>表示不可退（未支付 / 已退款 / 订阅订单 / 未发货 / 权益已失效 / 金额低于下限）
     */
    public static Optional<Quote> quote(Order order, List<License> licenses,
                                        LocalDateTime now, BigDecimal minAmount) {
        if (order == null || order.getPaymentStatus() != Order.PaymentStatus.PAID) {
            return Optional.empty();
        }
        BigDecimal total = order.getTotalAmount();
        if (total == null || total.signum() <= 0) {
            return Optional.empty();
        }
        if (isSubscriptionOrder(order)) {
            return Optional.empty();
        }
        if (licenses == null || licenses.isEmpty()) {
            return Optional.empty();
        }
        // 全部 License 均已被吊销 → 无可退权益（如已因违规被作废，不应再退款）
        boolean hasLiveLicense = licenses.stream()
            .anyMatch(l -> l != null && l.getStatus() != License.LicenseStatus.REVOKED);
        if (!hasLiveLicense) {
            return Optional.empty();
        }

        LocalDateTime issuedAt = null;
        LocalDateTime expiresAt = null;
        for (License license : licenses) {
            if (license == null) {
                continue;
            }
            LocalDateTime issued = license.getIssuedAt();
            LocalDateTime expires = license.getExpiresAt();
            if (issued != null && (issuedAt == null || issued.isBefore(issuedAt))) {
                issuedAt = issued;
            }
            if (expires != null && (expiresAt == null || expires.isAfter(expiresAt))) {
                expiresAt = expires;
            }
        }
        if (issuedAt == null || expiresAt == null) {
            return Optional.empty();
        }

        long totalDays = ChronoUnit.DAYS.between(issuedAt, expiresAt);
        if (totalDays <= 0) {
            return Optional.empty();
        }
        long remainingDays = ChronoUnit.DAYS.between(now, expiresAt);
        if (remainingDays <= 0) {
            // 权益已到期：窗口关闭
            return Optional.empty();
        }
        if (remainingDays >= totalDays) {
            // 尚未使用时（或签发时间晚于计算时点）：全额退
            return Optional.of(new Quote(total, true));
        }

        BigDecimal prorated = total.multiply(BigDecimal.valueOf(remainingDays))
            .divide(BigDecimal.valueOf(totalDays), AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (prorated.compareTo(total) >= 0) {
            return Optional.of(new Quote(total, true));
        }
        if (minAmount != null && prorated.compareTo(minAmount) < 0) {
            return Optional.empty();
        }
        return Optional.of(new Quote(prorated, false));
    }

    /** 订单是否含订阅类商品（任一商品为订阅周期即视为订阅订单） */
    private static boolean isSubscriptionOrder(Order order) {
        if (order.getOrderItems() == null) {
            return false;
        }
        return order.getOrderItems().stream()
            .filter(item -> item != null && item.getProduct() != null)
            .anyMatch(item -> SUBSCRIPTION_CYCLES.contains(item.getProduct().getBillingCycle()));
    }
}
