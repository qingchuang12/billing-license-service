package com.billing.license.service;

import com.billing.license.dto.CheckoutRequest;
import com.billing.license.dto.CheckoutResponse;
import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.*;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentCloseResult;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentLinkContext;
import com.billing.license.service.payment.strategy.PaymentProduct;
import com.billing.license.service.payment.strategy.PaymentQueryResult;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.util.AmountValidator;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
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
    static final long COMPENSATION_COOLDOWN_SECONDS = 30;

    /** #50：清理无支付关联的过期会话，每批大小与最大批次数（防止单次任务无限占用调度线程）。 */
    private static final int CLEANUP_BATCH_SIZE = 500;
    private static final int CLEANUP_MAX_BATCHES = 100;

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentService paymentService;
    private final com.billing.license.service.fulfillment.FulfillmentService fulfillmentService;
    private final LicenseRepository licenseRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final RateLimitService rateLimitService;
    // E1（客户标识邮箱化）：对外邮箱 → 内部 userId 解析；未注册自动建访客账户
    private final CustomerIdentityService customerIdentityService;
    // 主动查单补偿的金额/币种核对（与 Webhook 同口径）
    private final AmountValidator amountValidator;

    /** 部署基址：用于校验产品级回跳地址，禁止任意外域跳转。 */
    @org.springframework.beans.factory.annotation.Value("${app.base-url:http://localhost:8000}")
    private String appBaseUrl;

    // #5：自注入代理，使同类内 applyCompensatedPayment 的 @Transactional 经 AOP 代理生效
    // （裸 this 自调用不走代理）。非 final，不进 @RequiredArgsConstructor 构造函数。
    @Autowired
    @Lazy
    private CheckoutService self;

    // R5：单实例内按订单号串行化发放，避免并发轮询重复签发 License/兑换码（资损）
    // F8：锁条目引用计数，最后一个持有者退出即摘除——只增不减会让每个被轮询过的订单号永久占位。
    private final ConcurrentHashMap<String, OrderLock> fulfillmentLocks = new ConcurrentHashMap<>();

    // #19：按 checkoutId 串行化「选择支付渠道」整段（判 + 调渠道 + 写库），
    // 并发连点/前端重试时后到者进入即命中 B4 幂等分支，不会各自创建一个真实收银台。
    private final ConcurrentHashMap<String, OrderLock> checkoutLocks = new ConcurrentHashMap<>();

    /** 引用计数锁：{@code monitor} 是同步对象，{@code refs} 只在对应锁表的 {@code compute} 内改动（按 key 原子）。 */
    private static final class OrderLock {
        private final Object monitor = new Object();
        private int refs;
    }

    /**
     * 创建收银台会话
     * 根据币种/语言自动判断国内或国际区域，返回可选支付方式列表；
     * 同时创建订单与收银台会话。
     *
     * <p>#16（承 #5：渠道外部调用一律在事务之外）：本方法**不开事务**——一步下单（请求带 provider）
     * 末尾会直接走 {@link #selectProvider}，那是一条真实的渠道下单 HTTP；原先本方法的
     * {@code @Transactional} 把「建单 + 渠道调用」圈进同一事务，一次请求全程占住一根 DB 连接。
     * 建单与建会话收敛进 {@link #persistOrderAndSession} 短事务，渠道调用发生在其之外。
     *
     * <p>有意为之的一处语义变化：一步下单且渠道创建失败时，订单与收银台会话**保留**（改造前随外层事务
     * 一并回滚），与两步下单一致——前端可拿同一 checkoutId 续选其它渠道；本次失败仍按 H6 抛
     * {@code PAYMENT_CREATE_FAILED}。
     */
    public CheckoutResponse createCheckout(CheckoutRequest request) {
        log.info("创建收银台：product={}, currency={}, locale={}", request.getProductId(), request.getCurrency(), request.getLocale());

        Product product = productRepository.findBySku(request.getProductId())
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + request.getProductId()));
        if (!product.getActive()) {
            throw new BusinessException("PRODUCT_INACTIVE", "Product is inactive: " + request.getProductId());
        }
        // 套餐只有 CNY/USD 两档定价：其它币种会被静默换成 USD 结算，必须在入口拒绝
        if (request.getCurrency() != null
                && request.getCurrency() != Currency.CNY
                && request.getCurrency() != Currency.USD) {
            throw new BusinessException("UNSUPPORTED_CURRENCY",
                "Unsupported currency: " + request.getCurrency().code());
        }

        // 风控：同一邮箱大量购买频控（架构十七）
        if (request.getCustomerEmail() != null && !request.getCustomerEmail().isEmpty()) {
            try {
                rateLimitService.checkEmailPurchase(request.getCustomerEmail());
            } catch (RateLimitService.RateLimitExceededException e) {
                throw new BusinessException("EMAIL_PURCHASE_LIMIT", "同一邮箱购买过于频繁，请稍后再试");
            }
        }

        // #8（未认证资源放大）：本端点匿名可调，且每次下单都会 resolveOrCreate 客户账户（未注册即建号）
        // + 落订单与会话行。仅邮箱维度限流时，轮换邮箱即可无限建账户、无限写行；故补 IP 维度，
        // 且置于任何建号/写库之前——限流命中时不产生任何副作用。IP 由 Controller 服务端解析后写入。
        try {
            rateLimitService.checkCheckoutIp(request.getClientIp());
        } catch (RateLimitService.RateLimitExceededException e) {
            throw new BusinessException("CHECKOUT_IP_LIMIT", "下单过于频繁，请稍后再试");
        }

        // 区域/渠道判定只看**结算币种**：zh 语言 + USD 仍属国际订单，不能把 USD 交给支付宝。
        // 缺币种时才按 locale 回落（兼容旧调用方）。
        boolean domestic = request.getCurrency() != null
            ? request.getCurrency() == Currency.CNY
            : isDomestic(null, request.getLocale());
        // 渠道能力矩阵：只保留「支持该套餐计费周期」的已启用渠道
        // （国内支付宝/微信只支持一次性买断；订阅周期仅 Stripe / Paddle）
        List<PaymentMethod> methods = paymentServiceFactory.getEnabledMethods(domestic, product.getChargeMode());

        if (methods.isEmpty()) {
            throw new BusinessException("NO_PAYMENT_METHOD",
                "当前套餐（" + product.getBillingCycle().name() + "）在该区域没有可用的支付方式");
        }

        // 双币种定价（B19）：金额与币种随区域取用，国内走 CNY/priceCny，国际走 USD/priceUsd
        BigDecimal orderAmount = product.getPriceForRegion(domestic);
        Currency orderCurrency = product.getCurrencyForRegion(domestic);

        // E4（客户标识邮箱化）：不再匿名随机 UUID 兜底——对外邮箱解析为内部 userId，
        // 未注册邮箱自动建访客账户（方案 A：邮箱对外、UUID 内部）。
        CheckoutSession session = self.persistOrderAndSession(request, domestic, orderAmount, orderCurrency);
        String checkoutId = session.getCheckoutId();

        // I7（2026-09-14）一步下单：请求带 provider 时直接创建支付并返回二维码/跳转链接，
        // 免去前端「create → select-provider」两次往返；不带则维持两步（先返回可用支付方式列表）。
        // #16：建单事务已提交，这里的渠道调用不再被任何事务包着。
        if (request.getProvider() != null && !request.getProvider().isBlank()) {
            SelectProviderRequest selectRequest = new SelectProviderRequest();
            selectRequest.setProvider(request.getProvider().trim());
            selectRequest.setPaymentProduct(request.getPaymentProduct());
            selectRequest.setInstallmentNum(request.getInstallmentNum());
            CheckoutResponse paid = selectProvider(checkoutId, selectRequest);
            paid.setPaymentMethods(methods.stream().map(Enum::name).collect(Collectors.toList()));
            return paid;
        }

        return CheckoutResponse.builder()
            .checkoutId(checkoutId)
            .orderNumber(session.getOrderNumber())
            .status(session.getStatus().name())
            .paymentMethods(methods.stream().map(Enum::name).collect(Collectors.toList()))
            .payUrl("")  // 未传 provider：选择支付方式后再生成
            .expiresAt(session.getExpiresAt() != null ? session.getExpiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() : null)
            .build();
    }

    /**
     * #16：建单 + 建会话的短事务（客户标识解析与访客建号也在其中），与渠道外部调用解耦。
     */
    @Transactional
    public CheckoutSession persistOrderAndSession(CheckoutRequest request, boolean domestic,
                                                  BigDecimal orderAmount, Currency orderCurrency) {
        Product product = productRepository.findBySku(request.getProductId())
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + request.getProductId()));

        Order order = Order.builder()
            .orderNumber(generateOrderNumber())
            .customerId(customerIdentityService.resolveOrCreate(request.getCustomerEmail()))
            .totalAmount(orderAmount)
            .currency(orderCurrency)
            .status(Order.OrderStatus.PENDING)
            .paymentStatus(Order.PaymentStatus.UNPAID)
            .title(product.getName())
            .description(product.getDescription())
            .machineCode(request.getMachineId())
            .email(request.getCustomerEmail())
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
            .email(request.getCustomerEmail())
            // 产品级回跳只接受站内相对路径或与部署基址同源的绝对地址，阻止开放跳转
            .returnUrl(sanitizeReturnUrl(request.getReturnUrl()))
            .cancelUrl(sanitizeReturnUrl(request.getCancelUrl()))
            .expiresAt(LocalDateTime.now().plusHours(2))
            .build();
        checkoutSessionRepository.save(session);
        return session;
    }

    /**
     * 选择支付方式并创建真实支付会话（返回二维码或跳转链接）
     *
     * <p>#16（承 #5：渠道外部调用一律在事务之外）：本方法**不开事务**。中间那步
     * {@link com.billing.license.service.payment.PaymentService#requestPayment} 是真实的渠道下单 HTTP，
     * 原先整段包在 {@code @Transactional} 内，一次选渠道会在等渠道响应期间占住一根 DB 连接
     * （Hikari 默认池 10）；一步下单（{@code createCheckout} 带 provider）更是把建单与渠道调用圈进同一事务。
     * 现拆三段：事务外只读会话/订单 → 事务外调渠道 → 写库收敛进 {@link #recordPaymentChoice} 短事务。
     *
     * <p>旧语义逐条保持不变：
     * <ul>
     *   <li>B4 幂等前置：会话已支付、或同渠道已建过支付入口 → 复用既有入口，绝不再建一个真实收银台；</li>
     *   <li>H6 失败显式反馈：渠道返回 FAILED 时在**任何写库之前**抛 {@code PAYMENT_CREATE_FAILED}，
     *       故订单渠道、会话与 Payment 行均不落地（改造前由外层事务回滚达成，现由抛出时机达成）；</li>
     *   <li>H5：仅成功时把所选渠道写进订单，供管理端退款定位渠道。</li>
     * </ul>
     */
    public CheckoutResponse selectProvider(String checkoutId, SelectProviderRequest request) {
        log.info("选择支付方式：checkoutId={}, provider={}", checkoutId, request.getProvider());

        PaymentMethod method = resolveMethod(request.getProvider());

        // #19：同一 checkoutId 的「判 + 调渠道 + 写库」整段串行化。
        // 未加锁时两个并发请求都能读到 CREATED 而各建一个真实收银台，用户若两个都付即双收；
        // 锁在外部渠道调用期间保持持有（同 checkoutId 串行，不同 checkoutId 互不影响）。
        OrderLock lock = acquireLock(checkoutLocks, checkoutId);
        try {
            synchronized (lock.monitor) {
                CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId)
                    .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));

                UUID orderId = session.getOrderId();
                // #12：本方法不开事务（渠道 HTTP 必须在事务外），故取订单时就预加载行项与其产品：
                // 下方 resolveBillingCycle 与支付策略的 resolveRecurringProduct 都要读它们。
                Order order = orderRepository.findWithItemsById(orderId)
                    .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId));

                // 过期会话不得继续创建真实支付入口（页面不轮询后靠后台补偿，见 #50）
                if (session.getStatus() != CheckoutSession.Status.PAID
                        && session.getExpiresAt() != null
                        && session.getExpiresAt().isBefore(LocalDateTime.now())) {
                    throw new BusinessException("CHECKOUT_EXPIRED", "收银台会话已过期，请重新下单");
                }
                // 已退款订单不允许再拉起支付
                if (order.getPaymentStatus() == Order.PaymentStatus.REFUNDED
                        || order.getPaymentStatus() == Order.PaymentStatus.PARTIALLY_REFUNDED
                        || order.getStatus() == Order.OrderStatus.REFUNDED) {
                    throw new BusinessException("ORDER_REFUNDED", "订单已退款，不能再次支付");
                }

                // 渠道能力校验：收款方式 + 结算币种 + 渠道商品映射必须同时满足
                Product skuProduct = order.getOrderItems() == null ? null
                    : order.getOrderItems().stream().findFirst().map(OrderItem::getProduct).orElse(null);
                if (skuProduct != null && !method.supportsChargeMode(skuProduct.getChargeMode())) {
                    throw new BusinessException("PROVIDER_NOT_SUPPORTED_FOR_PLAN",
                        "支付渠道 " + method.name() + " 不支持该套餐的收款方式："
                            + skuProduct.getChargeMode());
                }
                if (!method.supportsCurrency(order.getCurrency())) {
                    throw new BusinessException("PROVIDER_CURRENCY_MISMATCH",
                        "支付渠道 " + method.name() + " 不支持订单币种 "
                            + (order.getCurrency() != null ? order.getCurrency().code() : "null"));
                }
                if (!mappingReady(method, skuProduct)) {
                    throw new BusinessException("PROVIDER_PRODUCT_MAPPING_MISSING",
                        "套餐未配置 " + method.name() + " 的渠道商品/价目 ID");
                }

                // B4（双重扣款修复）：selectProvider 幂等前置校验，避免用户连点/重复提交生成第二个真实收银台。
                // 1) 会话已支付：直接返回状态，绝不再创建支付。
                if (session.getStatus() == CheckoutSession.Status.PAID) {
                    log.info("会话已支付，忽略重复选择支付方式：checkoutId={}", checkoutId);
                    return buildResumeResponse(session, session.getProvider() != null ? session.getProvider() : method);
                }
                // 2) 同一渠道且已创建过支付入口（PENDING + 已有 providerSessionId）：复用既有支付，不重复创建。
                if (session.getStatus() == CheckoutSession.Status.PENDING
                        && method == session.getProvider()
                        && session.getProviderSessionId() != null && !session.getProviderSessionId().isEmpty()) {
                    log.info("同渠道重复选择，复用既有支付入口（幂等）：checkoutId={}, provider={}", checkoutId, method);
                    return buildResumeResponse(session, method);
                }
                // 3) 其余情况（首次选择 / 切换到不同渠道）走正常创建流程。

                PaymentProduct presentation = resolvePaymentProduct(method, request.getPaymentProduct());
                PaymentLinkContext linkContext = new PaymentLinkContext(
                    session.getReturnUrl(), session.getCancelUrl(), presentation,
                    request.getInstallmentNum());
                PaymentResponse paymentResponse = paymentService.requestPayment(order, method, linkContext);

                // H6：支付创建失败（渠道未配置/网络异常/参数错误等）必须向前端显式反馈，
                // 禁止静默返回空 payUrl 让前端陷入"已下单却无支付入口"的困惑状态。
                // 抛出点在任何写库之前：订单渠道、会话与 Payment 行都不落地。
                if (PaymentStatus.FAILED.name().equals(paymentResponse.getStatus())) {
                    String err = (paymentResponse.getErrorMessage() != null && !paymentResponse.getErrorMessage().isEmpty())
                            ? paymentResponse.getErrorMessage()
                            : "支付渠道创建失败，请稍后重试或联系支持";
                    throw new BusinessException("PAYMENT_CREATE_FAILED", err);
                }

                session = self.recordPaymentChoice(order.getId(), checkoutId, method, paymentResponse);
                return buildPaymentChoiceResponse(session, method, paymentResponse);
            }
        } finally {
            releaseLock(checkoutLocks, checkoutId, lock);
        }
    }

    /**
     * #16：渠道下单成功后的写库腿，独立短事务——订单所选渠道（H5）+ Payment 记录 + 收银台会话三处同进同退。
     */
    @Transactional
    public CheckoutSession recordPaymentChoice(UUID orderId, String checkoutId, PaymentMethod method,
                                               PaymentResponse paymentResponse) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId));
        // H5 修复：落库订单所选支付渠道，供管理端退款 resolveMethod(order.getPaymentProvider()) 使用。
        // 实际收款渠道在确认收款时落库；选择阶段只记尝试，避免“选了别的渠道就改掉退款渠道”
        paymentService.recordAttempt(order, method, paymentResponse);

        CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId)
            .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));
        session.setProvider(method);
        session.setStatus(CheckoutSession.Status.PENDING);
        session.setProviderSessionId(paymentResponse.getPaymentId());
        // 完整保存支付入口：扫码单必须落 qrCode，否则刷新/重复选渠道后无法恢复
        if (paymentResponse.getPayUrl() != null) {
            session.setPayUrl(paymentResponse.getPayUrl());
            session.setPaymentMode("redirect");
        }
        if (paymentResponse.getRedirectUrl() != null && session.getPayUrl() == null) {
            session.setPayUrl(paymentResponse.getRedirectUrl());
            session.setPaymentMode("redirect");
        }
        if (paymentResponse.getQrCode() != null) {
            session.setQrCode(paymentResponse.getQrCode());
            session.setPaymentMode("qrcode");
        }
        checkoutSessionRepository.save(session);
        return session;
    }

    /** 选择渠道成功后的响应：支付入口按「跳转 → 重定向 → 二维码」优先级取用。 */
    private CheckoutResponse buildPaymentChoiceResponse(CheckoutSession session, PaymentMethod method,
                                                        PaymentResponse paymentResponse) {
        CheckoutResponse.CheckoutResponseBuilder resp = CheckoutResponse.builder()
            .checkoutId(session.getCheckoutId())
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
     *
     * <p>#5（外部调用治理）：本方法**不开事务**——它包含一次外部渠道查单（HTTP）。
     * 原先整体包在 {@code @Transactional} 里，轮询线程会在一等就是数秒的渠道调用期间占住一根 DB 连接
     * （Hikari 默认池 10），渠道抖动时与 webhook 一起把池打满。
     * 写库动作收敛到 {@link #applyCompensatedPayment} 的短事务，其余读由各仓储/服务自带事务完成
     * （发放本身已按 B13/R5 幂等，拆开不改变结果）。
     */
    public CheckoutResponse getStatus(String checkoutId) {
        log.info("查询收银台状态：checkoutId={}", checkoutId);

        CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId)
            .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));

        CheckoutResponse.CheckoutResponseBuilder resp = CheckoutResponse.builder()
            .checkoutId(checkoutId)
            .orderNumber(session.getOrderNumber())
            .status(session.getStatus().name())
            // P16：账号页「继续支付」从订单进会话时，页面需要用会话上的邮箱/机器码补全摘要
            .email(session.getEmail())
            .machineId(session.getMachineId());

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
                // #50：轮询只做查单补偿，不关单（用户可能还停留在渠道页面）；
                // 过期关单由后台任务负责，见 CheckoutReconciliationService。
                reconcileSession(checkoutId, false);
            }
            // 重新读取最新状态（补偿可能已置 PAID）
            session = checkoutSessionRepository.findByCheckoutId(checkoutId)
                .orElseThrow(() -> new BusinessException("CHECKOUT_NOT_FOUND", "Checkout session not found: " + checkoutId));
            resp.status(session.getStatus().name());
        }

        // 会话恢复（页面刷新/第三方支付回跳）：CREATED 会话把当前区域可用支付方式一并返回，
        // 页面恢复流程据此直接续选支付方式；缺了它前端拿到空列表会误报「暂无可用支付方式」。
        // （按 createCheckout 的区域判定口径：session.country CN → 国内渠道，其余 → 国际渠道）
        if (session.getStatus() == CheckoutSession.Status.CREATED) {
            boolean domestic = "CN".equals(session.getCountry());
            // OSIV 已关闭：本方法不开事务，取订单必须预加载行项，否则 resolveBillingCycle 懒加载直接 500
            Order sessionOrder = orderRepository.findWithItemsById(session.getOrderId()).orElse(null);
            List<PaymentMethod> restoreMethods = paymentServiceFactory.getEnabledMethods(
                domestic, resolveChargeMode(sessionOrder));
            resp.paymentMethods(restoreMethods.stream().map(Enum::name).collect(Collectors.toList()));
        }

        // 会话恢复（PENDING）：已建过支付入口的会话必须把既有二维码/跳转链接带回去。
        // 刷新页面或从渠道页回跳后若只拿到「待支付」却拿不到入口，用户只能干等会话过期重下单。
        // 已过期的会话不再下发入口（渠道侧二维码多半已失效）。
        if (session.getStatus() == CheckoutSession.Status.PENDING
                && (session.getExpiresAt() == null
                    || session.getExpiresAt().isAfter(java.time.LocalDateTime.now()))) {
            resp.provider(session.getProvider() != null ? session.getProvider().name() : null);
            applyPaymentEntry(resp, session);
        }

        if (session.getStatus() == CheckoutSession.Status.PAID) {
            // R5：发放幂等——单实例内按订单号串行化，且优先返回已签发记录，
            // 避免并发轮询重复签发 License 或兑换码（资损）。
            // Webhook 侧发放由 R1（原子幂等）+ 订单状态机（canFulfill/markPaid）兜底；
            // 多实例部署建议额外部署分布式锁（见修复说明）。
            OrderLock lock = acquireOrderLock(session.getOrderNumber());
            try {
                synchronized (lock.monitor) {
                    // 交付由履约适配器完成；轮询只读结果（旧 PAID 会话缺事件时补建并交付）
                    fulfillmentService.ensureAndProcess(session.getOrderNumber(),
                        com.billing.license.service.fulfillment.LicenseFulfillmentHandler.TYPE);
                    if (session.getMachineId() != null && !session.getMachineId().isEmpty()) {
                        // 退款后证已 REVOKED，若还按订单把旧 token 返回给轮询页，等于退款后继续发凭证
                        var existing = licenseRepository.findByOrderId(session.getOrderId());
                        var usable = existing.stream()
                            .filter(l -> l.getStatus() == License.LicenseStatus.ACTIVE)
                            .filter(l -> l.getExpiresAt() == null || l.getExpiresAt().isAfter(LocalDateTime.now()))
                            .findFirst().orElse(null);
                        if (usable != null) {
                            resp.license(usable.getSignedToken());
                        }
                    } else {
                        var existing = redeemCodeRepository.findByOrderId(session.getOrderNumber());
                        if (!existing.isEmpty()) {
                            resp.redeemCode(existing.get(0).getCode());
                        }
                    }
                }
            } finally {
                releaseOrderLock(session.getOrderNumber(), lock);
            }
        }
        return resp.build();
    }

    // R5：获取订单级发放锁（单实例串行化，避免并发轮询重复签发）
    private OrderLock acquireOrderLock(String orderNumber) {
        return acquireLock(fulfillmentLocks, orderNumber);
    }

    /**
     * F8：释放锁条目。必须在**退出 monitor 之后**调用——若在临界区内释放，
     * 计数归零摘除后另一线程会新建一个不同的 monitor 并立即进入，两个线程同时处在临界区。
     */
    private void releaseOrderLock(String orderNumber, OrderLock lock) {
        releaseLock(fulfillmentLocks, orderNumber, lock);
    }

    /** 引用计数锁的取用：同一 key 的并发请求共享同一个 monitor，最后一个持有者退出时摘除条目。 */
    private static OrderLock acquireLock(ConcurrentHashMap<String, OrderLock> locks, String key) {
        return locks.compute(key, (k, v) -> {
            OrderLock lock = (v != null) ? v : new OrderLock();
            lock.refs++;
            return lock;
        });
    }

    private static void releaseLock(ConcurrentHashMap<String, OrderLock> locks, String key, OrderLock lock) {
        locks.compute(key, (k, v) -> {
            if (v != lock) {
                return v;
            }
            lock.refs--;
            return (lock.refs == 0) ? null : lock;
        });
    }

    /**
     * B4：从既有会话构造响应（复用已创建的支付入口，不再创建新支付），
     * 用于「同渠道重复选择」与「会话已支付」两种幂等返回。
     */
    private CheckoutResponse buildResumeResponse(CheckoutSession session, PaymentMethod method) {
        CheckoutResponse.CheckoutResponseBuilder resp = CheckoutResponse.builder()
            .checkoutId(session.getCheckoutId())
            .orderNumber(session.getOrderNumber())
            .status(session.getStatus().name())
            .provider(method.name());
        applyPaymentEntry(resp, session);
        return resp.build();
    }

    /**
     * 把会话上已创建的支付入口写进响应：二维码优先，其次跳转链接，并带上会话过期时间。
     * 续选支付方式与状态轮询共用同一份口径，避免两条恢复路径返回的入口不一致。
     */
    private static void applyPaymentEntry(CheckoutResponse.CheckoutResponseBuilder resp,
                                          CheckoutSession session) {
        if ("qrcode".equals(session.getPaymentMode()) && session.getQrCode() != null) {
            resp.qrcode(session.getQrCode());
            resp.paymentMode("qrcode");
        } else if (session.getPayUrl() != null && !session.getPayUrl().isEmpty()) {
            resp.payUrl(session.getPayUrl());
            resp.paymentMode("redirect");
        }
        if (session.getExpiresAt() != null) {
            resp.expiresAt(session.getExpiresAt()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
    }

    /**
     * 支付形态解析（#57）：显式请求优先并校验渠道支持；留空时优先扫码，否则跳转。
     * 与 machineCode 完全解耦——机器绑定只影响发证，不应决定用户看到二维码还是网页。
     */
    private static PaymentProduct resolvePaymentProduct(PaymentMethod method, String requested) {
        java.util.Set<PaymentProduct> supported = method.getPaymentProducts();
        if (requested != null && !requested.isBlank()) {
            try {
                PaymentProduct product = PaymentProduct.valueOf(requested.trim().toUpperCase(java.util.Locale.ROOT));
                if (!supported.contains(product)) {
                    throw new BusinessException("PAYMENT_PRODUCT_NOT_SUPPORTED",
                        "渠道 " + method.name() + " 不支持支付形态：" + product);
                }
                return product;
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_PAYMENT_PRODUCT", "非法的支付形态：" + requested);
            }
        }
        return supported.contains(PaymentProduct.QRCODE)
            ? PaymentProduct.QRCODE : PaymentProduct.REDIRECT;
    }

    private PaymentMethod resolveMethod(String provider) {
        if (provider == null) throw new BusinessException("PROVIDER_REQUIRED", "provider is required");
        try {
            return PaymentMethod.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("UNSUPPORTED_PROVIDER", "Unsupported provider: " + provider);
        }
    }

    /** 从订单明细取收款方式；订单/明细缺失时返回 null（渠道过滤退化为只按区域过滤）。 */
    private static ChargeMode resolveChargeMode(Order order) {
        if (order == null || order.getOrderItems() == null || order.getOrderItems().isEmpty()) {
            return null;
        }
        OrderItem item = order.getOrderItems().get(0);
        return (item != null && item.getProduct() != null) ? item.getProduct().getChargeMode() : null;
    }

    /** #50：单次对账结果（轮询与后台任务共用）。 */
    public enum ReconcileResult {
        /** 本轮确认已收款并完成补偿发货 */
        PAID,
        /** 渠道说成功但缺金额/币种/订单关联证据，保持待核 */
        UNVERIFIED,
        /** 渠道未确认收款，会话未过期，继续等待 */
        UNPAID,
        /** 会话已过期且渠道已确认关单（或交易已关闭），不可能再收款 */
        CLOSED,
        /** 会话已过期，渠道没有关单接口，只能保留关联继续观察 */
        CLOSE_UNSUPPORTED,
        /** 会话已过期，关单结果未知，下一轮继续 */
        CLOSE_UNKNOWN,
        /** 会话已过期且从未创建过支付入口 */
        EXPIRED,
        /** 会话未过期且没有支付尝试（用户还在选渠道） */
        NO_ATTEMPT,
        /** 会话已支付或状态无需对账 */
        SKIPPED,
        /** 查单异常 */
        ERROR
    }

    /** 单条支付尝试的查单结果（#55 多尝试逐条核对）。 */
    private record AttemptCheck(PaymentAttempt attempt, PaymentQueryResult detail) {}

    /**
     * 对单个收银台会话做一次渠道对账（#50）。
     *
     * <p>页面轮询（{@link #getStatus}）与后台定时任务共用本方法：先查单，只有「已收款且金额、
     * 币种、订单关联齐全」才补偿发货；会话过期且渠道确认未收款时，支持关单的渠道立即关单，
     * 避免本地过期后用户仍能用原二维码/链接付款。
     *
     * <p>不依赖页面存活——这是「回调丢失 + 用户关页面」场景的唯一兜底。本方法不开事务：
     * 查单/关单是外部 HTTP，写库分别收敛进 {@link #applyCompensatedPayment} 与 mark* 的短事务
     * （承硬约束 16）。
     *
     * @param allowClose 会话过期时是否允许关单：轮询路径传 false（用户可能还在渠道页面），
     *                   后台任务传 true
     */
    public ReconcileResult reconcileSession(String checkoutId, boolean allowClose) {
        if (checkoutSessionRepository.findByCheckoutId(checkoutId).isEmpty()) {
            return ReconcileResult.SKIPPED;
        }
        // 与 selectProvider 共用 checkoutId 锁：避免「后台关单」与「用户刚好又选渠道」交叉
        OrderLock lock = acquireLock(checkoutLocks, checkoutId);
        try {
            synchronized (lock.monitor) {
                CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId).orElse(null);
                if (session == null) return ReconcileResult.SKIPPED;
                if (session.getStatus() == CheckoutSession.Status.PAID) return ReconcileResult.PAID;
                if (session.getStatus() != CheckoutSession.Status.CREATED
                        && session.getStatus() != CheckoutSession.Status.PENDING) {
                    return ReconcileResult.SKIPPED;
                }
                boolean expired = session.getExpiresAt() != null
                    && session.getExpiresAt().isBefore(LocalDateTime.now());

                // #55 多尝试安全：用户换过渠道时同订单有多行尝试，必须逐条查——
                // 只看最新一条会漏掉「旧渠道其实已收款」的场景（钱收了货不发）。
                List<PaymentAttempt> attempts = attemptRepository
                    .findAllByOrderIdStrOrderByCreatedAtDesc(session.getOrderId().toString())
                    .stream()
                    .filter(a -> a.getMethod() != null)
                    .filter(a -> a.getStatus() != PaymentStatus.FAILED
                        && a.getStatus() != PaymentStatus.CANCELLED
                        && a.getStatus() != PaymentStatus.REFUNDED)
                    .toList();
                if (attempts.isEmpty()) {
                    if (!expired) return ReconcileResult.NO_ATTEMPT;
                    markExpired(session, null);
                    return ReconcileResult.EXPIRED;
                }

                List<AttemptCheck> checks = new ArrayList<>(attempts.size());
                for (PaymentAttempt attempt : attempts) {
                    try {
                        checks.add(new AttemptCheck(attempt,
                            paymentService.queryPaymentDetail(attempt.getPaymentId(), attempt.getMethod())));
                    } catch (Exception e) {
                        log.warn("主动对账查单失败，下一轮重试：checkoutId={}, paymentId={}",
                            checkoutId, attempt.getPaymentId(), e);
                        markCompensated(session);
                        return ReconcileResult.ERROR;
                    }
                }

                // 先查完全部尝试再决定：任一条确认收款且证据齐全即补偿，绝不被后续关单误关
                boolean unverifiedPaid = false;
                for (AttemptCheck check : checks) {
                    if (check.detail().status() == PaymentStatus.SUCCESS) {
                        ReconcileResult result = compensateVerifiedPayment(session, check.attempt(), check.detail());
                        if (result == ReconcileResult.PAID) {
                            return result;
                        }
                        unverifiedPaid = true;
                    }
                }
                if (unverifiedPaid) {
                    markCompensated(session);
                    return ReconcileResult.UNVERIFIED;
                }

                if (expired && allowClose) {
                    boolean allClosed = true;
                    boolean anyUnsupported = false;
                    boolean anyUnknown = false;
                    for (AttemptCheck check : checks) {
                        PaymentAttempt attempt = check.attempt();
                        if (check.detail().status() == PaymentStatus.CANCELLED) {
                            markAttemptClosed(attempt, "渠道交易已关闭");
                            continue;
                        }
                        PaymentCloseResult close;
                        try {
                            close = paymentService.closeUnpaidPayment(attempt.getPaymentId(), attempt.getMethod());
                        } catch (Exception e) {
                            log.warn("关单调用失败，保留会话待下一轮：checkoutId={}, paymentId={}",
                                checkoutId, attempt.getPaymentId(), e);
                            anyUnknown = true;
                            allClosed = false;
                            continue;
                        }
                        if (close.isClosed()) {
                            markAttemptClosed(attempt, close.message());
                            log.info("过期会话已安全关单：checkoutId={}, paymentId={}, result={}",
                                checkoutId, attempt.getPaymentId(), close.message());
                        } else {
                            allClosed = false;
                            if (close.status() == PaymentCloseResult.Status.UNSUPPORTED) {
                                anyUnsupported = true;
                            } else {
                                anyUnknown = true;
                            }
                            log.warn("过期会话关单未确认，继续保留对账：checkoutId={}, paymentId={}, status={}, msg={}",
                                checkoutId, attempt.getPaymentId(), close.status(), close.message());
                        }
                    }
                    markCompensated(session);
                    if (allClosed) {
                        markExpired(session, LocalDateTime.now());
                        return ReconcileResult.CLOSED;
                    }
                    return anyUnknown || !anyUnsupported
                        ? ReconcileResult.CLOSE_UNKNOWN : ReconcileResult.CLOSE_UNSUPPORTED;
                }

                // 渠道未确认支付是最常见的正常分支（用户还没付/渠道查不到单），必须留痕：
                // 早期此处静默 return，排查「已付款却迟迟不发货」时日志全黑。
                log.info("主动对账未确认支付（继续等待）：checkoutId={}, attempts={}, 渠道返回={}",
                    checkoutId, checks.size(),
                    checks.stream().map(c -> c.detail().status()).toList());
                markCompensated(session);
                return ReconcileResult.UNPAID;
            }
        } catch (Exception e) {
            // 对账是旁路逻辑：异常不得让轮询接口或调度线程崩溃，会话状态保持原样待下一轮
            log.warn("收银台对账异常（保留会话待重试）：checkoutId={}", checkoutId, e);
            return ReconcileResult.ERROR;
        } finally {
            releaseLock(checkoutLocks, checkoutId, lock);
        }
    }

    /** 渠道确认收款后的证据核对与补偿写库；证据不齐时保持待核，绝不发货。 */
    private ReconcileResult compensateVerifiedPayment(CheckoutSession session, PaymentAttempt attempt,
                                                      PaymentQueryResult detail) {
        if (!detail.verified()) {
            log.warn("主动查单缺少金额/币种/订单证据，保持待核不发货：checkoutId={}, paymentId={}",
                session.getCheckoutId(), attempt.getPaymentId());
            return ReconcileResult.UNVERIFIED;
        }
        Order order = orderRepository.findByOrderNumber(session.getOrderNumber()).orElse(null);
        if (order == null) {
            log.warn("主动查单命中但订单不存在：orderNumber={}", session.getOrderNumber());
            return ReconcileResult.UNVERIFIED;
        }
        if (detail.orderReference() != null
                && !detail.orderReference().equals(session.getOrderNumber())) {
            log.error("主动查单订单关联不符，拒绝发货：expected={}, actual={}",
                session.getOrderNumber(), detail.orderReference());
            return ReconcileResult.UNVERIFIED;
        }
        BigDecimal expected = order.getTotalAmount();
        String expectedCurrency = order.getCurrency() != null ? order.getCurrency().code() : null;
        if (detail.amount() == null || expected == null
                || detail.amount().subtract(expected).abs().compareTo(new BigDecimal("0.01")) > 0) {
            log.error("主动查单金额不符，拒绝发货：expected={}, actual={}", expected, detail.amount());
            return ReconcileResult.UNVERIFIED;
        }
        if (detail.currency() == null || expectedCurrency == null
                || !detail.currency().equalsIgnoreCase(expectedCurrency)) {
            log.error("主动查单币种不符，拒绝发货：expected={}, actual={}", expectedCurrency, detail.currency());
            return ReconcileResult.UNVERIFIED;
        }
        OrderLock orderLock = acquireOrderLock(session.getOrderNumber());
        try {
            synchronized (orderLock.monitor) {
                self.applyCompensatedPayment(session.getCheckoutId(), attempt.getPaymentId());
            }
        } finally {
            releaseOrderLock(session.getOrderNumber(), orderLock);
        }
        log.info("主动对账补偿：渠道确认已支付，已标记会话/订单 PAID 并发货：checkoutId={}", session.getCheckoutId());
        return ReconcileResult.PAID;
    }

    private void markCompensated(CheckoutSession session) {
        session.setLastCompensatedAt(LocalDateTime.now());
        checkoutSessionRepository.save(session);
    }

    /** 会话置过期；{@code closedAt} 非空表示渠道已确认关单，交易不可能再收款。 */
    private void markExpired(CheckoutSession session, LocalDateTime closedAt) {
        session.setStatus(CheckoutSession.Status.EXPIRED);
        session.setLastCompensatedAt(LocalDateTime.now());
        if (closedAt != null) {
            session.setChannelClosedAt(closedAt);
        }
        checkoutSessionRepository.save(session);
    }

    /** 关单确认后把尝试置为已取消，保留行作为证据；已成功的尝试绝不覆盖。 */
    private void markAttemptClosed(PaymentAttempt attempt, String reason) {
        if (attempt.getStatus() == PaymentStatus.SUCCESS) {
            return;
        }
        attempt.setStatus(PaymentStatus.CANCELLED);
        attempt.setErrorMessage(reason);
        attemptRepository.save(attempt);
    }

    /**
     * C5：补偿确认「渠道已收款」后的写库腿，独立短事务（外部查单已在事务外完成，见 {@link #reconcileSession}）。
     *
     * <p>统一发货入口——会话与订单同置 PAID 并复用与 fulfillOrder 等价的发放逻辑，避免「只改会话不发货 /
     * 订单恒 PENDING」导致 Webhook 迟到二次发放或永远拿不到 License。
     * 幂等：会话已被 Webhook 抢先置 PAID 时直接跳过。
     */
    @Transactional
    public void applyCompensatedPayment(String checkoutId, String paymentId) {
        CheckoutSession session = checkoutSessionRepository.findByCheckoutId(checkoutId).orElse(null);
        if (session == null || session.getStatus() == CheckoutSession.Status.PAID) {
            return;
        }
        session.setStatus(CheckoutSession.Status.PAID);
        checkoutSessionRepository.save(session);
        // #4：与 fulfillOrder 用同一订单行锁，序列化「轮询补偿」与「Webhook 回调」两条发货腿。
        // 原先只读订单（无锁）会让补偿腿在 Webhook 已提交 PAID 后仍按内存里的旧状态再发一次货。
        Order order = orderRepository.findByOrderNumberForUpdate(session.getOrderNumber()).orElse(null);
        if (order != null && order.canFulfill()) {
            order.markPaid();
            orderRepository.save(order);
            PaymentAttempt attempt = attemptRepository.findByPaymentId(paymentId).orElse(null);
            if (attempt != null && attempt.getMethod() != null) {
                paymentService.recordCharge(order, attempt, attempt.getMethod(),
                    order.getTotalAmount(), order.getCurrency(),
                    attempt.getTransactionId() != null ? attempt.getTransactionId() : paymentId,
                    "charge:" + paymentId, "checkout-compensation");
            }
            // 支付事务只登记履约事件；本次请求返回后由 getStatus 调用 ensureAndProcess 同步交付
            fulfillmentService.enqueue(order,
                com.billing.license.service.fulfillment.LicenseFulfillmentHandler.TYPE, null);
        }
    }

    /**
     * F7：按业务订单号回查收银台会话的买家 locale，供发货/退款邮件选中英文。
     * 会话缺失时返回 {@code null}（{@code EmailNotificationService.preferEn} 据此回落 en）。
     */
    @Transactional(readOnly = true)
    public String findLocaleByOrderNumber(String orderNumber) {
        return checkoutSessionRepository.findByOrderNumber(orderNumber)
            .map(CheckoutSession::getLocale)
            .orElse(null);
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

    /** 区域只由结算币种决定；仅当调用方未给币种时才回落到 locale。 */
    private boolean isDomestic(Currency currency, String locale) {
        if (currency != null) return currency == Currency.CNY;
        return locale != null && locale.toLowerCase().startsWith("zh");
    }

    /** 渠道商品/价目映射是否就绪（缺失必须在调用渠道前拒单，不能等渠道报错）。 */
    private static boolean mappingReady(PaymentMethod method, Product product) {
        if (product == null) {
            return true;
        }
        boolean recurring = product.getBillingCycle() != null && product.getBillingCycle().isRecurring();
        return switch (method) {
            case CREEM -> hasText(product.getCreemProductId());
            case PADDLE -> !recurring || hasText(product.getPaddlePriceId());
            default -> true;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 产品级回跳允许列表：站内相对路径，或与 {@code app.base-url} 同 scheme+host 的绝对地址。
     * 其余一律丢弃（返回 null），避免收银台把任意 URL 透传给渠道形成开放跳转。
     */
    private String sanitizeReturnUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.startsWith("/")) {
            return trimmed.startsWith("//") ? null : trimmed;
        }
        try {
            java.net.URI candidate = java.net.URI.create(trimmed);
            java.net.URI base = java.net.URI.create(appBaseUrl);
            String scheme = candidate.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || candidate.getHost() == null
                    || base.getHost() == null
                    || !candidate.getHost().equalsIgnoreCase(base.getHost())) {
                log.warn("回跳地址不在允许列表，已丢弃：{}", trimmed);
                return null;
            }
            return trimmed;
        } catch (Exception e) {
            log.warn("回跳地址非法，已丢弃：{}", trimmed);
            return null;
        }
    }

    /**
     * 本地订单号同时作为支付宝 {@code out_trade_no} 使用，必须满足支付宝字符集：
     * 仅字母/数字/下划线、64 字符以内。因此不能带 {@code -}（旧格式 ORD-<ms>-<hex> 会被网关拒绝）。
     */
    static String generateOrderNumber() {
        return "ORD" + System.currentTimeMillis()
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /**
     * M2 + #50：定时清理过期且**从未创建过支付入口**的收银台会话，避免记录无限增长。
     * 默认每小时执行一次（fixedDelay，上次完成后间隔）；可通过
     * billing.checkout-session.cleanup-interval-ms 调整。
     *
     * <p>有支付尝试的会话一律保留：它是「订单 ↔ 渠道交易」的唯一关联，Webhook 丢失时靠它补偿发货，
     * 关单未确认时靠它继续观察；删掉会让已收款订单永远无法交付（未对清证据）。
     * 必须开事务：派生删除走 JPA remove，无事务时抛 TransactionRequiredException。
     */
    @Transactional
    @Scheduled(fixedDelayString = "${billing.checkout-session.cleanup-interval-ms:3600000}")
    public void cleanupExpiredSessions() {
        LocalDateTime cutoff = LocalDateTime.now();
        int deleted = 0;
        for (int round = 0; round < CLEANUP_MAX_BATCHES; round++) {
            List<UUID> ids = checkoutSessionRepository.findExpiredUnlinkedIds(
                CheckoutSession.Status.PAID, cutoff, PageRequest.of(0, CLEANUP_BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            deleted += checkoutSessionRepository.deleteByIds(ids);
            if (ids.size() < CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("清理过期且无支付关联的收银台会话 {} 条", deleted);
        }
    }
}
