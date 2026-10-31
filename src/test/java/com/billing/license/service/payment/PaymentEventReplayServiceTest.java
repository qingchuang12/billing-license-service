package com.billing.license.service.payment;

import com.billing.license.controller.webhook.WebhookController;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import com.billing.license.service.payment.strategy.PaymentStrategy;
import com.billing.license.service.payment.strategy.WebhookPayload;
import com.billing.license.service.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentEventReplayServiceTest {

    private PaymentEventRepository repository;
    private PaymentServiceFactory factory;
    private SubscriptionService subscriptionService;
    private WebhookController webhookController;
    private PaymentEventReplayService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentEventRepository.class);
        factory = mock(PaymentServiceFactory.class);
        subscriptionService = mock(SubscriptionService.class);
        webhookController = mock(WebhookController.class);
        service = new PaymentEventReplayService(repository, factory, subscriptionService, webhookController);
    }

    private PaymentEvent event(boolean processed, boolean signatureValid) {
        return PaymentEvent.builder()
            .id(UUID.randomUUID()).provider("CREEM").eventId("evt_1")
            .eventType("checkout.completed").signatureValid(signatureValid)
            .processed(processed).payload("{\"id\":\"evt_1\"}").build();
    }

    @Test
    void replay_rejectsProcessedEvent() {
        PaymentEvent e = event(true, true);
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.replay(e.getId()));
        assertEquals("PAYMENT_EVENT_ALREADY_PROCESSED", ex.getErrorCode());
    }

    @Test
    void replay_rejectsUnverifiedEvent() {
        PaymentEvent e = event(false, false);
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.replay(e.getId()));
        assertEquals("PAYMENT_EVENT_SIGNATURE_INVALID", ex.getErrorCode());
    }

    @Test
    void replay_dispatchesThroughVerifiedWebhook() {
        PaymentEvent e = event(false, true);
        when(repository.findById(e.getId())).thenReturn(Optional.of(e));
        PaymentStrategy strategy = mock(PaymentStrategy.class);
        WebhookPayload payload = new WebhookPayload();
        payload.setWebhookEventId("evt_1");
        when(factory.getStrategyForExistingPayment(PaymentMethod.CREEM)).thenReturn(strategy);
        when(strategy.parseWebhookPayload(e.getPayload())).thenReturn(payload);
        when(subscriptionService.enrichFromProvider(payload, PaymentMethod.CREEM)).thenReturn(true);
        when(webhookController.processVerifiedWebhook(PaymentMethod.CREEM, payload, e.getPayload()))
            .thenReturn(ResponseEntity.ok("success"));

        ResponseEntity<String> response = service.replay(e.getId());

        assertEquals(200, response.getStatusCode().value());
        verify(webhookController).processVerifiedWebhook(PaymentMethod.CREEM, payload, e.getPayload());
    }
}
