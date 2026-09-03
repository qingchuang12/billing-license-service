package com.billing.license.service;

import com.billing.license.dto.CheckoutRequest;
import com.billing.license.dto.CheckoutResponse;
import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.Order;
import com.billing.license.entity.Payment;
import com.billing.license.entity.Product;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.repository.PaymentRepository;
import com.billing.license.service.payment.PaymentService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentResponse;
import com.billing.license.service.payment.strategy.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * CheckoutService 单元测试 - 覆盖统一收银台创建、区域判定、支付方式选择、状态查询
 */
class CheckoutServiceTest {

    private ProductRepository productRepository;
    private OrderRepository orderRepository;
    private CheckoutSessionRepository checkoutSessionRepository;
    private PaymentServiceFactory paymentServiceFactory;
    private PaymentService paymentService;
    private LicenseService licenseService;
    private RedeemCodeService redeemCodeService;
    private LicenseRepository licenseRepository;
    private RedeemCodeRepository redeemCodeRepository;
    private PaymentRepository paymentRepository;
    private CheckoutService checkoutService;

    private Product product;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        orderRepository = mock(OrderRepository.class);
        checkoutSessionRepository = mock(CheckoutSessionRepository.class);
        paymentServiceFactory = mock(PaymentServiceFactory.class);
        paymentService = mock(PaymentService.class);
        licenseService = mock(LicenseService.class);
        redeemCodeService = mock(RedeemCodeService.class);
        licenseRepository = mock(LicenseRepository.class);
        redeemCodeRepository = mock(RedeemCodeRepository.class);
        paymentRepository = mock(PaymentRepository.class);

        // B13：getStatus 优先查已签发结果，未命中才签发；默认返回空列表避免 NPE
        when(licenseRepository.findByOrderId(any())).thenReturn(List.of());
        when(redeemCodeRepository.findByOrderId(anyString())).thenReturn(List.of());

        checkoutService = new CheckoutService(
            productRepository, orderRepository, checkoutSessionRepository,
            paymentServiceFactory, paymentService, licenseService, redeemCodeService,
            licenseRepository, redeemCodeRepository,
            paymentRepository,
            mock(com.billing.license.service.risk.RateLimitService.class));

