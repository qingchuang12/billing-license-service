package com.billing.license.service;

import com.billing.license.dto.CheckoutRequest;
import com.billing.license.dto.CheckoutResponse;
import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一收银台服务 - 创建收银台会话、选择支付方式、查询支付状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentService paymentService;
    private final LicenseService licenseService;
    private final RedeemCodeService redeemCodeService;
    private final RateLimitService rateLimitService;

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

        // 构建订单
        Order order = Order.builder()
            .orderNumber(generateOrderNumber())
            .customerId(request.getCustomerId() != null ? request.getCustomerId() : UUID.randomUUID())
            .totalAmount(product.getPrice())
            .currency(domestic ? "CNY" : "USD")
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
            .unitPrice(product.getPrice())
            .totalPrice(product.getPrice())
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

        if (session.getStatus() == CheckoutSession.Status.PAID) {
            if (session.getMachineId() != null && !session.getMachineId().isEmpty()) {
                // 已绑定机器码：直接签发/返回 License
                var licenses = licenseService.issueLicensesForOrder(session.getOrderId());
                if (!licenses.isEmpty()) {
                    resp.license(licenses.get(0).getSignedToken());
                }
            } else {
                // 未绑定机器码：返回兑换码
                String code = redeemCodeService.generateCode(session.getOrderNumber());
                resp.redeemCode(code);
            }
        }
        return resp.build();
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
}
