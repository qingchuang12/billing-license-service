package com.billing.license.service;

import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.*;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CheckoutService.selectProvider 支付失败反馈测试（H6）。
 * 渠道创建支付失败时，必须向前端显式抛出 PAYMENT_CREATE_FAILED，而非静默返回空 payUrl。
 */
class CheckoutServicePaymentFailureTest {

    private ProductRepository productRepository;
    private OrderRepository orderRepository;
    private CheckoutSessionRepository checkoutSessionRepository;
    private PaymentServiceFactory paymentServiceFactory;
    private PaymentService paymentService;
    private CheckoutService checkoutService;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        orderRepository = mock(OrderRepository.class);
        checkoutSessionRepository = mock(CheckoutSessionRepository.class);
        paymentServiceFactory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        checkoutService = new CheckoutService(
                productRepository, orderRepository, checkoutSessionRepository,
                paymentServiceFactory, paymentService, mock(LicenseService.class),
                mock(RedeemCodeService.class), mock(LicenseRepository.class),
                mock(RedeemCodeRepository.class), mock(PaymentRepository.class),
                mock(RateLimitService.class), mock(CustomerIdentityService.class));
    }

    @Test
    void selectProvider_shouldThrow_whenPaymentCreationFailed() {
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1")
                .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).build();
        CheckoutSession session = CheckoutSession.builder()
                .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(mock(PaymentStrategy.class));

        PaymentResponse failed = new PaymentResponse();
        failed.setStatus(PaymentStatus.FAILED.name());
        failed.setErrorMessage("支付宝未配置");
        when(paymentService.createPayment(any(Order.class), eq(PaymentMethod.ALIPAY))).thenReturn(failed);
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checkoutService.selectProvider("chk_1", SelectProviderRequest.builder().provider("alipay").build()));
        assertEquals("PAYMENT_CREATE_FAILED", ex.getErrorCode());
    }

    @Test
    void selectProvider_shouldSucceed_whenPaymentCreated() {
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1")
                .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY).build();
        CheckoutSession session = CheckoutSession.builder()
                .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(mock(PaymentStrategy.class));

        PaymentResponse ok = new PaymentResponse();
        ok.setPayUrl("https://pay.example.com/x");
        when(paymentService.createPayment(any(Order.class), eq(PaymentMethod.ALIPAY))).thenReturn(ok);
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));

        assertDoesNotThrow(() ->
                checkoutService.selectProvider("chk_1", SelectProviderRequest.builder().provider("alipay").build()));
    }
}
