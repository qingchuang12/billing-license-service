package com.billing.license.service.payment;

import com.billing.license.controller.webhook.WebhookController;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 支付事件人工重放：仅允许「验签通过且未处理」的事件。
 * 重放复用 WebhookController 的幂等/金额/订单校验，不绕过任何发货/退款保护。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventReplayService {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentServiceFactory paymentServiceFactory;
    private final SubscriptionService subscriptionService;
    private final WebhookController webhookController;

    public ResponseEntity<String> replay(UUID eventId) {
        PaymentEvent event = paymentEventRepository.findById(eventId)
            .orElseThrow(() -> new BusinessException("PAYMENT_EVENT_NOT_FOUND", "事件不存在"));
        if (Boolean.TRUE.equals(event.getProcessed())) {
            throw new BusinessException("PAYMENT_EVENT_ALREADY_PROCESSED", "事件已处理，不能重放");
        }
        if (!Boolean.TRUE.equals(event.getSignatureValid())) {
            throw new BusinessException("PAYMENT_EVENT_SIGNATURE_INVALID", "事件验签未通过，不能重放");
        }
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            throw new BusinessException("PAYMENT_EVENT_PAYLOAD_MISSING", "事件没有原始 payload，不能重放");
        }
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(event.getProvider().trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException("PAYMENT_EVENT_PROVIDER_INVALID", "未知支付渠道：" + event.getProvider());
        }

        WebhookPayload payload;
        try {
            payload = paymentServiceFactory.getStrategyForExistingPayment(method)
                .parseWebhookPayload(event.getPayload());
        } catch (Exception e) {
            log.error("事件重放解析失败：eventId={}, provider={}", eventId, method, e);
            throw new BusinessException("PAYMENT_EVENT_PARSE_FAILED", "事件 payload 解析失败：" + e.getMessage());
        }
        if (!subscriptionService.enrichFromProvider(payload, method)) {
            return ResponseEntity.status(503).body("Subscription lookup unavailable");
        }

        ResponseEntity<String> response = webhookController.processVerifiedWebhook(method, payload, event.getPayload());
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new BusinessException("PAYMENT_EVENT_REPLAY_FAILED",
                "重放失败：" + (response.getBody() != null ? response.getBody() : response.getStatusCode()));
        }
        log.info("支付事件人工重放成功：eventId={}, provider={}, event={}",
            eventId, method, event.getEventType());
        return response;
    }
}
