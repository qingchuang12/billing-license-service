package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.UserRefundResponse;
import com.billing.license.entity.Currency;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AccountRefundService 测试（plan-4.1 用户端自助退款）。
 *
 * <p>覆盖三道闸门与一条主干：归属校验（越权按不存在返回）→ 限流 → 资格/折算 → 复用 AdminService 退款链路。
 * 折算数学的精确取值由 {@code RefundPolicyTest} 用固定时钟覆盖，此处只验证**接线**（是否把折算额传下去、
 * 是否按实际结果回报），故金额断言用区间而非钉死具体数值，避免随时钟漂移。
 */
class AccountRefundServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String ORDER_NO = "ORD-20260923-0001";

    private OrderRepository orderRepository;
    private LicenseRepository licenseRepository;
    private AdminService adminService;
    private RateLimitService rateLimitService;

    private AccountRefundService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        licenseRepository = mock(LicenseRepository.class);
        adminService = mock(AdminService.class);
        rateLimitService = mock(RateLimitService.class);
        service = new AccountRefundService(
            orderRepository, licenseRepository, adminService, rateLimitService, new BillingProperties());
    }

    private Order paidOrder(UUID customerId) {
        return Order.builder()
            .id(UUID.randomUUID())
            .orderNumber(ORDER_NO)
            .customerId(customerId)
            .email("buyer@example.com")
            .totalAmount(new BigDecimal("365.00"))
            .currency(Currency.USD)
            .status(Order.OrderStatus.PAID)
            .paymentStatus(Order.PaymentStatus.PAID)
            .build();
    }

    private License activeLicense() {
        // 用「签发 = 100 天前、到期 = 266 天后」构造：总 366 天、剩 265 天，
        // 两侧截断稳定（ε < 1 天不影响取整），折算额必落在 (0, 实付额) 内
        LocalDateTime now = LocalDateTime.now();
        License license = new License();
        license.setStatus(License.LicenseStatus.ACTIVE);
        license.setIssuedAt(now.minusDays(100));
        license.setExpiresAt(now.plusDays(266));
        return license;
    }

    private OrderResponse refundResult(String paymentStatus) {
        return OrderResponse.builder()
            .orderNumber(ORDER_NO)
            .totalAmount(new BigDecimal("365.00"))
            .paymentStatus(paymentStatus)
            .build();
    }

    @Test
    void requestRefund_passesProratedAmountToAdminService_andReportsPartial() {
        Order order = paidOrder(USER_ID);
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(order));
        when(licenseRepository.findByOrderId(order.getId())).thenReturn(List.of(activeLicense()));
        when(adminService.refundOrder(eq(ORDER_NO), eq("买错了版本"), any(BigDecimal.class)))
            .thenReturn(refundResult("PARTIALLY_REFUNDED"));

        UserRefundResponse response = service.requestRefund(USER_ID, ORDER_NO, "买错了版本");

        // 折算额必须传下去（而不是让 AdminService 按全额退）
        ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(adminService).refundOrder(eq(ORDER_NO), eq("买错了版本"), captor.capture());
        BigDecimal passed = captor.getValue();
        assertTrue(passed.signum() > 0, "折算额应为正");
        assertTrue(passed.compareTo(new BigDecimal("365.00")) < 0, "未用完整个周期应为部分退款");
        // 回报口径与实际结果一致
        assertFalse(response.isFullRefund());
        assertEquals("PARTIALLY_REFUNDED", response.getPaymentStatus());
        assertEquals(passed, response.getRefundedAmount());
        verify(rateLimitService).checkUserRefund(USER_ID);
    }

    @Test
    void requestRefund_reportsFullRefund_whenAdminServiceDegradedToFull() {
        Order order = paidOrder(USER_ID);
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(order));
        when(licenseRepository.findByOrderId(order.getId())).thenReturn(List.of(activeLicense()));
        // 降级：申请的是折算额，实际退了全额
        when(adminService.refundOrder(eq(ORDER_NO), any(), any(BigDecimal.class)))
            .thenReturn(refundResult("REFUNDED"));

        UserRefundResponse response = service.requestRefund(USER_ID, ORDER_NO, null);

        assertTrue(response.isFullRefund());
        assertEquals("REFUNDED", response.getPaymentStatus());
        // 回报的是实际退还的全额，而不是申请时的折算额
        assertEquals(new BigDecimal("365.00"), response.getRefundedAmount());
    }

    @Test
    void requestRefund_throwsOrderNotFound_forForeignOrder_andSkipsRateLimit() {
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(paidOrder(OTHER_ID)));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.requestRefund(USER_ID, ORDER_NO, null));

        // 不泄露他人订单存在性：越权与不存在同码
        assertEquals("ORDER_NOT_FOUND", ex.getErrorCode());
        verify(rateLimitService, never()).checkUserRefund(any());
        verify(adminService, never()).refundOrder(anyString(), any(), any());
    }

    @Test
    void requestRefund_throwsOrderNotFound_whenCustomerIdNull() {
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(paidOrder(null)));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.requestRefund(USER_ID, ORDER_NO, null));

        assertEquals("ORDER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void requestRefund_throwsRefundLimit_whenRateLimited() {
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(paidOrder(USER_ID)));
        when(rateLimitService.checkUserRefund(USER_ID))
            .thenThrow(new RateLimitService.RateLimitExceededException("user-refund", USER_ID.toString(), 6, 5));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.requestRefund(USER_ID, ORDER_NO, null));

        assertEquals("REFUND_LIMIT", ex.getErrorCode());
        verify(adminService, never()).refundOrder(anyString(), any(), any());
    }

    @Test
    void requestRefund_throwsAlreadyRefunded_forPartiallyRefundedOrder() {
        Order order = paidOrder(USER_ID);
        order.markPartiallyRefunded();
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.requestRefund(USER_ID, ORDER_NO, null));

        assertEquals("ALREADY_REFUNDED", ex.getErrorCode());
        verify(adminService, never()).refundOrder(anyString(), any(), any());
    }

    @Test
    void requestRefund_throwsNotRefundable_whenNoLicenseIssued() {
        Order order = paidOrder(USER_ID);
        when(orderRepository.findByOrderNumber(ORDER_NO)).thenReturn(Optional.of(order));
        when(licenseRepository.findByOrderId(order.getId())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.requestRefund(USER_ID, ORDER_NO, null));

        assertEquals("NOT_REFUNDABLE", ex.getErrorCode());
        verify(adminService, never()).refundOrder(anyString(), any(), any());
    }
}
