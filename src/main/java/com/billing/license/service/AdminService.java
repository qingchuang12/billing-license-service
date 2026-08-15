package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
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
     * 退款订单：调用支付服务商退款接口，标记订单 REFUNDED，作废全部 License，发送通知
     */
    @Transactional
    public OrderResponse refundOrder(String orderNumber, String reason) {
        log.info("管理员发起退款：orderNumber={}, reason={}", orderNumber, reason);

        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));

        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED) {
            throw new BusinessException("ALREADY_REFUNDED", "Order already refunded: " + orderNumber);
        }

        // 调用支付渠道退款（若已实现）
        PaymentMethod method = resolveMethod(order.getPaymentProvider());
        if (method != null) {
            try {
                boolean ok = paymentServiceFactory.getStrategy(method)
                    .refundPayment(order, order.getPaymentIntentId(), order.getTotalAmount());
                if (!ok) {
                    log.warn("支付渠道退款未实现或返回失败，仅做本地标记：provider={}", method);
                }
            } catch (Exception e) {
                log.error("调用支付渠道退款异常，仅做本地标记：provider={}", method, e);
            }
        }

        // 作废该订单下所有 License
        List<License> licenses = licenseRepository.findByOrder(order);
        for (License license : licenses) {
            if (license.getStatus() != License.LicenseStatus.REVOKED) {
                license.setStatus(License.LicenseStatus.REVOKED);
                license.setRevokedAt(LocalDateTime.now());
                licenseRepository.save(license);
            }
        }

        // 标记订单状态
        order.setPaymentStatus(Order.PaymentStatus.REFUNDED);
        order.setStatus(Order.OrderStatus.REFUNDED);
        order.setMetadata(appendMetadata(order.getMetadata(), "refundReason", reason));
        orderRepository.save(order);

        if (order.getEmail() != null && !order.getEmail().isEmpty()) {
            emailNotificationService.sendPaymentFailureEmail(
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
        String base = metadata == null ? "" : metadata;
        if (base.isEmpty()) return "{" + "\"" + key + "\":\"" + (value == null ? "" : value) + "\"}";
        // 简单追加，避免引入 JSON 库
        return base.replaceFirst("\\}$", ",\"" + key + "\":\"" + (value == null ? "" : value) + "\"}");
    }
}
