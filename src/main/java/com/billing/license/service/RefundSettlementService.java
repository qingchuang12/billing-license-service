package com.billing.license.service;

import com.billing.license.entity.License;
import com.billing.license.entity.LicenseEvent;
import com.billing.license.entity.Order;
import com.billing.license.entity.RedeemCode;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 渠道退款/取消后的统一权益结算（人工退款与渠道回调共用）。
 *
 * <p>此前两条退款路径各自实现，且都只吊销已签 License、不撤销**未使用的兑换码**：
 * 用户退款后仍可用码兑换一张新授权。这里把「撤销该订单全部权益」收敛为唯一入口。
 *
 * <p>并发边界：调用方若已持有订单行锁或 Payment 行锁，本服务不再重复加锁；
 * 本服务不开新事务（沿用调用方事务）——渠道 HTTP 必须在调用前完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundSettlementService {

    private final LicenseRepository licenseRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final PaymentService paymentService;
    private final LicenseService licenseService;

    private static final com.fasterxml.jackson.databind.ObjectMapper METADATA_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 按退款金额结算订单：撤销权益（License + 未用兑换码）→ 标记全/部分退款 → 写退款流水。
     *
     * @param amount      实际退款金额；null 或达到订单总额按全额
     * @param currency    退款币种；与订单不一致时只告警（渠道退款币种由原交易决定）
     * @param refundId    渠道退款号（用于流水幂等与追溯）
     */
    @Transactional
    public void settle(Order order, PaymentMethod method, String refundId,
                       BigDecimal amount, String currency, String reason) {
        settle(order, method, refundId, amount, currency, reason, null);
    }

    /**
     * @param originalChannelId 原收款交易号（人工退款传入 Payment.paymentId）；为空时按订单流水反查
     */
    @Transactional
    public void settle(Order order, PaymentMethod method, String refundId,
                       BigDecimal amount, String currency, String reason, String originalChannelId) {
        settle(order, method, refundId, amount, currency, reason, originalChannelId, false);
    }

    /**
     * @param cumulativeAmount {@code amount} 是否为**累计退款总额**（支付宝 refund_fee 即此口径）；
     *                         true 时按「目标累计额 − 已入账额」的差额入账，重投/乱序不会重复累计
     */
    @Transactional
    public void settle(Order order, PaymentMethod method, String refundId,
                       BigDecimal amount, String currency, String reason, String originalChannelId,
                       boolean cumulativeAmount) {
        if (order == null) {
            return;
        }
        BigDecimal total = order.getTotalAmount();
        // 归一到「本渠道声明的累计退款总额」：null 或达到订单总额一律按全额
        BigDecimal reported = (amount == null || total == null || amount.compareTo(total) >= 0)
            ? total : amount;
        if (reported == null || reported.signum() <= 0) {
            log.warn("退款金额非法，仅记录不结算：orderNumber={}, amount={}", order.getOrderNumber(), amount);
            return;
        }
        if (currency != null && order.getCurrency() != null
                && !currency.equalsIgnoreCase(order.getCurrency().code())) {
            log.warn("退款币种与订单不一致（按渠道金额记账）：orderNumber={}, order={}, refund={}",
                order.getOrderNumber(), order.getCurrency().code(), currency);
        }

        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED) {
            log.info("订单已全额退款，跳过重复结算：orderNumber={}", order.getOrderNumber());
            return;
        }
        String effectiveRefundId = (refundId == null || refundId.isBlank())
            ? "RF-" + order.getId() + "-" + reported.toPlainString()
            : refundId;
        // 同一渠道退款号重复投递：资金账已存在，不能重复累计退款额
        if (ledgerRepository.existsByIdempotencyKey("refund:" + effectiveRefundId)) {
            log.info("退款流水已存在，跳过重复结算：orderNumber={}, refundId={}",
                order.getOrderNumber(), effectiveRefundId);
            return;
        }

        // 渠道可能分多次部分退款：累计已退金额，达到订单总额才置全额退款
        BigDecimal previously = recordedRefundAmount(order);
        // 累计口径下 reported 是总额而非本次额，取差额才不会把前几笔重复入账；
        // 差额 ≤ 0 表示这批通知没有新增退款（重投或乱序后到），入账与撤权都不该再发生
        BigDecimal delta = cumulativeAmount ? reported.subtract(previously) : reported;
        if (delta.signum() <= 0) {
            log.info("退款通知无新增金额，跳过入账：orderNumber={}, reported={}, recorded={}",
                order.getOrderNumber(), reported, previously);
            return;
        }
        BigDecimal cumulative = previously.add(delta);
        if (total != null && cumulative.compareTo(total) > 0) {
            cumulative = total;
        }
        boolean full = total != null && cumulative.compareTo(total) >= 0;

        revokeBenefits(order, reason);

        if (full) {
            order.markRefunded();
        } else {
            order.markPartiallyRefunded();
        }
        order.setMetadata(appendMetadata(order.getMetadata(), "refundAmount", cumulative.toPlainString()));
        if (reason != null && !reason.isBlank()) {
            order.setMetadata(appendMetadata(order.getMetadata(), "refundReason", reason));
        }

        paymentService.recordRefund(order, method, effectiveRefundId, delta,
            order.getCurrency(), null, originalChannelId, "refund-settlement");
        log.info("退款结算完成：orderNumber={}, amount={}, cumulative={}, full={}",
            order.getOrderNumber(), delta, cumulative, full);
    }

    /**
     * 撤销订单全部权益：已签 License 一律 REVOKED；未使用的兑换码一律 REVOKED。
     * 幂等：已终态的证/码不重复写事件。
     */
    @Transactional
    public void revokeBenefits(Order order, String reason) {
        List<License> licenses = licenseRepository.findByOrder(order);
        for (License license : licenses) {
            if (license.getStatus() != License.LicenseStatus.REVOKED) {
                String boundMachineCode = license.getMachineCode();
                license.setStatus(License.LicenseStatus.REVOKED);
                license.setRevokedAt(LocalDateTime.now());
                licenseRepository.save(license);
                licenseService.recordLicenseEvent(license, LicenseEvent.EventType.REVOKED, boundMachineCode,
                    "Revoked by refund. order=" + order.getOrderNumber());
            }
        }
        if (order.getOrderNumber() == null) {
            return;
        }
        List<RedeemCode> codes = redeemCodeRepository.findByOrderIdForUpdate(order.getOrderNumber());
        int revokedCodes = 0;
        for (RedeemCode code : codes) {
            if (code.getStatus() == RedeemCode.RedeemCodeStatus.UNUSED) {
                code.setStatus(RedeemCode.RedeemCodeStatus.REVOKED);
                redeemCodeRepository.save(code);
                revokedCodes++;
            }
        }
        if (revokedCodes > 0) {
            log.info("退款撤销未使用兑换码：orderNumber={}, count={}", order.getOrderNumber(), revokedCodes);
        }
    }

    /** 已记录退款总额（metadata.refundAmount），无记录返回 0。 */
    private static BigDecimal recordedRefundAmount(Order order) {
        String metadata = order.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            var node = METADATA_MAPPER.readTree(metadata);
            if (node.hasNonNull("refundAmount")) {
                return new BigDecimal(node.get("refundAmount").asText());
            }
        } catch (Exception ignored) {
            // 脏 metadata 不阻断退款结算
        }
        return BigDecimal.ZERO;
    }

    private static String appendMetadata(String metadata, String key, String value) {
        String entry = "\"" + key + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (metadata == null || metadata.isBlank()) {
            return "{" + entry + "}";
        }
        String trimmed = metadata.trim();
        if (trimmed.endsWith("}")) {
            String body = trimmed.substring(0, trimmed.length() - 1).trim();
            return body + (body.endsWith("{") ? "" : ",") + entry + "}";
        }
        return metadata;
    }
}
