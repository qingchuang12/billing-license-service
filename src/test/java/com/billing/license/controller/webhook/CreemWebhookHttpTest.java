package com.billing.license.controller.webhook;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.PaymentLedgerEntry;
import com.billing.license.entity.Product;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.repository.PaymentLedgerRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 渠道回调走**真实 HTTP**（随机端口起 Tomcat + 真实 socket + Jackson 反序列化）的自动化回归。
 *
 * <p>其余 WebhookController 用例直接把解析好的 {@code WebhookPayload} 交给控制器，绕过了
 * 「JSON 数字 → BigDecimal」这一层。真实渠道（Creem）的金额是 JSON number（最小货币单位），
 * 一旦解析层按字符串假设写死，线上表现就是金额错位或直接 4xx，而 mock 测试全绿。
 *
 * <p>本用例自算 HMAC 签名、以 JSON 数字下发退款额，断言金额精确落地、订单置退款——全程不经
 * 任何 mock 的渠道策略，也不绕过 serde 与过滤器链。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        // 只在本用例启用 Creem：其余测试上下文的渠道判定不受影响
        "payment.enabled-channels=CREEM",
        "payment.creem.api-key=test-only-creem-api-key",
        "payment.creem.webhook-secret=test-only-creem-webhook-secret"
    })
@ActiveProfiles("test")
class CreemWebhookHttpTest {

    private static final String WEBHOOK_SECRET = "test-only-creem-webhook-secret";

    @LocalServerPort private int port;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentLedgerRepository ledgerRepository;
    @Autowired private PaymentEventRepository paymentEventRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        // 按外键依赖反向清理：收款用例会签发兑换码 / License 并建订阅记录，
        // 只删订单会被 redeem_codes.product_id、licenses.order_id 等外键挡住。
        for (String table : new String[]{
            "payment_ledger_entries", "payment_events", "payment_attempts",
            "redeem_codes", "license_events", "licenses", "fulfillment_events",
            "subscriptions", "order_items", "checkout_sessions", "orders", "products"}) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    @Test
    @DisplayName("refund.created 以 JSON 数字下发退款额：金额精确入账、订单置退款")
    void refundCreated_withNumericAmount_settlesPreciselyOverRealHttp() throws Exception {
        Order order = orderRepository.save(Order.builder()
            .orderNumber("ORD-HTTP-1").customerId(UUID.randomUUID())
            .totalAmount(new BigDecimal("99.00")).currency(Currency.USD)
            .status(Order.OrderStatus.PAID).paymentStatus(Order.PaymentStatus.PAID)
            .build());

        // refund_amount 是 JSON number（2000 分 = 20.00 美元），刻意不用字符串
        String body = "{\"id\":\"evt_refund_1\",\"eventType\":\"refund.created\",\"object\":{"
            + "\"id\":\"ref_1\",\"status\":\"succeeded\",\"refund_amount\":2000,"
            + "\"refund_currency\":\"USD\",\"checkout\":{\"request_id\":\"ORD-HTTP-1\"},"
            + "\"transaction\":{\"id\":\"txn_http_1\"}}}";

        HttpResponse<String> resp = post(body, hmacHex(body));
        assertEquals(200, resp.statusCode(), "回调应被接受：" + resp.body());

        Order after = orderRepository.findByOrderNumber("ORD-HTTP-1").orElseThrow();
        assertEquals(Order.PaymentStatus.PARTIALLY_REFUNDED, after.getPaymentStatus(),
            "退 20.00 / 订单 99.00 属部分退款，不得直接置全额退款");

        List<PaymentLedgerEntry> entries =
            ledgerRepository.findByOrderIdStrOrderByOccurredAtDesc(order.getId().toString());
        PaymentLedgerEntry refund = entries.stream()
            .filter(e -> e.getEntryType() == PaymentLedgerEntry.EntryType.REFUND)
            .findFirst().orElse(null);
        assertNotNull(refund, "退款必须落资金分录");
        assertEquals(0, refund.getAmount().compareTo(new BigDecimal("20.00")),
            "JSON 数字 2000 分必须精确换算为 20.00，不得被当成 2000 元或丢精度");
        assertEquals(Currency.USD, refund.getCurrency());
    }

