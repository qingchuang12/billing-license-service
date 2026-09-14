package com.billing.license.service;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 订单服务：订单查询与响应映射。
 *
 * <p>D4（2026-09-14）：**删除** {@code createOrder}——该入口与 {@link CheckoutService#createCheckout}
 * 职责重叠，且其实现按 {@code product.getPrice()}（USD 基准价）计价、仅靠 {@code priceCny != null}
 * 推断币种，会产生「币种 CNY + 金额 USD」的资损级不一致。下单统一走收银台
 * （区域判定 {@code isDomestic} + {@link com.billing.license.entity.Product#getPriceForRegion}
 * 与 {@code getCurrencyForRegion}，缺价即拒单）。本服务只保留查询与映射能力。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderResponse getOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND",
                "Order not found: " + orderId));
        return mapToResponse(order);
    }

    public OrderResponse getOrderByNumber(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND",
                "Order not found: " + orderNumber));
        return mapToResponse(order);
    }

    /** 订单实体 → 响应 DTO（AdminService 亦复用） */
    public OrderResponse mapToResponse(Order order) {
        return OrderResponse.builder()
            .id(order.getId())
            .orderNumber(order.getOrderNumber())
            .customerId(order.getCustomerId())
            .totalAmount(order.getTotalAmount())
            .currency(order.getCurrency())
            .status(order.getStatus().name())
            .paymentStatus(order.getPaymentStatus().name())
            .createdAt(order.getCreatedAt())
            .build();
    }
}
