package com.billing.license.controller;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.SubscriptionView;
import com.billing.license.entity.*;
import com.billing.license.repository.*;
import com.billing.license.service.AccountAssetService;
import com.billing.license.service.OrderService;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AccountAssetController 单元测试（U1 用户资产自助查询）——
 * 覆盖三个只读端点的字段映射与脱敏口径：licenseKey 完整回显、signedToken 不返回、
 * machineCode/客户邮箱回填、订阅产品信息批量解析。
 */
class AccountAssetControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final LicenseRepository licenseRepository = mock(LicenseRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final AccountAssetService service = new AccountAssetService(
            licenseRepository, subscriptionRepository, orderRepository,
            productRepository, userRepository, new OrderService(orderRepository));
    private final AccountAssetController controller = new AccountAssetController(service);

    /** 模拟 JwtAuthFilter 写入的当前用户上下文（principal = userId 字符串） */
    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(USER_ID.toString(), null));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User sampleUser() {
        return User.builder().email("buyer@example.com").build();
    }

    private License sampleLicense() {
        return License.builder()
                .licenseKey("LIC-2F8A-7C31-9D04-B5E6")
                .customerId(USER_ID)
                .status(License.LicenseStatus.ACTIVE)
                .issuedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .expiresAt(LocalDateTime.of(2027, 9, 1, 10, 0))
                .machineCode("5E01-7EB8-3661-E06A")
                .signedToken("header.payload.signature")
                .build();
    }

    private Subscription sampleSubscription() {
        UUID productId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        return Subscription.builder()
                .customerId(USER_ID)
                .productId(productId)
                .provider(PaymentMethod.STRIPE)
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.of(2026, 9, 1, 0, 0))
                .currentPeriodEnd(LocalDateTime.of(2026, 10, 1, 0, 0))
                .cancelAtPeriodEnd(false)
                .build();
    }

    private Product sampleProduct() {
        Product product = Product.builder().sku("pro-sub-yearly").name("AI-Tools Pro 订阅版").build();
        product.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        return product;
    }

    private Order sampleOrder() {
        return Order.builder()
                .orderNumber("ORD20260901100000123")
                .email("buyer@example.com")
                .totalAmount(new BigDecimal("99.00"))
                .currency(Currency.USD)
                .status(Order.OrderStatus.PAID)
                .paymentStatus(Order.PaymentStatus.PAID)
                .createdAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .build();
    }

    @Test
    void licenses_selfViewFullKeyWithoutSignedToken() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser()));
        when(licenseRepository.findByCustomerIdOrderByIssuedAtDesc(USER_ID))
                .thenReturn(List.of(sampleLicense()));

        ResponseEntity<List<LicenseResponse>> resp = controller.myLicenses();

        assertEquals(200, resp.getStatusCode().value());
        List<LicenseResponse> body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        LicenseResponse dto = body.get(0);
        // 自助视角核心口径：密钥完整可见（激活所需），离线验签令牌不外发
        assertEquals("LIC-2F8A-7C31-9D04-B5E6", dto.getLicenseKey());
        assertNull(dto.getSignedToken());
        assertEquals("buyer@example.com", dto.getCustomerEmail());
        assertEquals("5E01-7EB8-3661-E06A", dto.getMachineCode());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals(LocalDateTime.of(2027, 9, 1, 10, 0), dto.getExpiresAt());
    }

    @Test
    void subscriptions_mapProviderStatusAndProduct() {
        when(subscriptionRepository.findByCustomerIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(sampleSubscription()));
        when(productRepository.findAllById(anyList())).thenReturn(List.of(sampleProduct()));

        ResponseEntity<List<SubscriptionView>> resp = controller.mySubscriptions();

        assertEquals(200, resp.getStatusCode().value());
        List<SubscriptionView> body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        SubscriptionView dto = body.get(0);
        assertEquals("STRIPE", dto.getProvider());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals("pro-sub-yearly", dto.getProductSku());
        assertEquals("AI-Tools Pro 订阅版", dto.getProductName());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), dto.getCurrentPeriodEnd());
        assertEquals(false, dto.getCancelAtPeriodEnd());
    }

    @Test
    void subscriptions_emptyWhenNoRecords() {
        when(subscriptionRepository.findByCustomerIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of());

        ResponseEntity<List<SubscriptionView>> resp = controller.mySubscriptions();

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isEmpty());
        // 空列表不应触发产品批量查询
        verify(productRepository, never()).findAllById(anyList());
    }

    @Test
    void orders_mapNumberAmountAndStatuses() {
        when(orderRepository.findByCustomerIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(List.of(sampleOrder()));

        ResponseEntity<List<OrderResponse>> resp = controller.myOrders();

        assertEquals(200, resp.getStatusCode().value());
        List<OrderResponse> body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        OrderResponse dto = body.get(0);
        assertEquals("ORD20260901100000123", dto.getOrderNumber());
        assertEquals(new BigDecimal("99.00"), dto.getTotalAmount());
        assertEquals("PAID", dto.getStatus());
        assertEquals("PAID", dto.getPaymentStatus());
        assertEquals("buyer@example.com", dto.getCustomerEmail());
    }
}
