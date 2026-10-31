package com.billing.license.service;

import com.billing.license.entity.CheckoutSession;
import com.billing.license.repository.CheckoutSessionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * #50：后台对账调度器只负责扫描与节流，对账动作必须带 allowClose=true 交给 CheckoutService。
 */
class CheckoutReconciliationServiceTest {

    @Test
    void shouldReconcilePendingSessionsWithCloseEnabled() {
        CheckoutSessionRepository repository = mock(CheckoutSessionRepository.class);
        CheckoutService checkoutService = mock(CheckoutService.class);
        CheckoutSession session = CheckoutSession.builder().checkoutId("chk_1").build();
        when(repository.findReconcileCandidates(eq(CheckoutSession.Status.PENDING), any(), any(), any()))
            .thenReturn(List.of(session));
        when(checkoutService.reconcileSession("chk_1", true))
            .thenReturn(CheckoutService.ReconcileResult.PAID);

        new CheckoutReconciliationService(repository, checkoutService).reconcileOpenSessions();

        verify(checkoutService).reconcileSession("chk_1", true);
    }

    @Test
    void shouldNotCallReconcileWhenNoCandidates() {
        CheckoutSessionRepository repository = mock(CheckoutSessionRepository.class);
        CheckoutService checkoutService = mock(CheckoutService.class);
        when(repository.findReconcileCandidates(eq(CheckoutSession.Status.PENDING), any(), any(), any()))
            .thenReturn(List.of());

        new CheckoutReconciliationService(repository, checkoutService).reconcileOpenSessions();

        verifyNoInteractions(checkoutService);
    }
}
