package com.billing.license.service.subscription;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.PlanTier;
import com.billing.license.entity.Product;
import com.billing.license.entity.Subscription;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.SubscriptionRepository;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.WebhookPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        service = new SubscriptionService(subscriptionRepository, orderRepository,
            licenseRepository, productRepository, licenseService, redeemCodeService);
    }

    private Product buildProduct(int durationDays) {
        return Product.builder().id(productId).sku("pro-subscription")
            .name("订阅 Pro").price(java.math.BigDecimal.valueOf(19)).currency("USD")
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
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE.name(), subId))
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
        assertEquals(PaymentMethod.PADDLE.name(), saved.getProvider());
        // 首充不应延长有效期
        verify(licenseRepository, never()).save(any(License.class));
    }

    @Test
    void renewal_shouldExtendLicenseExpiry() {
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.STRIPE.name())
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.STRIPE.name(), subId))
            .thenReturn(Optional.of(existing));
        License license = buildLicense(10); // 当前剩余 10 天
        when(licenseRepository.findByOrderId(orderId)).thenReturn(List.of(license));

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
    void cancellation_shouldExpireLicense() {
        Subscription existing = Subscription.builder().id(UUID.randomUUID()).orderId(orderId)
            .customerId(customerId).provider(PaymentMethod.PADDLE.name())
            .providerSubscriptionId(subId).status(Subscription.SubscriptionStatus.ACTIVE)
            .licenseId(licenseId).build();
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE.name(), subId))
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
    }

    @Test
    void missingOrderAndNoExistingSubscription_shouldNotCreate() {
        when(subscriptionRepository.findByProviderAndProviderSubscriptionId(PaymentMethod.PADDLE.name(), subId))
            .thenReturn(Optional.empty());
        WebhookPayload p = new WebhookPayload();
        p.setSubscriptionId(subId);
        p.setOrderId(null); // 无订单、无已有订阅
        p.setStatus(PaymentStatus.SUCCESS.name());
        p.setEventType("subscription.updated");

        service.processSubscriptionEvent(p, PaymentMethod.PADDLE);

        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }
}
