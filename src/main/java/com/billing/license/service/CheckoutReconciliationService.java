package com.billing.license.service;

import com.billing.license.entity.CheckoutSession;
import com.billing.license.repository.CheckoutSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收银台后台对账（#50）：不依赖页面轮询的补偿与关单。
 *
 * <p>解决的问题：用户付完款直接关页面、或渠道回调丢失，此前只有前端轮询 {@code getStatus}
 * 才会主动查单，页面一关就永远卡在 PENDING，钱收了货不发。
 *
 * <p>本轮只处理 PENDING（真正创建过渠道交易）且未超出观察窗的会话：
 * <ol>
 *   <li>渠道确认已收款 → 走与轮询相同的证据核对后补偿发货；</li>
 *   <li>会话已过期且渠道确认未收款 → 支持关单的渠道立即关单，防止本地过期后仍被支付；</li>
 *   <li>关单不支持/结果未知 → 保留会话与支付尝试（未对清证据），下一轮继续；</li>
 *   <li>超出观察窗（默认 7 天）→ 不再轮询，但保留行与尝试作为对账证据。</li>
 * </ol>
 *
 * <p>外部 HTTP 全部在 {@link CheckoutService#reconcileSession} 内、事务之外完成；
 * 本类只负责扫描与节流，不写库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutReconciliationService {

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final CheckoutService checkoutService;

    /** 过期后再观察多少小时（覆盖各渠道自身会话有效期：Stripe 24h / PayPal 3h / 支付宝 2h）。 */
    @Value("${billing.checkout-session.reconcile-window-hours:168}")
    private long reconcileWindowHours;

    /** 单轮最多处理多少个会话，避免突发积压占满调度线程。 */
    @Value("${billing.checkout-session.reconcile-batch-size:50}")
    private int reconcileBatchSize;

    @Scheduled(
            initialDelayString = "${billing.checkout-session.reconcile-initial-delay-ms:60000}",
            fixedDelayString = "${billing.checkout-session.reconcile-interval-ms:300000}")
    public void reconcileOpenSessions() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusSeconds(CheckoutService.COMPENSATION_COOLDOWN_SECONDS);
        LocalDateTime notBefore = now.minusHours(Math.max(1, reconcileWindowHours));

        List<CheckoutSession> candidates = checkoutSessionRepository.findReconcileCandidates(
            CheckoutSession.Status.PENDING, staleBefore, notBefore,
            PageRequest.of(0, Math.max(1, reconcileBatchSize)));
        if (candidates.isEmpty()) {
            return;
        }

        int paid = 0;
        int closed = 0;
        int retained = 0;
        int failed = 0;
        for (CheckoutSession session : candidates) {
            try {
                CheckoutService.ReconcileResult result =
                    checkoutService.reconcileSession(session.getCheckoutId(), true);
                switch (result) {
                    case PAID -> paid++;
                    case CLOSED, EXPIRED -> closed++;
                    case UNPAID, UNVERIFIED, CLOSE_UNSUPPORTED, CLOSE_UNKNOWN,
                         NO_ATTEMPT, SKIPPED, ERROR -> retained++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("后台对账异常：checkoutId={}", session.getCheckoutId(), e);
            }
        }
        log.info("后台对账完成：扫描 {}，补偿发货 {}，关单/过期 {}，保留待核 {}，异常 {}",
            candidates.size(), paid, closed, retained, failed);
    }
}
