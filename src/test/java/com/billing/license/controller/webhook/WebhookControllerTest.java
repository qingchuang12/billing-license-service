package com.billing.license.controller.webhook;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.CheckoutService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStatus;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.payment.util.AmountValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WebhookController 单元测试 - 覆盖统一 Webhook 处理流程：
 * 验签、幂等、金额校验、发货、事件记录（外围接口实现）
 */
class WebhookControllerTest {

    private PaymentServiceFactory factory;
    private PaymentService paymentService;
    private OrderRepository orderRepository;
    private PaymentRepository paymentRepository;
    private LicenseService licenseService;
    private RedeemCodeService redeemCodeService;
    private EmailNotificationService emailService;
    private AmountValidator amountValidator;
    private PaymentEventRepository paymentEventRepository;
    private CheckoutService checkoutService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        factory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        orderRepository = mock(OrderRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        licenseService = mock(LicenseService.class);
        redeemCodeService = mock(RedeemCodeService.class);
        emailService = mock(EmailNotificationService.class);
        amountValidator = mock(AmountValidator.class);
        paymentEventRepository = mock(PaymentEventRepository.class);
        checkoutService = mock(CheckoutService.class);

        controller = new WebhookController();
        setField(controller, "paymentServiceFactory", factory);
        setField(controller, "paymentService", paymentService);
        setField(controller, "orderRepository", orderRepository);
        setField(controller, "paymentRepository", paymentRepository);
        setField(controller, "licenseService", licenseService);
        setField(controller, "redeemCodeService", redeemCodeService);
        setField(controller, "emailNotificationService", emailService);
        setField(controller, "amountValidator", amountValidator);
        setField(controller, "paymentEventRepository", paymentEventRepository);
        setField(controller, "checkoutService", checkoutService);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PaymentStrategy stubStrategy(boolean signatureValid) {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(signatureValid);
        WebhookPayload payload = new WebhookPayload();
        payload.setOrderId("ORD-1");
        payload.setPaymentId("pay_1");
        payload.setTransactionId("txn_1");
        payload.setEventType("payment_success");
        payload.setStatus(PaymentStatus.SUCCESS.name());
        payload.setAmount(new BigDecimal("99.00"));
        payload.setCurrency("CNY");
        when(strategy.parseWebhookPayload(anyString())).thenReturn(payload);
        when(strategy.getPaymentMethod()).thenReturn(PaymentMethod.ALIPAY);
        return strategy;
    }

    @Test
    void processWebhook_shouldReturn401_whenSignatureInvalid() {
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        when(strategy.verifyWebhookSignature(anyString(), any(), any())).thenReturn(false);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "bad-sig", Map.of());
        assertEquals(401, resp.getStatusCode().value());
        verify(paymentEventRepository).save(any(PaymentEvent.class));
    }

    @Test
    void processWebhook_shouldSkip_whenEventAlreadyProcessed() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(PaymentMethod.ALIPAY.name(), "txn_1"))
            .thenReturn(true);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("Already processed", resp.getBody());
        verify(paymentService, never()).updatePaymentStatus(any(), any(), any());
    }

    @Test
    void processWebhook_shouldFulfill_whenAmountValid() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(anyString(), anyString())).thenReturn(false);

        Order order = Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency("CNY").email("u@e.com").build();
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(amountValidator.validateAmount(any(Order.class), any())).thenReturn(true);
        Payment payment = new Payment();
        when(paymentService.updatePaymentStatus(any(), any(), any())).thenReturn(payment);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("Success", resp.getBody());
        // 已绑定机器码 → 签发 License
        verify(licenseService).issueLicense("ORD-1", "M1");
        verify(emailService).sendPaymentSuccessEmail(eq("u@e.com"), anyString(), anyString(), anyDouble(), anyString());
        // 记录支付事件（已处理）
        verify(paymentEventRepository).save(argThat(e -> Boolean.TRUE.equals(e.getProcessed())));
    }

    @Test
    void processWebhook_shouldReject_whenAmountMismatch() {
        PaymentStrategy strategy = stubStrategy(true);
        when(factory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(strategy);
        when(paymentEventRepository.existsByProviderAndEventId(anyString(), anyString())).thenReturn(false);

        Order order = Order.builder().orderNumber("ORD-1").machineCode("M1")
            .totalAmount(new BigDecimal("99.00")).currency("CNY").build();
        when(orderRepository.findByOrderNumber("ORD-1")).thenReturn(Optional.of(order));
        when(amountValidator.validateAmount(any(Order.class), any())).thenReturn(false);

        ResponseEntity<String> resp = controller.processWebhook(
            PaymentMethod.ALIPAY, "payload", "sig", Map.of());
        assertEquals(400, resp.getStatusCode().value());
        verify(licenseService, never()).issueLicense(anyString(), anyString());
    }
}
