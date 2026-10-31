package com.billing.license.controller;

import com.billing.license.dto.PaymentEventView;
import com.billing.license.entity.PaymentEvent;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.service.payment.PaymentEventReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminPaymentEventControllerTest {

    private PaymentEventRepository repository;
    private PaymentEventReplayService replayService;
    private AdminPaymentEventController controller;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentEventRepository.class);
        replayService = mock(PaymentEventReplayService.class);
        controller = new AdminPaymentEventController(repository, replayService);
    }

    @Test
    void events_mapsPageAndNormalizesProvider() {
        PaymentEvent event = PaymentEvent.builder()
            .id(UUID.randomUUID()).provider("CREEM").eventId("evt_1")
            .eventType("checkout.completed").orderId("ORD-1")
            .processed(true).payload("{\"id\":\"evt_1\"}").createdAt(LocalDateTime.now())
            .build();
        when(repository.search(eq("CREEM"), eq(true), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(event)));

        Page<PaymentEventView> page = controller.events("creem", true, 0, 20).getBody();

        assertNotNull(page);
        assertEquals(1, page.getTotalElements());
        assertEquals("CREEM", page.getContent().get(0).getProvider());
        assertTrue(page.getContent().get(0).getProcessed());
    }

    @Test
    void events_clampsPageSize() {
        when(repository.search(isNull(), isNull(), any(PageRequest.class)))
            .thenReturn(Page.empty());

        controller.events(null, null, -1, 9999);

        verify(repository).search(isNull(), isNull(),
            argThat(p -> p.getPageNumber() == 0 && p.getPageSize() == 200));
    }
}
