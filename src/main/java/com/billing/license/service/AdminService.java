package com.billing.license.service;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.*;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
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
    // E1/E3（客户标识邮箱化）：对外邮箱 ↔ 内部 userId 解析，及出参 customerEmail 回填
    private final CustomerIdentityService customerIdentityService;
    private final UserRepository userRepository;
    // D6（plan-7.0）：退款吊销同样要写 license_events 留痕，事件口径复用 LicenseService#recordLicenseEvent
    // （与「管理端作废 / 解绑 / 重发」同一来源，避免各写一套）。LicenseService 不反向依赖本类，无循环。
    private final LicenseService licenseService;
    // 退款编排（渠道 HTTP 在事务外、outbox 补偿）已独立到 RefundService
    private final RefundService refundService;

    // i6：复用 ObjectMapper 构造 metadata JSON，避免手写拼接导致的转义/注入问题
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();

    /**
     * 订单查询（I2 收敛）：支持按状态 / 订单号 / 订单 ID 过滤，三者全部留空即全量列表。
     * 一个端点替代原 {@code listOrders} + {@code listOrdersByStatus} + OrderController 的 by-id/by-number 四个入口。
     *
     * <p>#9：分页 + 状态下推。无过滤时原先 {@code findAll()} 把全表拉进内存再整体序列化，
     * 数据量增长后单次请求即打爆堆；现与 {@code /api/admin/users} 同口径分页
     * （页大小由控制层夹到 1~200、默认 20），按下单时间倒序保证翻页稳定；
     * 状态过滤走 {@code findByStatus} 的 SQL 条件而不是内存过滤。
     * 按订单号 / 订单 ID 精确命中时最多一行，分页参数只影响返回切片的元数据。
     */
    @Transactional(readOnly = true)
    public Page<OrderResponse> listOrders(Order.OrderStatus status, String orderNumber, String orderId,
                                          Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
            Sort.by(Sort.Direction.DESC, "createdAt"));
        if (orderNumber != null && !orderNumber.isBlank()) {
            return singlePage(orderRepository.findByOrderNumber(orderNumber.trim()), sorted,
                orderService::mapToResponse);
        }
        if (orderId != null && !orderId.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(orderId.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_ORDER_ID", "非法的订单 ID: " + orderId);
            }
            return singlePage(orderRepository.findById(id), sorted, orderService::mapToResponse);
        }
        Page<Order> orders = status != null
            ? orderRepository.findByStatus(status, sorted)
            : orderRepository.findAll(sorted);
        return orders.map(orderService::mapToResponse);
    }

    /** #9：把「精确命中至多一行」的查询收敛成与分页查询同形的 Page 返回。 */
    private static <T, R> Page<R> singlePage(java.util.Optional<T> found, Pageable pageable,
                                             java.util.function.Function<T, R> mapper) {
        List<T> content = found.map(List::of).orElse(List.of());
        return new PageImpl<>(content.stream().map(mapper).collect(Collectors.toList()), pageable, content.size());
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
     *
     * <p>#9：分页 + 状态下推。原先无过滤走 {@code findAll()} 全表入内存、有过滤也在内存里按状态筛；
     * 现状态与主体条件一并下推到 SQL，并按签发时间倒序分页（与 {@code /api/admin/users} 同口径）。
     */
    @Transactional(readOnly = true)
    public Page<LicenseResponse> listLicenses(String customerEmail, String orderNumber, String status,
                                              Pageable pageable) {
        License.LicenseStatus target = null;
        if (status != null && !status.isBlank()) {
            try {
                target = License.LicenseStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_LICENSE_STATUS", "非法的 License 状态值: " + status);
            }
        }
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
            Sort.by(Sort.Direction.DESC, "issuedAt"));
        Page<License> page;
        if (orderNumber != null && !orderNumber.isBlank()) {
            Order order = getOrderByNumber(orderNumber.trim());
            page = target == null
                ? licenseRepository.findByOrder(order, sorted)
                : licenseRepository.findByOrderAndStatus(order, target, sorted);
        } else if (customerEmail != null && !customerEmail.isBlank()) {
            // E2：对外邮箱经解析器换成内部 userId 再查；未注册邮箱 → 无匹配（只读解析，不建号）
            UUID customerId = customerIdentityService.resolveExisting(customerEmail).orElse(null);
            if (customerId == null) {
                return Page.empty(sorted);
            }
            page = target == null
                ? licenseRepository.findByCustomerId(customerId, sorted)
                : licenseRepository.findByCustomerIdAndStatus(customerId, target, sorted);
        } else {
            page = target == null
                ? licenseRepository.findAll(sorted)
                : licenseRepository.findByStatus(target, sorted);
        }
        // E3：批量解析本页 customerId → 邮箱，避免逐条查库（N+1）
        List<UUID> customerIds = page.getContent().stream()
            .map(License::getCustomerId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        Map<UUID, String> emailById = customerIds.isEmpty()
            ? Map.of()
            : userRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
        List<LicenseResponse> views = page.getContent().stream()
            .map(l -> LicenseResponse.adminView(l,
                l.getCustomerId() == null ? null : emailById.get(l.getCustomerId())))
            .collect(Collectors.toList());
        return new PageImpl<>(views, sorted, page.getTotalElements());
    }

    /**
     * 退款编排已抽到 {@link RefundService}：渠道 HTTP 在事务外，退款意图/结果落
     * {@code refund_requests} outbox，后台任务负责反查与本地结算补偿。
     */
    public OrderResponse refundOrder(String orderNumber, String reason) {
        return refundService.refundOrder(orderNumber, reason, null);
    }

    public OrderResponse refundOrder(String orderNumber, String reason, BigDecimal refundAmount) {
        return refundService.refundOrder(orderNumber, reason, refundAmount);
    }

    /** 人工退款队列（Creem 全额待审核 / 部分待转账确认）。 */
    public List<com.billing.license.dto.accounting.ManualRefundView> listManualRefunds() {
        return refundService.listManualRefunds();
    }

    public com.billing.license.dto.accounting.ManualRefundView approveRefund(
            UUID requestId, UUID reviewer, String note) {
        return refundService.approveRefund(requestId, reviewer, note);
    }

    public com.billing.license.dto.accounting.ManualRefundView rejectRefund(
            UUID requestId, UUID reviewer, String note) {
        return refundService.rejectRefund(requestId, reviewer, note);
    }

    public OrderResponse confirmManualTransfer(UUID requestId, String transferChannel,
                                               String transferReference, UUID reviewer, String note) {
        return refundService.confirmManualTransfer(requestId, transferChannel, transferReference, reviewer, note);
    }

    /**
     * H-C2：按 License 密钥查询详情（管理端）。
     *
     * <p>补齐管理端查询缺口：公开端点 {@code GET /api/licenses/verify/{licenseKey}} 对
     * EXPIRED / REVOKED / REISSUED 会直接抛业务异常（400），而售后排查恰恰要看**失效件**
     * （为何失效、何时被吊销、换机重发到哪条）。本方法**不过滤状态**，返回脱敏管理视图。
     */
    @Transactional(readOnly = true)
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
