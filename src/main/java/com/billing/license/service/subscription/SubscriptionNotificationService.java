package com.billing.license.service.subscription;

import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.Order;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.notification.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 订阅状态通知：发给买家；扣款失败/到期同时通知运营。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionNotificationService {

    private final OrderRepository orderRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final EmailNotificationService emailNotificationService;

    public void notifyStatus(UUID orderId, String statusZh, String statusEn, String detail, boolean notifyOps) {
        try {
            Order order = orderId == null ? null : orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return;
            }
            if (order.getEmail() != null && !order.getEmail().isEmpty()) {
                String locale = checkoutSessionRepository.findByOrderNumber(order.getOrderNumber())
                    .map(CheckoutSession::getLocale).orElse(null);
                emailNotificationService.sendSubscriptionStatusEmail(
                    order.getEmail(), order.getOrderNumber(), statusZh, statusEn, detail, locale);
            }
            if (notifyOps) {
                emailNotificationService.sendOpsAlertEmail(
                    order.getOrderNumber(), statusZh, statusEn, detail);
            }
        } catch (Exception e) {
            log.warn("订阅状态通知发送失败：orderId={}", orderId, e);
        }
    }
}
