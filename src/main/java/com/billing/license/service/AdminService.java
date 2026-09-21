package com.billing.license.service;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // E1/E3（客户标识邮箱化）：对外邮箱 ↔ 内部 userId 解析，及出参 customerEmail 回填
    private final CustomerIdentityService customerIdentityService;
    private final UserRepository userRepository;

    // i6：复用 ObjectMapper 构造 metadata JSON，避免手写拼接导致的转义/注入问题
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    /**
     * 订单查询（I2 收敛）：支持按状态 / 订单号 / 订单 ID 过滤，三者全部留空即全量列表。
     * 一个端点替代原 {@code listOrders} + {@code listOrdersByStatus} + OrderController 的 by-id/by-number 四个入口。
     */
    public List<OrderResponse> listOrders(Order.OrderStatus status, String orderNumber, String orderId) {
        List<Order> orders;
        if (orderNumber != null && !orderNumber.isBlank()) {
            orders = orderRepository.findByOrderNumber(orderNumber.trim())
                .map(o -> List.of(o)).orElse(List.of());
        } else if (orderId != null && !orderId.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(orderId.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_ORDER_ID", "非法的订单 ID: " + orderId);
            }
            orders = orderRepository.findById(id).map(o -> List.of(o)).orElse(List.of());
        } else if (status != null) {
            orders = orderRepository.findByStatus(status);
        } else {
            orders = orderRepository.findAll();
        }
        return orders.stream().map(orderService::mapToResponse).collect(Collectors.toList());
    }

    /** 按订单号取订单实体（供签发等管理动作复用） */
    public Order getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));
    }

    /**
     * License 查询（I3 收敛）：支持按客户邮箱 / 订单号 / 状态过滤，三者全部留空即全量；
     * 返回脱敏管理视图（不回显 signedToken）。一个端点替代原
     * {@code /api/licenses/customer/{cid}} 与 {@code /api/admin/orders/{n}/licenses}。
     */
    public List<LicenseResponse> listLicenses(String customerEmail, String orderNumber, String status) {
        List<License> licenses;
        if (orderNumber != null && !orderNumber.isBlank()) {
            licenses = licenseRepository.findByOrder(getOrderByNumber(orderNumber.trim()));
        } else if (customerEmail != null && !customerEmail.isBlank()) {
            // E2：对外邮箱经解析器换成内部 userId 再查；未注册邮箱 → 无匹配（只读解析，不建号）
            UUID customerId = customerIdentityService.resolveExisting(customerEmail).orElse(null);
            licenses = (customerId == null) ? List.of() : licenseRepository.findByCustomerId(customerId);
        } else {
            licenses = licenseRepository.findAll();
        }
        if (status != null && !status.isBlank()) {
            License.LicenseStatus target;
            try {
                target = License.LicenseStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_LICENSE_STATUS", "非法的 License 状态值: " + status);
            }
            licenses = licenses.stream()
                .filter(l -> l.getStatus() == target)
                .collect(Collectors.toList());
        }
        // E3：批量解析本页 customerId → 邮箱，避免逐条查库（N+1）
        List<UUID> customerIds = licenses.stream()
            .map(License::getCustomerId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        Map<UUID, String> emailById = customerIds.isEmpty()
            ? Map.of()
            : userRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
        return licenses.stream()
            .map(l -> LicenseResponse.adminView(l,
                l.getCustomerId() == null ? null : emailById.get(l.getCustomerId())))
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
        PaymentMethod method = order.getPaymentProvider();

        // C2 兜底：存量订单（provider 为 NULL）从已落库的 Payment.method 回补渠道，
        // 避免线上历史 PAID 订单退款依旧 100% 失败。Payment.method 由 createPayment 写入，比 Order 字段更权威。
        if (method == null) {
            try {
                Payment payment = paymentRepository.findByOrderIdStr(order.getId().toString()).orElse(null);
                if (payment != null && payment.getMethod() != null) {
                    method = payment.getMethod();
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
            // C8：退款失败态必须在独立事务（REQUIRES_NEW）中落库，否则随外层 @Transactional 回滚，
            // 导致 DB 永远 PAID、运营看不到待处理清单。内层提交后外层再抛异常回滚不影响已落库的失败态。
            try {
                persistRefundFailed(order, reason);
            } catch (Exception ex) {
                log.error("标记退款失败态异常（内层事务）：orderNumber={}", orderNumber, ex);
            }
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

        // 退款流水：渠道退款成功后写入一条 REFUNDED 支付记录，使交易流水（Payment 表）
        // 完整反映资金变动（此前退款只改订单状态、流水缺退款行）。与 markRefunded 同事务提交，
        // 保证「状态 REFUNDED」与「流水有退款行」原子一致；同批失败则整体回滚、订单回到 PAID 供重试。
        writeRefundPayment(order, method, channelPaymentId);

        if (order.getEmail() != null && !order.getEmail().isEmpty()) {
            // M5 修正：退款通知使用退款专用文案，不再复用「支付失败」模板
            emailNotificationService.sendRefundProcessedEmail(
                order.getEmail(), orderNumber, "退款已处理：" + (reason != null ? reason : ""));
        }

        log.info("退款完成：orderNumber={}", orderNumber);
        return orderService.mapToResponse(order);
    }

    /**
     * 写入一条 REFUNDED 支付流水（全额退款，与订单同币种）。
     *
     * <p>{@code payment_id} 唯一非空，不能复用原支付记录，故用 {@code REFUND-<orderId>-<时间戳>} 独立标识；
     * {@code transactionId} 关联发起退款时使用的渠道交易号（{@code channelPaymentId}）以便追溯。
     * {@code channel} 与 {@code method} 同源写入，对齐 {@code AccountingService.toView} 的展示口径。
     */
    private void writeRefundPayment(Order order, PaymentMethod method, String channelPaymentId) {
        Payment refund = Payment.builder()
                .orderIdStr(order.getId().toString())
                .paymentId("REFUND-" + order.getId() + "-" + System.currentTimeMillis())
                .transactionId(channelPaymentId)
                .amount(order.getTotalAmount())
                .currency(order.getCurrency())
                .method(method)
                .channel(method)
                .status(PaymentStatus.REFUNDED)
                .paidAt(LocalDateTime.now())
                .build();
        paymentRepository.save(refund);
    }

    /**
     * C8：在独立事务中持久化退款失败态（markRefundFailed + 记录原因），
     * 避免被外层退款事务回滚。重新按 id 加载，避免修改外层受管实体。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistRefundFailed(Order order, String reason) {
        order.markRefundFailed();
        order.setMetadata(appendMetadata(order.getMetadata(), "refundReason", reason));
        orderRepository.save(order);
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
     * H-C2：按 License 密钥查询详情（管理端）。
     *
     * <p>补齐管理端查询缺口：公开端点 {@code GET /api/licenses/verify/{licenseKey}} 对
     * EXPIRED / REVOKED / REISSUED 会直接抛业务异常（400），而售后排查恰恰要看**失效件**
     * （为何失效、何时被吊销、换机重发到哪条）。本方法**不过滤状态**，返回脱敏管理视图。
     */
    public LicenseResponse getLicenseDetail(String licenseKey) {
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND", "License not found: " + licenseKey));
        return LicenseResponse.adminView(license, resolveEmail(license.getCustomerId()));
    }

    /**
     * E3：按 userId 单查邮箱，供单条 License 视图回填 {@code customerEmail}；查不到（如匿名历史件）返回 null。
     */
    private String resolveEmail(UUID customerId) {
        if (customerId == null) {
            return null;
        }
        return userRepository.findById(customerId).map(User::getEmail).orElse(null);
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