    @Test
    @DisplayName("签名不符的回调在真实 HTTP 层被拒（401），不产生任何账务")
    void refundCreated_withWrongSignature_isRejected() throws Exception {
        orderRepository.save(Order.builder()
            .orderNumber("ORD-HTTP-2").customerId(UUID.randomUUID())
            .totalAmount(new BigDecimal("99.00")).currency(Currency.USD)
            .status(Order.OrderStatus.PAID).paymentStatus(Order.PaymentStatus.PAID)
            .build());
        String body = "{\"id\":\"evt_refund_2\",\"eventType\":\"refund.created\",\"object\":{"
            + "\"status\":\"succeeded\",\"refund_amount\":2000,\"refund_currency\":\"USD\","
            + "\"checkout\":{\"request_id\":\"ORD-HTTP-2\"}}}";

        HttpResponse<String> resp = post(body, hmacHex(body + "tampered"));
        assertEquals(401, resp.statusCode(), "验签失败必须拒绝");

        assertEquals(Order.PaymentStatus.PAID,
            orderRepository.findByOrderNumber("ORD-HTTP-2").orElseThrow().getPaymentStatus(),
            "验签失败不得改动订单状态");
    }

    @Test
    @DisplayName("订阅首充 checkout.completed：订单实际成交渠道回填 CREEM")
    void subscriptionFirstCharge_backfillsOrderPaymentProvider() throws Exception {
        Order order = prepareSubscriptionOrder("ORD-SUB-HTTP-1");

        String body = firstChargeBody("evt_sub_1", "ORD-SUB-HTTP-1");
        HttpResponse<String> resp = post(body, hmacHex(body));
        assertEquals(200, resp.statusCode(), "订阅首充回调应被接受：" + resp.body());

        Order after = orderRepository.findByOrderNumber(order.getOrderNumber()).orElseThrow();
        assertEquals(Order.PaymentStatus.PAID, after.getPaymentStatus(), "首充应置已支付");
        assertEquals(PaymentMethod.CREEM, after.getPaymentProvider(),
            "订单实际成交渠道必须落库，否则管理端退款与展示只能靠支付尝试表兜底");
    }

    @Test
    @DisplayName("订阅续期 subscription.paid：逐期入账、周期前进、不重复发货与签码")
    void subscriptionRenewal_recordsChargeWithoutDuplicateFulfillment() throws Exception {
        Order order = prepareSubscriptionOrder("ORD-SUB-HTTP-2");
        String first = firstChargeBody("evt_sub_2", "ORD-SUB-HTTP-2");
        assertEquals(200, post(first, hmacHex(first)).statusCode(), "首充应被接受");

        String renewal = "{\"id\":\"evt_sub_2_paid\",\"eventType\":\"subscription.paid\",\"object\":{"
            + "\"id\":\"sub_ORD-SUB-HTTP-2\",\"status\":\"active\","
            + "\"metadata\":{\"order_id\":\"ORD-SUB-HTTP-2\"},"
            + "\"last_transaction\":{\"id\":\"tran_sub_2b\",\"amount\":1900,\"currency\":\"USD\"},"
            + "\"current_period_start_date\":\"2026-11-30T00:00:00Z\","
            + "\"current_period_end_date\":\"2026-12-30T00:00:00Z\"}}";
        HttpResponse<String> resp = post(renewal, hmacHex(renewal));
        assertEquals(200, resp.statusCode(), "续期回调应被接受：" + resp.body());

        List<PaymentLedgerEntry> charges = ledgerRepository
            .findByOrderIdStrOrderByOccurredAtDesc(order.getId().toString()).stream()
            .filter(e -> e.getEntryType() == PaymentLedgerEntry.EntryType.CHARGE)
            .toList();
        assertEquals(2, charges.size(), "首充与续期必须各落一条收款流水，不能合并也不能漏记");

        assertEquals(1, count("select count(*) from redeem_codes"),
            "续期不得重复签发兑换码（首充已签一次）");
        assertEquals(1, count("select count(*) from fulfillment_events"),
            "续期不得重复登记履约事件，否则会重复发货");
        assertEquals(java.time.LocalDateTime.of(2026, 12, 30, 0, 0),
            jdbcTemplate.queryForObject("select current_period_end from subscriptions",
                java.time.LocalDateTime.class),
            "订阅周期必须按渠道下发的期末前进");
    }

