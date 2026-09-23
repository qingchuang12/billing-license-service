package com.billing.license.service;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.*;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    // D6（plan-7.0）：退款吊销同样要写 license_events 留痕，事件口径复用 LicenseService#recordLicenseEvent
    // （与「管理端作废 / 解绑 / 重发」同一来源，避免各写一套）。LicenseService 不反向依赖本类，无循环。
    private final LicenseService licenseService;

    // i6：复用 ObjectMapper 构造 metadata JSON，避免手写拼接导致的转义/注入问题
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    // 自注入代理：使同类内 persistRefundFailed 的 @Transactional(REQUIRES_NEW) 经 AOP 代理生效
    // （裸 this 自调用不走代理，REQUIRES_NEW 会失效）。非 final，不进 @RequiredArgsConstructor 构造函数。
    @Autowired
    @Lazy
    private AdminService self;

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
     * 全额退款（管理端既有入口）：委托 {@link #refundOrder(String, String, BigDecimal)} 并传 null 金额，
     * 行为与 plan-4.1 引入部分退款之前完全一致。
     */
    public OrderResponse refundOrder(String orderNumber, String reason) {
        return refundOrder(orderNumber, reason, null);
    }

    /**
     * 退款订单：调用支付服务商退款接口，标记订单退款态，作废全部 License，发送通知。
     *
     * H4 资损防护：仅当支付渠道侧退款成功（refundPayment 返回 true）才标记退款并作废 License；
     * 若渠道未实现/返回失败/抛异常，绝不本地谎报退款成功——改为标记 REFUND_FAILED（保留 PAID），
     * 并抛出业务异常交由运营到渠道控制台手动退款，避免「账显示已退但钱没退」的资损与对账混乱。
     *
     * <p>plan-4.1 增加金额入参以支持用户端「按使用时间折算」的部分退款：金额在
     * <b>渠道调用</b>与<b>退款流水</b>两处贯通；部分退款成功后置
     * {@link Order#markPartiallyRefunded()}（{@code paymentStatus = PARTIALLY_REFUNDED}）。
     *
     * <p><b>降级</b>：退款额小于实付额而渠道未受理时，自动降级为全额退并留痕
     * metadata {@code refundDegraded}，避免用户因单一渠道不支持部分退款而完全无法自助退款。
     *
     * @param refundAmount 退款金额；<b>null 表示全额</b>（管理端口径）；达到或超过实付额一律按全额处理
     */
    @Transactional
    public OrderResponse refundOrder(String orderNumber, String reason, BigDecimal refundAmount) {
        log.info("发起退款：orderNumber={}, reason={}, refundAmount={}", orderNumber, reason, refundAmount);

        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderNumber));

        // 一单只退一次：已部分退款的订单不得再退剩余（防止反复退款侵蚀）
        if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                || order.getPaymentStatus() == Order.PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException("ALREADY_REFUNDED", "Order already refunded: " + orderNumber);
        }

        BigDecimal total = order.getTotalAmount();
        BigDecimal requested = refundAmount != null ? refundAmount : total;
        if (requested == null || requested.signum() <= 0) {
            throw new BusinessException("INVALID_REFUND_AMOUNT", "非法退款金额: " + refundAmount);
        }
        // 不信任调用方金额：达到或超过实付额一律按全额处理
        boolean fullRefund = total == null || requested.compareTo(total) >= 0;
        BigDecimal channelAmount = fullRefund ? total : requested;

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
        BigDecimal refundedAmount = channelAmount;
        boolean degraded = false;
        if (method != null) {
            try {
                channelRefunded = paymentServiceFactory.getStrategy(method)
                    .refundPayment(order, channelPaymentId, channelAmount);
            } catch (Exception e) {
                log.error("调用支付渠道退款异常：provider={}, orderNumber={}", method, orderNumber, e);
                channelRefunded = false;
            }
            // plan-4.1 降级（拍板）：渠道未受理部分金额时自动改发全额退，避免用户因单一渠道
            // 不支持部分退款而完全无法自助退款。失败与降级均留痕，便于对账追溯。
            if (!channelRefunded && !fullRefund) {
                log.warn("渠道未受理部分退款，自动降级为全额退：provider={}, orderNumber={}, partialAmount={}",
                    method, orderNumber, channelAmount);
                try {
                    channelRefunded = paymentServiceFactory.getStrategy(method)
                        .refundPayment(order, channelPaymentId, total);
                    if (channelRefunded) {
                        fullRefund = true;
                        degraded = true;
                        refundedAmount = total;
                    }
                } catch (Exception e) {
                    log.error("降级全额退款异常：provider={}, orderNumber={}", method, orderNumber, e);
                }
            }
        } else {
            log.warn("无法解析支付渠道，无法发起渠道侧退款：provider={}, orderNumber={}",
                order.getPaymentProvider(), orderNumber);
        }

        if (!channelRefunded) {
            // 部分退款尝试失败：把尝试金额写入 metadata，便于对账追溯与人工复核
            if (!fullRefund) {
                order.setMetadata(appendMetadata(order.getMetadata(), "refundAttemptedAmount",
                    channelAmount.toPlainString()));
            }
            // C8：退款失败态必须在独立事务（REQUIRES_NEW）中落库，否则随外层 @Transactional 回滚，
            // 导致 DB 永远 PAID、运营看不到待处理清单。内层提交后外层再抛异常回滚不影响已落库的失败态。
            try {
                self.persistRefundFailed(order, reason);
            } catch (Exception ex) {
                log.error("标记退款失败态异常（内层事务）：orderNumber={}", orderNumber, ex);
            }
            throw new BusinessException("REFUND_FAILED",
                "支付渠道退款未成功（订单保持已支付）。请到支付渠道控制台手动退款，或确认渠道配置后重试。orderNumber=" + orderNumber);
        }

        // 渠道退款成功：作废该订单下所有 License
        // D6（plan-7.0，2026-09-23）：退款吊销**同样**必须写 license_events 留痕——此前只置状态 + save，
        // 与管理端作废是同一个缺陷（流水账查不到作废历史，售后排查会误判「该件从未被作废」）。
        // 事件口径复用 LicenseService#recordLicenseEvent，与管理端作废 / 解绑 / 重发同一来源。
        List<License> licenses = licenseRepository.findByOrder(order);
        for (License license : licenses) {
            if (license.getStatus() != License.LicenseStatus.REVOKED) {
                String boundMachineCode = license.getMachineCode();
                license.setStatus(License.LicenseStatus.REVOKED);
                license.setRevokedAt(LocalDateTime.now());
                licenseRepository.save(license);
                licenseService.recordLicenseEvent(license, LicenseEvent.EventType.REVOKED, boundMachineCode,
                    "Revoked by refund. order=" + orderNumber);
            }
        }

        // H15：通过状态机唯一出口标记退款态——全额 → REFUNDED；部分 → PARTIALLY_REFUNDED（status 保持 PAID）
        if (fullRefund) {
            order.markRefunded();
        } else {
            order.markPartiallyRefunded();
        }
        order.setMetadata(appendMetadata(order.getMetadata(), "refundReason", reason));
        order.setMetadata(appendMetadata(order.getMetadata(), "refundAmount", refundedAmount.toPlainString()));
        if (degraded) {
            order.setMetadata(appendMetadata(order.getMetadata(), "refundDegraded",
                "渠道未受理部分退款，已按全额退（原折算额 " + channelAmount.toPlainString() + "）"));
        }
        orderRepository.save(order);

        // 退款流水：渠道退款成功后写入一条 REFUNDED 支付记录，使交易流水（Payment 表）
        // 完整反映资金变动（此前退款只改订单状态、流水缺退款行）。与状态标记同事务提交，
        // 保证「状态已退」与「流水有退款行」原子一致；同批失败则整体回滚、订单回到 PAID 供重试。
        // 金额口径：部分退款记折算额，降级/全额记实付额，故流水金额与实际退还资金恒等。
        writeRefundPayment(order, method, channelPaymentId, refundedAmount);

        if (order.getEmail() != null && !order.getEmail().isEmpty()) {
            // M5 修正：退款通知使用退款专用文案，不再复用「支付失败」模板
            // plan-4.1：文案带上实际退款金额（部分退款时用户需知道退了多少钱）
            String currency = order.getCurrency() != null ? " " + order.getCurrency().code() : "";
            emailNotificationService.sendRefundProcessedEmail(
                order.getEmail(), orderNumber,
                "退款已处理：" + refundedAmount.toPlainString() + currency
                    + (reason != null && !reason.isBlank() ? "（" + reason + "）" : ""));
        }

        log.info("退款完成：orderNumber={}", orderNumber);
        return orderService.mapToResponse(order);
    }

    /**
     * 写入一条 REFUNDED 支付流水（与订单同币种）。
     *
     * <p>{@code payment_id} 唯一非空，不能复用原支付记录，故用 {@code REFUND-<orderId>-<时间戳>} 独立标识；
     * {@code transactionId} 关联发起退款时使用的渠道交易号（{@code channelPaymentId}）以便追溯。
     * {@code channel} 与 {@code method} 同源写入，对齐 {@code AccountingService.toView} 的展示口径。
     *
     * @param amount 实际退还金额（plan-4.1：部分退款记折算额，降级/全额记实付额）
     */
    private void writeRefundPayment(Order order, PaymentMethod method, String channelPaymentId, BigDecimal amount) {
        Payment refund = Payment.builder()
                .orderIdStr(order.getId().toString())
                .paymentId("REFUND-" + order.getId() + "-" + System.currentTimeMillis())
                .transactionId(channelPaymentId)
                .amount(amount)
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
