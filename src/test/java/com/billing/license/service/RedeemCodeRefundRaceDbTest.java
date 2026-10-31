package com.billing.license.service;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.Currency;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Product;
import com.billing.license.entity.RedeemCode;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 撤码与核销的并发竞态（硬约束 14）**真实库证伪**。
 *
 * <p>单测摸不到这件事：两条路径各自 mock 出来的仓库永远读到 UNUSED。真实并发下若撤码不持行锁，
 * 会出现「退款把码置 REVOKED，同时核销把它覆盖回 USED 并签发一张可用的证」——钱已退、证还在。
 *
 * <p>本用例反复并发跑两条路径，只断言语义不变式，不假设谁先谁后：
 * 码为 REVOKED 时不得存在该订单下状态可用的 License；码为 USED 时必须真的签出了 License。
 */
@SpringBootTest
@ActiveProfiles("test")
class RedeemCodeRefundRaceDbTest {

    private static final int ROUNDS = 8;

    @Autowired private RefundSettlementService refundSettlementService;
    @Autowired private RedeemCodeService redeemCodeService;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private RedeemCodeRepository redeemCodeRepository;
    @Autowired private LicenseRepository licenseRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        licenseRepository.deleteAll();
        redeemCodeRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("退款撤码与核销并发：不得出现「码已撤销却仍拿到可用 License」")
    void concurrentRefundAndRedeem_neverLeavesUsableLicenseAfterRevoke() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                String suffix = "-" + i;
                Product product = productRepository.save(Product.builder()
                    .sku("race" + suffix).name("Race").price(new BigDecimal("99.00"))
                    .priceCny(new BigDecimal("99.00")).priceUsd(new BigDecimal("14.00"))
                    .licenseDurationDays(30).active(true).build());
                Order order = orderRepository.save(Order.builder()
                    .orderNumber("ORD-RACE" + suffix)
                    .customerId(java.util.UUID.randomUUID())
                    .totalAmount(new BigDecimal("99.00")).currency(Currency.CNY)
                    .status(Order.OrderStatus.PAID).paymentStatus(Order.PaymentStatus.PAID)
                    .build());
                RedeemCode code = redeemCodeRepository.save(RedeemCode.builder()
                    .code("RC-RACE" + suffix).product(product)
                    .orderId(order.getOrderNumber())
                    .status(RedeemCode.RedeemCodeStatus.UNUSED).currentUses(0).build());

                CountDownLatch start = new CountDownLatch(1);
                Future<?> refund = pool.submit(() -> {
                    start.await();
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        Order managed = orderRepository.findByOrderNumber(order.getOrderNumber())
                            .orElseThrow();
                        refundSettlementService.settle(managed, PaymentMethod.ALIPAY,
                            "rf" + suffix, null, "CNY", "race");
                    });
                    return null;
                });
                Future<?> redeem = pool.submit(() -> {
                    start.await();
                    try {
                        redeemCodeService.redeemCode(RedeemCodeRequest.builder()
                            .code(code.getCode()).customerEmail("race@example.com").build());
                    } catch (RuntimeException expected) {
                        // 竞态下核销被拒属正常分支（码已撤销/已用），这里只关心最终状态
                    }
                    return null;
                });

                start.countDown();
                refund.get(30, TimeUnit.SECONDS);
                redeem.get(30, TimeUnit.SECONDS);

                RedeemCode after = redeemCodeRepository.findByOrderId(order.getOrderNumber()).get(0);
                List<License> licenses = licenseRepository.findByOrderId(order.getId());
                if (after.getStatus() == RedeemCode.RedeemCodeStatus.REVOKED) {
                    assertTrue(licenses.stream().noneMatch(l -> l.getStatus() == License.LicenseStatus.ACTIVE),
                        "第 " + i + " 轮：码已撤销却仍存在 ACTIVE License（退款后仍可用）");
                } else if (after.getStatus() == RedeemCode.RedeemCodeStatus.USED) {
                    assertFalse(licenses.isEmpty(), "第 " + i + " 轮：码已核销却没有签发 License");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
