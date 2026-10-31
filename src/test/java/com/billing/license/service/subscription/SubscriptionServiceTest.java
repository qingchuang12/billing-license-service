package com.billing.license.service.subscription;

import com.billing.license.entity.*;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.SubscriptionRepository;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.SubscriptionLookup;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SubscriptionService 单元测试（B18 / M4 提优先级验证闸门）
 * 覆盖：首充绑定 License、续费延长有效期、取消作废 License。
 */
class SubscriptionServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private OrderRepository orderRepository;
    private LicenseRepository licenseRepository;
    private ProductRepository productRepository;
    private LicenseService licenseService;
    private RedeemCodeService redeemCodeService;
    private List<PaymentStrategy> paymentStrategies;
    private TransactionOperations transactionOperations;
    private SubscriptionNotificationService notificationService;
    private SubscriptionService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID licenseId = UUID.randomUUID();
    private final String subId = "sub_abc123";

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        orderRepository = mock(OrderRepository.class);
        licenseRepository = mock(LicenseRepository.class);
        productRepository = mock(ProductRepository.class);
        licenseService = mock(LicenseService.class);
        redeemCodeService = mock(RedeemCodeService.class);
        // #26：默认无策略可用，重修路径取不到订单号（退回人工告警）；
        // 需要验证重修的用例在测试内自行加入渠道策略桩。
        paymentStrategies = new java.util.ArrayList<>();
        // 编程式事务桩：直接同步执行事务体（等价无事务，验证的是仓库调用与状态流转）
        transactionOperations = mock(TransactionOperations.class);
        notificationService = mock(SubscriptionNotificationService.class);
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionOperations).executeWithoutResult(any());
        service = new SubscriptionService(subscriptionRepository, orderRepository,
            licenseRepository, productRepository, licenseService, redeemCodeService,
            paymentStrategies, transactionOperations, notificationService);
    }

    private Product buildProduct(int durationDays) {
        return Product.builder().id(productId).sku("pro-subscription")
            .name("订阅 Pro").price(java.math.BigDecimal.valueOf(19)).currency(Currency.USD)
            .licenseDurationDays(durationDays).tier(PlanTier.PRO).build();
    }

    private Order buildOrder() {
        Product product = buildProduct(31);
        OrderItemStub item = new OrderItemStub(product);
        Order order = Order.builder().id(orderId).orderNumber("ORD-SUB-1")
            .customerId(customerId).title("订阅 Pro").build();
        order.setOrderItems(List.of(item));
        return order;
    }

    // 轻量 OrderItem 替身（仅满足 getProduct()）
    static class OrderItemStub extends com.billing.license.entity.OrderItem {
        private final Product product;
        OrderItemStub(Product product) { this.product = product; }
        @Override public Product getProduct() { return product; }
    }

    private License buildLicense(int daysFromNow) {
        Product product = buildProduct(31);
        return License.builder().id(licenseId).licenseKey("LIC-1").customerId(customerId)
            .product(product).status(License.LicenseStatus.ACTIVE)
            .issuedAt(LocalDateTime.now())
            .expiresAt(LocalDateTime.now().plusDays(daysFromNow)).build();
    }

    private WebhookPayload buildPayload(String status, String eventType) {
        WebhookPayload p = new WebhookPayload();
        p.setSubscriptionId(subId);
        p.setOrderId("ORD-SUB-1");
        p.setEventType(eventType);
        p.setStatus(status);
        return p;
    }

    @Test
    void firstCharge_shouldCreateSubscriptionAndBindLicense() {
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.empty());
        when(orderRepository.findByOrderNumber("ORD-SUB-1")).thenReturn(Optional.of(buildOrder()));
        when(licenseRepository.findByOrderId(orderId)).thenReturn(List.of(buildLicense(31)));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        service.processSubscriptionEvent(buildPayload(PaymentStatus.SUCCESS.name(), "transaction.completed"), PaymentMethod.PADDLE);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        // 首充时 save 被调用两次：创建订阅、末次落库
        verify(subscriptionRepository, times(2)).save(captor.capture());
        Subscription saved = captor.getValue();
        assertEquals(Subscription.SubscriptionStatus.ACTIVE, saved.getStatus());
        assertEquals(licenseId, saved.getLicenseId());
        assertEquals(orderId, saved.getOrderId());
        assertEquals(subId, saved.getProviderSubscriptionId());
        assertEquals(PaymentMethod.PADDLE, saved.getProvider());
        // 首充不应延长有效期
        verify(licenseRepository, never()).save(any(License.class));
    }

    @Test
    void renewal_shouldExtendLicenseExpiry() {
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.STRIPE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.STRIPE, subId))
            .thenReturn(Optional.of(existing));
        License license = buildLicense(10); // 当前剩余 10 天
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        service.processSubscriptionEvent(buildPayload(PaymentStatus.SUCCESS.name(), "invoice.paid"), PaymentMethod.STRIPE);

        ArgumentCaptor<License> licCaptor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository).save(licCaptor.capture());
        License renewed = licCaptor.getValue();
        // 续期：在当前过期时间基础上 +31 天，而非从现在算起
        assertTrue(renewed.getExpiresAt().isAfter(LocalDateTime.now().plusDays(40)),
            "续期后应比现在多出 >40 天（原剩余10天 + 31天）");
        assertEquals(License.LicenseStatus.ACTIVE, renewed.getStatus());
        verify(subscriptionRepository).save(any(Subscription.class));
    }

    @Test
    void renewal_duplicateSuccessEvents_shouldBeIdempotent() {
        LocalDateTime periodEnd = LocalDateTime.now().plusDays(30);
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).currentPeriodEnd(periodEnd).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.of(existing));
        License license = buildLicense(10); // 当前剩余 10 天
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        WebhookPayload p = buildPayload(PaymentStatus.SUCCESS.name(), "transaction.billed");
        p.setCurrentPeriodEnd(periodEnd);

        // 同一订阅周期收到两次 SUCCESS 事件（Paddle 可能重复投递）
        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);
        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);

        ArgumentCaptor<License> licCaptor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository, times(2)).save(licCaptor.capture());
        // 两次落库的 License 过期时间均等于 Paddle 权威周期结束时间（幂等，不累加两次）
        for (License saved : licCaptor.getAllValues()) {
            assertEquals(periodEnd, saved.getExpiresAt(),
                "重复 SUCCESS 事件不应累加延长，过期时间应等于 Paddle 周期结束时间（而非 2×31 天）");
            assertEquals(License.LicenseStatus.ACTIVE, saved.getStatus());
        }
    }

    @Test
    void cancellation_shouldExpireLicense() {
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.of(existing));
        License license = buildLicense(100);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        service.processSubscriptionEvent(buildPayload(PaymentStatus.CANCELLED.name(), "subscription.canceled"), PaymentMethod.PADDLE);

        ArgumentCaptor<License> licCaptor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository).save(licCaptor.capture());
        assertEquals(License.LicenseStatus.EXPIRED, licCaptor.getValue().getStatus());
        assertNotNull(licCaptor.getValue().getExpiresAt());

        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(subCaptor.capture());
        assertEquals(Subscription.SubscriptionStatus.CANCELED, subCaptor.getValue().getStatus());
        verify(notificationService).notifyStatus(eq(orderId), eq("订阅已取消"),
            eq("Subscription canceled"), anyString(), eq(false));
    }

    @Test
    void renewal_subscriptionUpdatedThenTransactionBilled_shouldBeIdempotent() {
        // A10 根治（2026-09-24）：真实链路——subscription.updated 携周期落库后，
        // transaction.billed（无周期字段）到达，续期应依赖已持久化的 currentPeriodEnd 取 max，
        // 而非回退累加。两个不同 eventId 投递同一周期也只收敛到同一目标值，杜绝重复延长。
        LocalDateTime periodEnd = LocalDateTime.now().plusDays(30);
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.of(existing));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));
        License license = buildLicense(10); // 当前剩余 10 天
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        // 1) subscription.updated 携周期
        WebhookPayload updated = buildPayload(PaymentStatus.SUCCESS.name(), "subscription.updated");
        updated.setCurrentPeriodEnd(periodEnd);
        service.processSubscriptionEvent(updated, PaymentMethod.PADDLE);

        // 2) transaction.billed 不带周期（currentPeriodEnd 由已持久化的 sub 提供）
        WebhookPayload billed = buildPayload(PaymentStatus.SUCCESS.name(), "transaction.billed");
        service.processSubscriptionEvent(billed, PaymentMethod.PADDLE);

        ArgumentCaptor<License> licCaptor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository, times(2)).save(licCaptor.capture());
        for (License saved : licCaptor.getAllValues()) {
            assertEquals(periodEnd, saved.getExpiresAt(),
                "续期应等于 Paddle 权威周期结束时间，而非累加延长（避免双 eventId 重复投递重复延长）");
            assertEquals(License.LicenseStatus.ACTIVE, saved.getStatus());
        }
    }

    @Test
    void cancellation_withFuturePeriodEnd_shouldKeepLicenseUntilPeriodEnd() {
        LocalDateTime periodEnd = LocalDateTime.now().plusDays(20);
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).currentPeriodEnd(periodEnd).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.of(existing));
        License license = buildLicense(20);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        service.processSubscriptionEvent(buildPayload(PaymentStatus.CANCELLED.name(), "subscription.canceled"), PaymentMethod.PADDLE);

        ArgumentCaptor<License> licCaptor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository).save(licCaptor.capture());
        assertEquals(License.LicenseStatus.ACTIVE, licCaptor.getValue().getStatus(),
            "期末取消不应立即作废权益");
        assertEquals(periodEnd, licCaptor.getValue().getExpiresAt(),
            "权益应保留到已付周期结束");

        ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(subCaptor.capture());
        assertEquals(Subscription.SubscriptionStatus.CANCELED, subCaptor.getValue().getStatus());
        assertTrue(subCaptor.getValue().getCancelAtPeriodEnd());
    }

    @Test
    void paidEvent_afterExpiry_shouldReviveAndExtend() {
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.EXPIRED)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.of(existing));
        License license = buildLicense(-1);
        license.setStatus(License.LicenseStatus.EXPIRED);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));
        WebhookPayload p = buildPayload(PaymentStatus.SUCCESS.name(), "transaction.billed");
        p.setCurrentPeriodEnd(LocalDateTime.now().plusDays(30));

        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);

        // 已过期后渠道又确认扣款成功：必须恢复订阅并延长权益，不能收钱不发货
        assertEquals(Subscription.SubscriptionStatus.ACTIVE, existing.getStatus());
        assertEquals(License.LicenseStatus.ACTIVE, license.getStatus());
        assertTrue(license.getExpiresAt().isAfter(LocalDateTime.now().plusDays(29)));
        verify(licenseRepository).save(license);
    }

    @Test
    void lateSuccessEvent_afterCancellation_shouldNotResurrectSubscription() {
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE)
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.CANCELED)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.of(existing));

        service.processSubscriptionEvent(buildPayload(PaymentStatus.SUCCESS.name(), "transaction.billed"), PaymentMethod.PADDLE);

        verify(licenseRepository, never()).save(any(License.class));
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        assertEquals(Subscription.SubscriptionStatus.CANCELED, existing.getStatus());
    }

    @Test
    void missingOrderAndNoExistingSubscription_shouldNotCreate() {
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.empty());
        WebhookPayload p = new WebhookPayload();
        p.setSubscriptionId(subId);
        p.setOrderId(null); // 无订单、无已有订阅
        p.setStatus(PaymentStatus.SUCCESS.name());
        p.setEventType("subscription.updated");

        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);

        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }

    // ===== #26 首充回调丢失后的渠道查询自动重修 =====

    private PaymentStrategy providerStub(PaymentMethod method, Optional<SubscriptionLookup> lookup) {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.getPaymentMethod()).thenReturn(method);
        when(strategy.lookupSubscription(subId)).thenReturn(lookup);
        paymentStrategies.add(strategy);
        return strategy;
    }

    @Test
    void missingOrderId_shouldRepairByProviderLookup() {
        // #26：首充回调丢失（事件无 orderId）不再只翻日志人工补录——按 subId 到渠道反查，
        // 取回下单时写入订阅的 order_id 后补建订阅记录并绑定 License。
        providerStub(PaymentMethod.PADDLE, Optional.of(SubscriptionLookup.builder()
            .subscriptionId(subId).orderId("ORD-SUB-1").active(true)
            .currentPeriodEnd(LocalDateTime.now().plusDays(31)).build()));

        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.empty());
        when(orderRepository.findByOrderNumber("ORD-SUB-1")).thenReturn(Optional.of(buildOrder()));
        when(licenseRepository.findByOrderId(orderId)).thenReturn(List.of(buildLicense(31)));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(i -> i.getArgument(0));

        WebhookPayload p = buildPayload(PaymentStatus.SUCCESS.name(), "transaction.billed");
        p.setOrderId(null);

        // 渠道反查已前置到事务外：先补全订单号，再处理事件
        assertTrue(service.enrichFromProvider(p, PaymentMethod.PADDLE));
        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository, times(2)).save(captor.capture());
        assertEquals(orderId, captor.getValue().getOrderId());
        assertEquals(subId, captor.getValue().getProviderSubscriptionId());
        assertEquals(PaymentMethod.PADDLE, captor.getValue().getProvider());
        assertEquals(licenseId, captor.getValue().getLicenseId());
    }

    @Test
    void missingOrderId_lookupEmpty_shouldSkipAndNotCreate() {
        // 渠道查不到（订阅已删/凭据失效/未携带 order_id）→ 不建记录，退回人工告警
        PaymentStrategy paddle = providerStub(PaymentMethod.PADDLE, Optional.empty());

        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE, subId))
            .thenReturn(Optional.empty());

        WebhookPayload p = buildPayload(PaymentStatus.SUCCESS.name(), "transaction.billed");
        p.setOrderId(null);

        // 反查失败返回 false，由控制器转 503 让渠道重投；不得落订阅记录
        assertFalse(service.enrichFromProvider(p, PaymentMethod.PADDLE));
        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);

        verify(paddle).lookupSubscription(subId);
        verify(subscriptionRepository, never()).save(any(Subscription.class));
        verify(orderRepository, never()).findByOrderNumber(any());
    }

    @Test
    void missingOrderId_shouldOnlyQueryTheOwningProvider() {
        // 只查事件所属渠道：Stripe 的事件不得拿 Paddle 的凭据去问（避免串渠道查错对象）
        PaymentStrategy paddle = providerStub(PaymentMethod.PADDLE, Optional.of(SubscriptionLookup.builder()
            .subscriptionId(subId).orderId("ORD-SUB-1").active(true).build()));

        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.STRIPE, subId))
            .thenReturn(Optional.empty());

        WebhookPayload p = buildPayload(PaymentStatus.SUCCESS.name(), "invoice.paid");
        p.setOrderId(null);

        assertFalse(service.enrichFromProvider(p, PaymentMethod.STRIPE));
        service.processSubscriptionEvent(p, PaymentMethod.STRIPE);

        verify(paddle, never()).lookupSubscription(any());
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }
}
