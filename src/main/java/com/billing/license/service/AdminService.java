package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 管理后台服务 - 订单查询、退款、License 作废（架构十一.6、十六）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final LicenseRepository licenseRepository;
    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentService paymentService;
    private final EmailNotificationService emailNotificationService;
    private final PaymentRepository paymentRepository;

    // i6：复用 ObjectMapper 构造 metadata JSON，避免手写拼接导致的转义/注入问题
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    /**
     * 列出全部订单
     */
    public List<OrderResponse> listOrders() {
        return orderRepository.findAll().stream()
            .map(orderService::mapToResponse)
            .collect(Collectors.toList());
    }

    /**
     * 按状态筛选订单
     */
    public List<OrderResponse> listOrdersByStatus(Order.OrderStatus status) {
        return orderRepository.findByStatus(status).stream()
            .map(orderService::mapToResponse)
            .collect(Collectors.toList());
    }

    /**
     * 退款订单：调用支付服务商退款接口，标记订单 REFUNDED，作废全部 License，发送通知。
     *
     * H4 资损防护：仅当支付渠道侧退款成功（refundPayment 返回 true）才标记 REFUNDED 并作废 License；
     * 若渠道未实现/返回失败/抛异常，绝不本地谎报退款成功——改为标记 REFUND_FAILED（保留 PAID），
     * 并抛出业务异常交由运营到渠道控制台手动退款，避免「账显示已退但钱没退」的资损与对账混乱。
     */
    @Transactional
    public OrderResponse refundOrder(String orderNumber, String reason) {
        log.info("管理员发起退款：orderNumber={}, reason={}", orderNumber, reason);

        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));

        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED) {
            throw new BusinessException("ALREADY_REFUNDED", "Order already refunded: " + orderNumber);
        }

        // 调用支付渠道退款
        PaymentMethod method = resolveMethod(order.getPaymentProvider());

        // C2 兜底：存量订单（provider 为 NULL）从已落库的 Payment.method 回补渠道，
        // 避免线上历史 PAID 订单退款依旧 100% 失败。Payment.method 由 createPayment 写入，比 Order 字段更权威。
        if (method == null) {
            try {
                Payment payment = paymentRepository.findByOrderIdStr(order.getId().toString()).orElse(null);
                if (payment != null && payment.getMethod() != null) {
                    method = resolveMethod(payment.getMethod());
                    if (method != null) {
                        log.info("存量订单渠道兜底：从 Payment.method 回补 provider={}, orderNumber={}", method, orderNumber);
                    }
                }
            } catch (Exception e) {
                log.warn("存量订单渠道兜底查询失败：orderNumber={}", orderNumber, e);
            }
        }

        // H5 修正：渠道交易号存储于 Payment 实体（createPayment 时落库），
        // order.paymentIntentId 从未被赋值；必须以 Payment.paymentId 作为退款目标，
        // 否则 Stripe/Paddle/PayPal 因拿不到交易号而退款失败（进而被 H4 误判为 REFUND_FAILED）。
        String channelPaymentId = order.getPaymentIntentId();
        try {
            Payment payment = paymentRepository.findByOrderIdStr(order.getId().toString()).orElse(null);
            if (payment != null && payment.getPaymentId() != null) {
                channelPaymentId = payment.getPaymentId();
            }
        } catch (Exception e) {
            log.warn("查询支付记录失败，退而使用 order.paymentIntentId：orderNumber={}", orderNumber, e);
        }

        boolean channelRefunded = false;
        if (method != null) {
            try {
                channelRefunded = paymentServiceFactory.getStrategy(method)
                    .refundPayment(order, channelPaymentId, order.getTotalAmount());
            } catch (Exception e) {
                log.error("调用支付渠道退款异常：provider={}, orderNumber={}", method, orderNumber, e);
                channelRefunded = false;
            }
        } else {
            log.warn("无法解析支付渠道，无法发起渠道侧退款：provider={}, orderNumber={}",
                order.getPaymentProvider(), orderNumber);
        }

        if (!channelRefunded) {
            // H4：渠道退款未成功——绝不能标记 REFUNDED。置内部退款失败态，保留 PAID。
            order.markRefundFailed();
            order.setMetadata(appendMetadata(order.getMetadata(), "refundReason", reason));
            orderRepository.save(order);
            throw new BusinessException("REFUND_FAILED",
                "支付渠道退款未成功（订单保持已支付）。请到支付渠道控制台手动退款，或确认渠道配置后重试。orderNumber=" + orderNumber);
        }

        // 渠道退款成功：作废该订单下所有 License
        List<License> licenses = licenseRepository.findByOrder(order);
        for (License license : licenses) {
            if (license.getStatus() != License.LicenseStatus.REVOKED) {
                license.setStatus(License.LicenseStatus.REVOKED);
                license.setRevokedAt(LocalDateTime.now());
                licenseRepository.save(license);
            }
        }

        // H15：通过状态机唯一出口标记已退款，status 与 paymentStatus 一致
        order.markRefunded();
        order.setMetadata(appendMetadata(order.getMetadata(), "refundReason", reason));
        orderRepository.save(order);

        if (order.getEmail() != null && !order.getEmail().isEmpty()) {
            // M5 修正：退款通知使用退款专用文案，不再复用「支付失败」模板
            emailNotificationService.sendRefundProcessedEmail(
                order.getEmail(), orderNumber, "退款已处理：" + (reason != null ? reason : ""));
        }

        log.info("退款完成：orderNumber={}", orderNumber);
        return orderService.mapToResponse(order);
    }

    /**
     * 作废指定 License
     */
    @Transactional
    public void revokeLicense(String licenseKey, String reason) {
        log.info("管理员作废 License：licenseKey={}, reason={}", licenseKey, reason);
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND", "License not found: " + licenseKey));
        license.setStatus(License.LicenseStatus.REVOKED);
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
    }

    /**
     * 查询订单下全部 License
     */
    public List<License> listLicensesByOrder(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));
        return licenseRepository.findByOrder(order);
    }

    private PaymentMethod resolveMethod(String provider) {
        if (provider == null) return null;
        try {
            return PaymentMethod.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String appendMetadata(String metadata, String key, String value) {
        // i6：用 ObjectMapper 构造，避免手写 JSON 拼接导致的转义/注入问题
        // （value 可能含引号、反斜杠、换行、< 等，手写会破坏 JSON 结构或被注入）
        try {
            ObjectNode node;
            if (metadata == null || metadata.isBlank()) {
                node = METADATA_MAPPER.createObjectNode();
            } else {
                try {
                    node = (ObjectNode) METADATA_MAPPER.readTree(metadata);
                } catch (Exception e) {
                    // 存量脏数据或非 JSON：以现有内容为 raw 字段兜底，不丢信息
                    node = METADATA_MAPPER.createObjectNode();
                    node.put("raw", metadata);
                }
            }
            node.put(key, value == null ? "" : value);
            return METADATA_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // 极端兜底：序列化失败时退化为手写（保证主流程不中断）
            log.warn("appendMetadata 序列化失败，回退手写拼接：key={}", key, e);
            String base = metadata == null ? "" : metadata;
            if (base.isEmpty()) return "{\"" + key + "\":\"" + (value == null ? "" : value) + "\"}";
            return base.replaceFirst("\\}$", ",\"" + key + "\":\"" + (value == null ? "" : value) + "\"}");
        }
    }
}