        product = Product.builder().id(UUID.randomUUID()).sku("pro")
            .name("Pro").price(new BigDecimal("99.00")).active(true).build();
    }

    @Test
    void createCheckout_domestic_shouldReturnDomesticMethods() {
        when(productRepository.findBySku("pro")).thenReturn(Optional.of(product));
        when(paymentServiceFactory.getDomesticMethods()).thenReturn(List.of(PaymentMethod.ALIPAY, PaymentMethod.WECHAT_PAY));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));

        CheckoutRequest req = CheckoutRequest.builder()
            .productId("pro").currency("CNY").locale("zh-CN").build();
        CheckoutResponse resp = checkoutService.createCheckout(req);

        assertNotNull(resp.getCheckoutId());
        assertTrue(resp.getPaymentMethods().contains("ALIPAY"));
        assertTrue(resp.getPaymentMethods().contains("WECHAT_PAY"));
    }

    @Test
    void createCheckout_international_shouldReturnInternationalMethods() {
        when(productRepository.findBySku("pro")).thenReturn(Optional.of(product));
        when(paymentServiceFactory.getInternationalMethods()).thenReturn(List.of(PaymentMethod.STRIPE, PaymentMethod.PADDLE, PaymentMethod.PAYPAL));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));

        CheckoutRequest req = CheckoutRequest.builder()
            .productId("pro").currency("USD").locale("en-US").build();
        CheckoutResponse resp = checkoutService.createCheckout(req);

        assertTrue(resp.getPaymentMethods().contains("STRIPE"));
        assertTrue(resp.getPaymentMethods().contains("PAYPAL"));
    }

    @Test
    void createCheckout_shouldThrow_whenProductInactive() {
        Product inactive = Product.builder().sku("pro").active(false).build();
        when(productRepository.findBySku("pro")).thenReturn(Optional.of(inactive));

        assertThrows(RuntimeException.class, () ->
            checkoutService.createCheckout(CheckoutRequest.builder().productId("pro").currency("CNY").build()));
    }

    @Test
    void createCheckout_shouldUseRegionPrice_b19() {
        // 双币种定价（B19）：国内取 CNY/priceCny，国际取 USD/priceUsd
        Product dual = Product.builder().id(UUID.randomUUID()).sku("pro")
            .name("Pro").price(new BigDecimal("99.00"))
            .priceCny(new BigDecimal("712.80")).priceUsd(new BigDecimal("99.00")).active(true).build();
        when(productRepository.findBySku("pro")).thenReturn(Optional.of(dual));
        when(paymentServiceFactory.getDomesticMethods()).thenReturn(List.of(PaymentMethod.ALIPAY));
        when(paymentServiceFactory.getInternationalMethods()).thenReturn(List.of(PaymentMethod.STRIPE));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);

        checkoutService.createCheckout(
            CheckoutRequest.builder().productId("pro").currency("CNY").locale("zh-CN").build());
        verify(orderRepository).save(captor.capture());
        assertEquals(new BigDecimal("712.80"), captor.getValue().getTotalAmount());
        assertEquals("CNY", captor.getValue().getCurrency());

        checkoutService.createCheckout(
            CheckoutRequest.builder().productId("pro").currency("USD").locale("en-US").build());
        verify(orderRepository, times(2)).save(captor.capture());
        assertEquals(new BigDecimal("99.00"), captor.getValue().getTotalAmount());
        assertEquals("USD", captor.getValue().getCurrency());
    }

    @Test
    void selectProvider_shouldCreatePaymentAndReturnMode() {
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1")
            .totalAmount(new BigDecimal("99.00")).currency("CNY").build();
        CheckoutSession session = CheckoutSession.builder()
            .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(mock(com.billing.license.service.payment.strategy.PaymentStrategy.class));
        PaymentResponse pr = new PaymentResponse();
        pr.setPayUrl("https://pay.example.com/x");
        when(paymentService.createPayment(any(Order.class), eq(PaymentMethod.ALIPAY))).thenReturn(pr);
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));

        SelectProviderRequest sel = SelectProviderRequest.builder().provider("alipay").build();
        CheckoutResponse resp = checkoutService.selectProvider("chk_1", sel);

        assertEquals("redirect", resp.getPaymentMode());
        assertEquals("https://pay.example.com/x", resp.getPayUrl());
    }

    /**
     * w17：Webhook 丢失时，getStatus 应主动查渠道补偿——渠道确认已支付则置 PAID。
     */
    @Test
    void getStatus_shouldCompensateFromChannel_whenWebhookLost() {
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1").build();
        CheckoutSession session = CheckoutSession.builder()
            .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1")
            .status(CheckoutSession.Status.PENDING).machineId("M1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        // 模拟已落库的 Payment（由 selectProvider 写入），渠道为 ALIPAY
        Payment payment = Payment.builder().paymentId("alipay_ORD-1").method("ALIPAY").build();
        when(paymentRepository.findByOrderIdStr(order.getId().toString())).thenReturn(Optional.of(payment));
        // 渠道查询返回 SUCCESS → 补偿置 PAID
        when(paymentService.queryPaymentStatus("alipay_ORD-1", PaymentMethod.ALIPAY))
            .thenReturn(PaymentStatus.SUCCESS);
        when(checkoutSessionRepository.save(any(CheckoutSession.class))).thenAnswer(i -> i.getArgument(0));
        when(licenseRepository.findByOrderId(any())).thenReturn(List.of());

        CheckoutResponse resp = checkoutService.getStatus("chk_1");

        assertEquals("PAID", resp.getStatus());
        // 验证确实触发了一次渠道查询补偿
        verify(paymentService).queryPaymentStatus("alipay_ORD-1", PaymentMethod.ALIPAY);
    }

    @Test
    void selectProvider_shouldPersistPaymentProviderOnOrder() {
        // C2：选定渠道后必须落库 order.paymentProvider，否则管理端退款 resolveMethod 恒为 null
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1")
            .totalAmount(new BigDecimal("99.00")).currency("CNY").build();
        CheckoutSession session = CheckoutSession.builder()
            .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(paymentServiceFactory.getStrategy(PaymentMethod.ALIPAY)).thenReturn(mock(com.billing.license.service.payment.strategy.PaymentStrategy.class));
        PaymentResponse pr = new PaymentResponse();
        pr.setPayUrl("https://pay.example.com/x");
        when(paymentService.createPayment(any(Order.class), eq(PaymentMethod.ALIPAY))).thenReturn(pr);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        checkoutService.selectProvider("chk_1", SelectProviderRequest.builder().provider("alipay").build());

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, atLeastOnce()).save(captor.capture());
        assertEquals("ALIPAY", captor.getValue().getPaymentProvider(),
                "订单必须记下所选支付渠道，供退款链路解析");
    }

    @Test
    void getStatus_shouldReturnRedeemCode_whenPaidAndNoMachine() {
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1").build();
        CheckoutSession session = CheckoutSession.builder()
            .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1")
            .status(CheckoutSession.Status.PAID).build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(redeemCodeService.generateCode("ORD-1")).thenReturn("CODE-ABCD-1234");

        CheckoutResponse resp = checkoutService.getStatus("chk_1");

        assertEquals("PAID", resp.getStatus());
        assertEquals("CODE-ABCD-1234", resp.getRedeemCode());
    }

    @Test
    void getStatus_shouldReturnLicense_whenPaidAndMachineBound() {
        Order order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-1").build();
        CheckoutSession session = CheckoutSession.builder()
            .checkoutId("chk_1").orderId(order.getId()).orderNumber("ORD-1")
            .status(CheckoutSession.Status.PAID).machineId("M1").build();

        when(checkoutSessionRepository.findByCheckoutId("chk_1")).thenReturn(Optional.of(session));
        when(licenseService.issueLicensesForOrder(order.getId()))
            .thenReturn(List.of(com.billing.license.dto.LicenseResponse.builder().signedToken("tok.abc").build()));

        CheckoutResponse resp = checkoutService.getStatus("chk_1");

        assertEquals("tok.abc", resp.getLicense());
    }
}
