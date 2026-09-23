package com.billing.license.controller;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.*;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.*;
import com.billing.license.service.*;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.risk.RateLimitService;
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
    private final AdminService adminService = mock(AdminService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final BillingProperties billingProperties = new BillingProperties();

    private final AccountAssetService service = new AccountAssetService(
            licenseRepository, subscriptionRepository, orderRepository,
            productRepository, userRepository, new OrderService(orderRepository),
            billingProperties);
    private final AccountRefundService accountRefundService = new AccountRefundService(
            orderRepository, licenseRepository, adminService, rateLimitService, billingProperties);
    /** plan-7.0 决策 B6：账号页自助解绑（换机恢复路径） */
    private final LicenseService licenseService = mock(LicenseService.class);
    private final AccountAssetController controller =
            new AccountAssetController(service, accountRefundService, licenseService);

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
                .id(UUID.fromString("33333333-3333-3333-3333-333333333333"))
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

    /** plan-4.1：订单列表回填退款入口依据——可退额由 License 剩余有效期折算而来 */
    @Test
    void orders_fillRefundableAmount_forRefundableOrder() {
        Order order = sampleOrder();
        when(orderRepository.findByCustomerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(order));
        // 订单签发的 License 须挂在订单上（兑换码来源的 License 订单为 null，不参与订单退款）
        License license = sampleLicense();
        license.setOrder(order);
        when(licenseRepository.findByOrderIdIn(anyList())).thenReturn(List.of(license));

        ResponseEntity<List<OrderResponse>> resp = controller.myOrders();

        List<OrderResponse> body = resp.getBody();
        assertNotNull(body);
        assertEquals(1, body.size());
        assertTrue(body.get(0).getRefundable());
        // sampleLicense：issuedAt 2026-09-01、expiresAt 2027-09-01（365 天），
        // 故折算额必然落在 (0, 99.00) 开区间内——不钉死具体时点，避免测试随时钟漂移
        BigDecimal amount = body.get(0).getRefundableAmount();
        assertNotNull(amount);
        assertTrue(amount.signum() > 0, "折算额应为正");
        assertTrue(amount.compareTo(new BigDecimal("99.00")) < 0, "未用完完整周期应为部分退款");
    }

    /** 回归：兑换码签发的 License 无订单（order=null），订单列表折算不得空指针 */
    @Test
    void orders_tolerateLicensesWithoutOrder() {
        when(orderRepository.findByCustomerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(sampleOrder()));
        // sampleLicense 未挂订单，等价于兑换码来源的 License
        when(licenseRepository.findByOrderIdIn(anyList())).thenReturn(List.of(sampleLicense()));

        ResponseEntity<List<OrderResponse>> resp = controller.myOrders();

        assertNotNull(resp.getBody());
        assertEquals(Boolean.FALSE, resp.getBody().get(0).getRefundable());
    }

    /** plan-4.1：无可退依据（未发货/未支付）时 refundable=false 且不回填金额 */
    @Test
    void orders_notRefundable_whenNoLicense() {        when(orderRepository.findByCustomerIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(sampleOrder()));
        when(licenseRepository.findByOrderIdIn(anyList())).thenReturn(List.of());

        ResponseEntity<List<OrderResponse>> resp = controller.myOrders();

        List<OrderResponse> body = resp.getBody();
        assertNotNull(body);
        assertEquals(Boolean.FALSE, body.get(0).getRefundable());
        assertNull(body.get(0).getRefundableAmount());
    }

    /** plan-4.1：用户端退款端点——实退金额与退款后状态取自实际退款结果 */
    @Test
    void refund_returnsActualRefundedAmount() {
        Order order = sampleOrder();
        order.setCustomerId(USER_ID);
        when(orderRepository.findByOrderNumber("ORD20260901100000123")).thenReturn(Optional.of(order));
        when(licenseRepository.findByOrderId(order.getId())).thenReturn(List.of(sampleLicense()));
        when(adminService.refundOrder(eq("ORD20260901100000123"), eq("买错了"), any(BigDecimal.class)))
                .thenReturn(OrderResponse.builder()
                        .orderNumber("ORD20260901100000123")
                        .totalAmount(new BigDecimal("99.00"))
                        .status("PAID")
                        .paymentStatus("PARTIALLY_REFUNDED")
                        .build());

        ResponseEntity<UserRefundResponse> resp = controller.refundOrder(
                "ORD20260901100000123", UserRefundRequest.builder().reason("买错了").build());

        assertEquals(200, resp.getStatusCode().value());
        UserRefundResponse body = resp.getBody();
        assertNotNull(body);
        assertEquals("PARTIALLY_REFUNDED", body.getPaymentStatus());
        assertFalse(body.isFullRefund());
        assertTrue(body.getRefundedAmount().signum() > 0);
        verify(rateLimitService).checkUserRefund(USER_ID);
    }

    /** plan-4.1 安全红线：他人订单按 ORDER_NOT_FOUND 返回，不泄露订单存在性 */
    @Test
    void refund_throwsOrderNotFound_forForeignOrder() {
        Order order = sampleOrder();
        order.setCustomerId(UUID.fromString("99999999-9999-9999-9999-999999999999"));
        when(orderRepository.findByOrderNumber("ORD20260901100000123")).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.refundOrder(
                "ORD20260901100000123", UserRefundRequest.builder().build()));

        assertEquals("ORDER_NOT_FOUND", ex.getErrorCode());
        // 越权请求不应消耗限流配额，也不应触达退款链路
        verify(rateLimitService, never()).checkUserRefund(any());
        verify(adminService, never()).refundOrder(anyString(), any(), any());
    }

    /** plan-7.0 决策 B6：换机恢复路径——账号页自助解绑，归属凭证取登录用户 id */
    @Test
    void unbindMyLicense_passesCurrentUserId_toOwnerScopedUnbind() {
        ResponseEntity<Void> resp = controller.unbindMyLicense("LIC-2F8A-7C31-9D04-B5E6");

        assertEquals(200, resp.getStatusCode().value());
        verify(licenseService).unbindByOwner("LIC-2F8A-7C31-9D04-B5E6", USER_ID);
    }

    @Test
    void unbindMyLicense_propagatesNotFound_forForeignLicense() {
        doThrow(new BusinessException("LICENSE_NOT_FOUND", "License not found"))
                .when(licenseService).unbindByOwner(anyString(), any());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.unbindMyLicense("LIC-FOREIGN"));

        assertEquals("LICENSE_NOT_FOUND", ex.getErrorCode());
    }
}
