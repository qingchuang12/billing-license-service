package com.billing.license.service.fulfillment;

import com.billing.license.entity.FulfillmentEvent;
import com.billing.license.entity.Order;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.repository.FulfillmentEventRepository;
import com.billing.license.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 履约调度（#56）：支付事务只写事件，交付由适配器执行并支持失败重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentService {

    private static final int MAX_ATTEMPTS = 5;

    private final FulfillmentEventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final List<FulfillmentHandler> handlers;

    @Autowired
    @Lazy
    private FulfillmentService self;

    private Map<String, FulfillmentHandler> handlerMap;

    private Map<String, FulfillmentHandler> handlers() {
        if (handlerMap == null) {
            handlerMap = handlers.stream()
                .collect(Collectors.toMap(FulfillmentHandler::type, Function.identity()));
        }
        return handlerMap;
    }

    /**
     * 在支付事务内登记履约事件并把订单置为已支付；不执行交付。
     * 同一订单 + 类型唯一，重复收款事件不会重复建事件。
     */
    @Transactional
    public void enqueue(Order order, String eventType, String payload) {
        FulfillmentEvent event = eventRepository
            .findByOrderIdStrAndEventType(order.getId().toString(), eventType)
            .orElse(null);
        if (event == null) {
            event = FulfillmentEvent.builder()
                .orderIdStr(order.getId().toString())
                .orderNumber(order.getOrderNo())
                .eventType(eventType)
                .status(FulfillmentEvent.Status.PENDING)
                .payload(payload)
                .build();
            eventRepository.save(event);
        }
        if (order.canFulfill()) {
            // 订单行锁已在调用方（fulfill/compensation）取得；这里只做状态机出口
            order.markPaid();
            orderRepository.save(order);
        }
        checkoutSessionRepository.findByOrderNumber(order.getOrderNo()).ifPresent(session -> {
            session.setStatus(com.billing.license.entity.CheckoutSession.Status.PAID);
            checkoutSessionRepository.save(session);
        });
        log.info("履约事件已登记：orderNumber={}, type={}, status={}",
            order.getOrderNo(), eventType, event.getStatus());
    }

    /**
     * 执行一个待交付事件。失败只记录不回滚支付：事件置 FAILED，由定时任务重试。
     * 成功/失败都不抛出（调用方是渠道回调或轮询，不能因此回滚已确认收款）。
     */
    @Transactional
    public void processPending(String orderNumber, String eventType) {
        if (orderNumber == null) {
            return;
        }
        // Webhook、轮询补偿与定时重试可能同时到达：先锁订单行再读事件，
        // 让同一订单的履约串行化（第二腿拿到锁后会看到事件已 DONE，不再签发第二张证）。
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber).orElse(null);
        if (order == null) {
            log.warn("履约订单不存在：orderNumber={}", orderNumber);
            return;
        }
        processEvent(order, eventType);
    }

    /** 兼容旧 PAID 会话：没有事件就补建，然后立即交付（用于轮询/补偿）。 */
    @Transactional
    public void ensureAndProcess(String orderNumber, String eventType) {
        Order order = orderRepository.findByOrderNumberForUpdate(orderNumber).orElse(null);
        if (order == null) {
            return;
        }
        enqueue(order, eventType, null);
        processEvent(order, eventType);
    }

    private void processEvent(Order order, String eventType) {
        FulfillmentEvent event = eventRepository
            .findByOrderIdStrAndEventType(order.getId().toString(), eventType)
            .orElse(null);
        if (event == null || event.getStatus() == FulfillmentEvent.Status.DONE) {
            return;
        }
        FulfillmentHandler handler = handlers().get(eventType);
        if (handler == null) {
            event.setStatus(FulfillmentEvent.Status.FAILED);
            event.setLastError("未注册履约适配器：" + eventType);
            eventRepository.save(event);
            return;
        }
        try {
            // 重新加载行项，避免 OSIV 关闭后适配器懒加载失败
            Order full = orderRepository.findWithItemsById(order.getId()).orElse(order);
            event.setAttempts((event.getAttempts() == null ? 0 : event.getAttempts()) + 1);
            handler.fulfill(full);
            event.setStatus(FulfillmentEvent.Status.DONE);
            event.setProcessedAt(LocalDateTime.now());
            event.setLastError(null);
            eventRepository.save(event);
            log.info("履约完成：orderNumber={}, type={}", order.getOrderNo(), eventType);
        } catch (Exception e) {
            event.setStatus(FulfillmentEvent.Status.FAILED);
            event.setLastError((e.getClass().getSimpleName() + ": " + e.getMessage()));
            eventRepository.save(event);
            log.error("履约失败，等待重试：orderNumber={}, type={}, attempts={}",
                order.getOrderNo(), eventType, event.getAttempts(), e);
        }
    }

    /** 定时重试 PENDING/FAILED 事件；每次最多 50 条。 */
    @Scheduled(fixedDelayString = "${billing.fulfillment.retry-interval-ms:60000}")
    public void retryPending() {
        List<FulfillmentEvent> events = eventRepository
            .findByStatusInAndAttemptsLessThanOrderByCreatedAtAsc(
                List.of(FulfillmentEvent.Status.PENDING, FulfillmentEvent.Status.FAILED),
                MAX_ATTEMPTS, PageRequest.of(0, 50));
        for (FulfillmentEvent event : events) {
            try {
                self.processPending(event.getOrderNumber(), event.getEventType());
            } catch (Exception e) {
                log.warn("履约重试异常：orderNumber={}, type={}", event.getOrderNumber(), event.getEventType(), e);
            }
        }
    }
}
