package com.billing.license.service.fulfillment;

import com.billing.license.entity.CheckoutSession;
import com.billing.license.entity.FulfillmentEvent;
import com.billing.license.entity.Order;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.FulfillmentEventRepository;
import com.billing.license.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * #56：支付事务只登记事件；交付成功才 DONE，失败保留 FAILED 重试。
 */
class FulfillmentServiceTest {

    private FulfillmentEventRepository eventRepository;
    private OrderRepository orderRepository;
    private CheckoutSessionRepository sessionRepository;
    private FulfillmentHandler handler;
    private FulfillmentService service;
    private Order order;

    @BeforeEach
    void setUp() {
        eventRepository = mock(FulfillmentEventRepository.class);
        orderRepository = mock(OrderRepository.class);
        sessionRepository = mock(CheckoutSessionRepository.class);
        handler = mock(FulfillmentHandler.class);
        when(handler.type()).thenReturn("LICENSE");
        service = new FulfillmentService(eventRepository, orderRepository, sessionRepository,
            List.of(handler));
        order = Order.builder().id(UUID.randomUUID()).orderNumber("ORD-F-1")
            .status(Order.OrderStatus.PENDING).paymentStatus(Order.PaymentStatus.UNPAID).build();
        when(eventRepository.save(any(FulfillmentEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        when(orderRepository.findByOrderNumber("ORD-F-1")).thenReturn(Optional.of(order));
        when(orderRepository.findByOrderNumberForUpdate("ORD-F-1")).thenReturn(Optional.of(order));
        when(orderRepository.findWithItemsById(order.getId())).thenReturn(Optional.of(order));
        when(sessionRepository.findByOrderNumber("ORD-F-1"))
            .thenReturn(Optional.of(CheckoutSession.builder().orderNumber("ORD-F-1").build()));
    }

    @Test
    void enqueue_createsPendingEvent_andMarksOrderPaid() {
        when(eventRepository.findByOrderIdStrAndEventType(order.getId().toString(), "LICENSE"))
            .thenReturn(Optional.empty());

        service.enqueue(order, "LICENSE", null);

        ArgumentCaptor<FulfillmentEvent> captor = ArgumentCaptor.forClass(FulfillmentEvent.class);
        verify(eventRepository).save(captor.capture());
        assertEquals(FulfillmentEvent.Status.PENDING, captor.getValue().getStatus());
        assertEquals(Order.OrderStatus.PAID, order.getStatus());
        assertEquals(Order.PaymentStatus.PAID, order.getPaymentStatus());
        verify(handler, never()).fulfill(any());
    }

    @Test
    void processPending_invokesHandler_andMarksDone() {
        FulfillmentEvent event = FulfillmentEvent.builder().orderIdStr(order.getId().toString())
            .orderNumber("ORD-F-1").eventType("LICENSE").status(FulfillmentEvent.Status.PENDING).build();
        when(eventRepository.findByOrderIdStrAndEventType(order.getId().toString(), "LICENSE"))
            .thenReturn(Optional.of(event));

        service.processPending("ORD-F-1", "LICENSE");

        verify(handler).fulfill(order);
        assertEquals(FulfillmentEvent.Status.DONE, event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertEquals(1, event.getAttempts());
    }

    @Test
    void processPending_marksFailedWithoutThrowing() {
        FulfillmentEvent event = FulfillmentEvent.builder().orderIdStr(order.getId().toString())
            .orderNumber("ORD-F-1").eventType("LICENSE").status(FulfillmentEvent.Status.PENDING).build();
        when(eventRepository.findByOrderIdStrAndEventType(order.getId().toString(), "LICENSE"))
            .thenReturn(Optional.of(event));
        doThrow(new RuntimeException("smtp down")).when(handler).fulfill(order);

        assertDoesNotThrow(() -> service.processPending("ORD-F-1", "LICENSE"));

        assertEquals(FulfillmentEvent.Status.FAILED, event.getStatus());
        assertTrue(event.getLastError().contains("smtp down"));
        assertEquals(1, event.getAttempts());
    }

    @Test
    void processPending_skipsAlreadyDone() {
        FulfillmentEvent event = FulfillmentEvent.builder().orderIdStr(order.getId().toString())
            .orderNumber("ORD-F-1").eventType("LICENSE").status(FulfillmentEvent.Status.DONE).build();
        when(eventRepository.findByOrderIdStrAndEventType(order.getId().toString(), "LICENSE"))
            .thenReturn(Optional.of(event));

        service.processPending("ORD-F-1", "LICENSE");

        verify(handler, never()).fulfill(any());
        assertEquals(FulfillmentEvent.Status.DONE, event.getStatus());
    }

    @Test
    void retryPending_processesEligibleEvents() {
        FulfillmentEvent event = FulfillmentEvent.builder().orderIdStr(order.getId().toString())
            .orderNumber("ORD-F-1").eventType("LICENSE").status(FulfillmentEvent.Status.FAILED).build();
        when(eventRepository.findByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(any(), eq(5), any()))
            .thenReturn(List.of(event));
        when(eventRepository.findByOrderIdStrAndEventType(order.getId().toString(), "LICENSE"))
            .thenReturn(Optional.of(event));
        // self 在单测无容器时为空；直接指回自身即可验证调度逻辑
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);

        service.retryPending();

        verify(handler).fulfill(order);
        assertEquals(FulfillmentEvent.Status.DONE, event.getStatus());
    }
}