    @Test
    @DisplayName("期末取消可逆：scheduled_cancel 置标记，渠道同步事件按 canceled_at 复位/保持")
    void subscriptionScheduledCancel_isReversibleBySyncEvent() throws Exception {
        prepareSubscriptionOrder("ORD-SUB-HTTP-4");
        String first = firstChargeBody("evt_sub_4", "ORD-SUB-HTTP-4");
        assertEquals(200, post(first, hmacHex(first)).statusCode(), "首充应被接受");

        // 1) 安排期末取消：权益保留到期末，标记置位
        String scheduled = "{\"id\":\"evt_sub_4_cancel\",\"eventType\":\"subscription.scheduled_cancel\","
            + "\"object\":{\"id\":\"sub_ORD-SUB-HTTP-4\",\"status\":\"active\","
            + "\"metadata\":{\"order_id\":\"ORD-SUB-HTTP-4\"},"
            + "\"canceled_at\":\"2026-11-05T10:00:00.000Z\","
            + "\"current_period_start_date\":\"2026-10-30T00:00:00Z\","
            + "\"current_period_end_date\":\"2026-11-30T00:00:00Z\"}}";
        assertEquals(200, post(scheduled, hmacHex(scheduled)).statusCode(), "期末取消事件应被接受");
        assertEquals(Boolean.TRUE, cancelAtPeriodEnd(), "期末取消必须置标记");
        assertEquals("ACTIVE", subscriptionStatus(), "期末取消不是终态，权益保留到期末");
        assertEquals(1, count("select count(*) from redeem_codes"), "取消不得回收已发兑换码");

        // 2) 用户撤销取消（渠道 canceled_at 归 null），只发同步事件——本地必须复位
        String resumed = "{\"id\":\"evt_sub_4_resume\",\"eventType\":\"subscription.active\","
            + "\"object\":{\"id\":\"sub_ORD-SUB-HTTP-4\",\"status\":\"active\","
            + "\"metadata\":{\"order_id\":\"ORD-SUB-HTTP-4\"},\"canceled_at\":null,"
            + "\"current_period_start_date\":\"2026-10-30T00:00:00Z\","
            + "\"current_period_end_date\":\"2026-11-30T00:00:00Z\"}}";
        assertEquals(200, post(resumed, hmacHex(resumed)).statusCode());
        assertEquals(Boolean.FALSE, cancelAtPeriodEnd(),
            "撤销取消后必须复位，否则到下次扣款前页面一直显示将取消");

        // 3) 反向保护：同步事件仍带 canceled_at 时不得误复位
        String stillScheduled = "{\"id\":\"evt_sub_4_update\",\"eventType\":\"subscription.update\","
            + "\"object\":{\"id\":\"sub_ORD-SUB-HTTP-4\",\"status\":\"active\","
            + "\"metadata\":{\"order_id\":\"ORD-SUB-HTTP-4\"},"
            + "\"canceled_at\":\"2026-11-05T10:00:00.000Z\","
            + "\"current_period_start_date\":\"2026-10-30T00:00:00Z\","
            + "\"current_period_end_date\":\"2026-11-30T00:00:00Z\"}}";
        assertEquals(200, post(stillScheduled, hmacHex(stillScheduled)).statusCode());
        assertEquals(Boolean.TRUE, cancelAtPeriodEnd(), "渠道仍标记已取消时不得复位");
    }

    private Boolean cancelAtPeriodEnd() {
        return jdbcTemplate.queryForObject("select cancel_at_period_end from subscriptions", Boolean.class);
    }

    private String subscriptionStatus() {
        return jdbcTemplate.queryForObject("select status from subscriptions", String.class);
    }

    private Order prepareSubscriptionOrder(String orderNumber) {
        Product product = productRepository.save(Product.builder()
            .sku("sub-pro-" + orderNumber).name("Pro Monthly")
            .price(new BigDecimal("19.00")).priceUsd(new BigDecimal("19.00"))
            .billingCycle(Product.BillingCycle.MONTHLY).active(true).build());
        Order order = Order.builder()
            .orderNumber(orderNumber).customerId(UUID.randomUUID())
            .totalAmount(new BigDecimal("19.00")).currency(Currency.USD)
            .status(Order.OrderStatus.PENDING).paymentStatus(Order.PaymentStatus.UNPAID)
            .build();
        order.setOrderItems(List.of(OrderItem.builder().order(order).product(product).quantity(1)
            .unitPrice(new BigDecimal("19.00")).totalPrice(new BigDecimal("19.00")).build()));
        return orderRepository.save(order);
    }

    private static String firstChargeBody(String eventId, String orderNumber) {
        return "{\"id\":\"" + eventId + "\",\"eventType\":\"checkout.completed\",\"object\":{"
            + "\"id\":\"ch_" + orderNumber + "\",\"status\":\"completed\",\"request_id\":\"" + orderNumber + "\","
            + "\"order\":{\"amount\":1900,\"currency\":\"USD\",\"transaction\":\"tran_" + orderNumber + "\"},"
            + "\"subscription\":{\"id\":\"sub_" + orderNumber + "\","
            + "\"current_period_start_date\":\"2026-10-30T00:00:00Z\","
            + "\"current_period_end_date\":\"2026-11-30T00:00:00Z\"}}}";
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value != null ? value : 0;
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/webhooks/creem"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("creem-signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String payload) throws Exception {
        Mac mac = Mac.getInstance("HMACSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HMACSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
