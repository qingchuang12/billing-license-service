package com.billing.license.service;

import com.billing.license.dto.CheckoutRequest;
import com.billing.license.dto.CheckoutResponse;
import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Payment;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 统一收银台服务 - 创建收银台会话、选择支付方式、查询支付状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    // R3：主动对账补偿冷却窗口（秒），避免客户端高频轮询每次都打外部支付渠道（放大/配额消耗）
    private static final long COMPENSATION_COOLDOWN_SECONDS = 30;

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentService paymentService;
    private final LicenseService licenseService;
    private final RedeemCodeService redeemCodeService;
    private final LicenseRepository licenseRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final PaymentRepository paymentRepository;
    private final RateLimitService rateLimitService;

    // R5：单实例内按订单号串行化发放，避免并发轮询重复签发 License/兑换码（资损）
    private ConcurrentHashMap<String, Object> fulfillmentLocks = new ConcurrentHashMap<>();

    /**
     * 创建收银台会话
     * 根据币种/语言自动判断国内或国际区域，返回可选支付方式列表；
     * 同时创建订单与收银台会话。
     */
    @Transactional
    public CheckoutResponse createCheckout(CheckoutRequest request) {
        log.info("创建收银台：product={}, currency={}, locale={}", request.getProductId(), request.getCurrency(), request.getLocale());

        Product product = productRepository.findBySku(request.getProductId())
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + request.getProductId()));
        if (!product.getActive()) {
            throw new BusinessException("PRODUCT_INACTIVE", "Product is inactive: " + request.getProductId());
        }

        // 风控：同一邮箱大量购买频控（架构十七）
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            try {
                rateLimitService.checkEmailPurchase(request.getEmail());
            } catch (RateLimitService.RateLimitExceededException e) {
                throw new BusinessException("EMAIL_PURCHASE_LIMIT", "同一邮箱购买过于频繁，请稍后再试");
            }
        }

        // 区域判定：CNY 或 zh-CN 视为国内，否则国际
        boolean domestic = isDomestic(request.getCurrency(), request.getLocale());
        List<PaymentMethod> methods = domestic
            ? paymentServiceFactory.getDomesticMethods()
            : paymentServiceFactory.getInternationalMethods();

        if (methods.isEmpty()) {
            throw new BusinessException("NO_PAYMENT_METHOD", "No available payment method for region");
        }

        // 双币种定价（B19）：金额与币种随区域取用，国内走 CNY/priceCny，国际走 USD/priceUsd
        BigDecimal orderAmount = product.getPriceForRegion(domestic);
        String orderCurrency = product.getCurrencyForRegion(domestic);

        // 构建订单
        Order order = Order.builder()
            .orderNumber(generateOrderNumber())
            .customerId(request.getCustomerId() != null ? request.getCustomerId() : UUID.randomUUID())
            .totalAmount(orderAmount)
            .currency(orderCurrency)
            .status(Order.OrderStatus.PENDING)
            .paymentStatus(Order.PaymentStatus.UNPAID)
            .title(product.getName())
            .description(product.getDescription())
            .machineCode(request.getMachineId())
            .email(request.getEmail())
            .build();
        List<OrderItem> items = new ArrayList<>();
        items.add(OrderItem.builder()
            .order(order)
            .product(product)
            .quantity(1)
            .unitPrice(orderAmount)
            .totalPrice(orderAmount)
            .build());
        order.setOrderItems(items);
        order = orderRepository.save(order);

        // 构建收银台会话
        String checkoutId = "chk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        CheckoutSession session = CheckoutSession.builder()
            .checkoutId(checkoutId)
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .status(CheckoutSession.Status.CREATED)
            .currency(order.getCurrency())
            .amount(order.getTotalAmount())
            .locale(request.getLocale())
            .country(domestic ? "CN" : "GLOBAL")
            .machineId(request.getMachineId())
            .email(request.getEmail())
            .returnUrl(request.getReturnUrl())
            .cancelUrl(request.getCancelUrl())
            .expiresAt(LocalDateTime.now().plusHours(2))
            .build();
        checkoutSessionRepository.save(session);

        return CheckoutResponse.builder()
            .checkoutId(checkoutId)
            .orderNumber(order.getOrderNumber())
            .status(session.getStatus().name())
            .paymentMethods(methods.stream().map(Enum::name).collect(Collectors.toList()))
            .payUrl("")  // 选择支付方式后再生成
            .expiresAt(session.getExpiresAt() != null ? session.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null)
            .build();
    }

    /**
     * 选择支付方式并创建真实支付会话（返回二维码或跳转链接）
     */
    @Transactional
    public CheckoutResponse selectProvider(String checkoutId, SelectProviderRequest request) {
        log.info("选择支付方式：checkoutId={}, provider={}", checkoutId, request.getProvider());

        CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId)
            .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));

        Order order = orderRepository.findById(session.getOrderId())
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + session.getOrderId()));

        PaymentMethod method = resolveMethod(request.getProvider());
        PaymentResponse paymentResponse = paymentService.createPayment(order, method);

        // H5 修复：落库订单所选支付渠道，供管理端退款 resolveMethod(order.getPaymentProvider()) 使用。
        // 全工程此前无任何 setPaymentProvider 调用点 → 退款恒走 REFUND_FAILED。
        // 仅当渠道支付创建成功（未抛 PAYMENT_CREATE_FAILED）后才落库，避免脏状态。
        order.setPaymentProvider(method.name());
        orderRepository.save(order);

        // H6：支付创建失败（渠道未配置/网络异常/参数错误等）必须向前端显式反馈，
        // 禁止静默返回空 payUrl 让前端陷入"已下单却无支付入口"的困惑状态。
        if (PaymentStatus.FAILED.name().equals(paymentResponse.getStatus())) {
            String err = (paymentResponse.getErrorMessage() != null && !paymentResponse.getErrorMessage().isEmpty())
                    ? paymentResponse.getErrorMessage()
                    : "支付渠道创建失败，请稍后重试或联系支持";
            throw new BusinessException("PAYMENT_CREATE_FAILED", err);
        }

        // 更新收银台会话
        session.setProvider(method.name());
        session.setStatus(CheckoutSession.Status.PENDING);
        session.setProviderSessionId(paymentResponse.getPaymentId());
        if (paymentResponse.getPayUrl() != null) session.setPayUrl(paymentResponse.getPayUrl());
        if (paymentResponse.getRedirectUrl() != null && session.getPayUrl() == null) session.setPayUrl(paymentResponse.getRedirectUrl());
        checkoutSessionRepository.save(session);

        CheckoutResponse.CheckoutResponseBuilder resp = CheckoutResponse.builder()
            .checkoutId(checkoutId)
            .orderNumber(session.getOrderNumber())
            .status(session.getStatus().name())
            .provider(method.name());

        if (paymentResponse.getPayUrl() != null) {
            resp.payUrl(paymentResponse.getPayUrl());
            resp.paymentMode("redirect");
        } else if (paymentResponse.getRedirectUrl() != null) {
            resp.redirectUrl(paymentResponse.getRedirectUrl());
            resp.paymentMode("redirect");
        } else if (paymentResponse.getQrCode() != null) {
            resp.qrcode(paymentResponse.getQrCode());
            resp.paymentMode("qrcode");
        }
        if (session.getExpiresAt() != null) {
            resp.expiresAt(session.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        return resp.build();
    }

    /**
     * 查询收银台/订单状态；若已支付且已发货，直接返回 License 或兑换码
     */
    @Transactional
    public CheckoutResponse getStatus(String checkoutId) {
        log.info("查询收银台状态：checkoutId={}", checkoutId);

        CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId)
            .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));

        CheckoutResponse.CheckoutResponseBuilder resp = CheckoutResponse.builder()
            .checkoutId(checkoutId)
            .orderNumber(session.getOrderNumber())
            .status(session.getStatus().name());

        // w17：主动对账补偿——Webhook 丢失/延迟时，客户端轮询仍卡在 CREATED/PENDING。
        // 若会话未过期，主动查一次渠道支付状态；成功则置 PAID（复用 fulfillOrder 等价逻辑），
        // 由订单状态机（canFulfill/markPaid）兜底幂等，避免重复发货。
        // R3：冷却窗口内跳过补偿，避免每次轮询都打外部渠道 API（放大/配额消耗向量）。
        if ((session.getStatus() == CheckoutSession.Status.CREATED
                || session.getStatus() == CheckoutSession.Status.PENDING)
                && (session.getExpiresAt() == null
                    || session.getExpiresAt().isAfter(java.time.LocalDateTime.now()))) {
            boolean withinCooldown = session.getLastCompensatedAt() != null
                && session.getLastCompensatedAt()
                    .plusSeconds(COMPENSATION_COOLDOWN_SECONDS)
                    .isAfter(java.time.LocalDateTime.now());
            if (!withinCooldown) {
                compensateFromChannel(session);
                // 记录补偿时间并落地，供下次轮询冷却判断
                session.setLastCompensatedAt(java.time.LocalDateTime.now());
                checkoutSessionRepository.save(session);
            }
            // 重新读取最新状态（补偿可能已置 PAID）
            session = checkoutSessionRepository.findByCheckoutId(checkoutId)
                .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));
            resp.status(session.getStatus().name());
        }

        if (session.getStatus() == CheckoutSession.Status.PAID) {
            // R5：发放幂等——单实例内按订单号串行化，且优先返回已签发记录，
            // 避免并发轮询重复签发 License 或兑换码（资损）。
            // Webhook 侧发放由 R1（原子幂等）+ 订单状态机（canFulfill/markPaid）兜底；
            // 多实例部署建议额外部署分布式锁（见修复说明）。
            synchronized (orderLock(session.getOrderNumber())) {
                if (session.getMachineId() != null && !session.getMachineId().isEmpty()) {
                    // 已绑定机器码：优先返回已签发的 License，避免轮询重复签发（B13）
                    var existing = licenseRepository.findByOrderId(session.getOrderId());
                    if (!existing.isEmpty()) {
                        resp.license(existing.get(0).getSignedToken());
                    } else {
                        var licenses = licenseService.issueLicensesForOrder(session.getOrderId());
                        if (!licenses.isEmpty()) {
                            resp.license(licenses.get(0).getSignedToken());
                        }
                    }
                } else {
                    // 未绑定机器码：优先返回已生成的兑换码，避免轮询重复签发（B13）
                    var existing = redeemCodeRepository.findByOrderId(session.getOrderNumber());
                    if (!existing.isEmpty()) {
                        resp.redeemCode(existing.get(0).getCode());
                    } else {
                        String code = redeemCodeService.generateCode(session.getOrderNumber());
                        resp.redeemCode(code);
                    }
                }
            }
        }
        return resp.build();
    }

    // R5：获取订单级发放锁（单实例串行化，避免并发轮询重复签发）
    private Object orderLock(String orderNumber) {
        return fulfillmentLocks.computeIfAbsent(orderNumber, k -> new Object());
    }

    private PaymentMethod resolveMethod(String provider) {
        if (provider == null) throw new BusinessException("PROVIDER_REQUIRED", "provider is required");
        try {
            return PaymentMethod.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("UNSUPPORTED_PROVIDER", "Unsupported provider: " + provider);
        }
    }

    /**
     * w17：主动对账补偿。仅当本地会话仍为 CREATED/PENDING 时调用一次：
     * 按订单查已落库的 Payment，解析渠道后调其 queryPaymentStatus 主动查支付状态；
     * 渠道确认为 SUCCESS 则标记会话 PAID（发货由已有 Webhook/fulfillOrder 幂等路径兜底）。
     */
    private void compensateFromChannel(CheckoutSession session) {
        try {
            Payment payment = paymentRepository.findByOrderIdStr(session.getOrderId().toString()).orElse(null);
            if (payment == null || payment.getMethod() == null) {
                return;
            }
            PaymentMethod method = resolveMethod(payment.getMethod());
            PaymentStatus status = paymentService.queryPaymentStatus(payment.getPaymentId(), method);
            if (PaymentStatus.SUCCESS == status) {
                log.info("主动对账补偿：渠道确认已支付，标记会话 PAID：checkoutId={}", session.getCheckoutId());
                session.setStatus(CheckoutSession.Status.PAID);
                checkoutSessionRepository.save(session);
            }
        } catch (Exception e) {
            // 补偿失败不应影响轮询返回；记录日志，等待 Webhook 或下次轮询重试
            log.warn("主动对账补偿失败（非致命）：checkoutId={}", session.getCheckoutId(), e);
        }
    }

    /**
     * 标记收银台为已支付（由 Webhook 发货成功后调用）
     */
    @Transactional
    public void markPaid(String orderNumber) {
        checkoutSessionRepository.findByOrderNumber(orderNumber).ifPresent(session -> {
            session.setStatus(CheckoutSession.Status.PAID);
            checkoutSessionRepository.save(session);
        });
    }

    private boolean isDomestic(String currency, String locale) {
        if (currency != null && currency.equalsIgnoreCase("CNY")) return true;
        if (locale != null && locale.toLowerCase().startsWith("zh")) return true;
        return false;
    }

    private String generateOrderNumber() {
        return "ORD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * M2：定时清理过期且未支付（PAID 保留用于对账）的收银台会话，避免记录无限增长。
     * 默认每小时执行一次（fixedDelay，上次完成后间隔）；可通过
     * billing.checkout-session.cleanup-interval-ms 调整。
     */
    @Scheduled(fixedDelayString = "${billing.checkout-session.cleanup-interval-ms:3600000}")
    public void cleanupExpiredSessions() {
        long deleted = checkoutSessionRepository.deleteByStatusNotAndExpiresAtBefore(
                CheckoutSession.Status.PAID, LocalDateTime.now());
        if (deleted > 0) {
            log.info("清理过期收银台会话 {} 条", deleted);
        }
    }
}
