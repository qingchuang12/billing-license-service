package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.CreateOrderRequest;
import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.Order;
import com.billing.license.entity.OrderItem;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final BillingProperties billingProperties;
    
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        log.info("Creating order for customer: {}", request.getCustomerId());
        
        // Create order
        Order order = Order.builder()
            .orderNumber(generateOrderNumber())
            .customerId(request.getCustomerId())
            .totalAmount(BigDecimal.ZERO)
            .currency("USD")
            .status(Order.OrderStatus.PENDING)
            .paymentStatus(Order.PaymentStatus.UNPAID)
            .build();
        
        order = orderRepository.save(order);
        
        // Add order items
        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>();
        
        for (CreateOrderRequest.OrderItemDto itemDto : request.getItems()) {
            Product product = productRepository.findBySku(itemDto.getSku())
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", 
                    "Product not found: " + itemDto.getSku()));
            
            if (!product.getActive()) {
                throw new BusinessException("PRODUCT_INACTIVE", 
                    "Product is inactive: " + itemDto.getSku());
            }
            
            BigDecimal itemTotal = product.getPrice()
                .multiply(BigDecimal.valueOf(itemDto.getQuantity()));
            
            OrderItem item = OrderItem.builder()
                .order(order)
                .product(product)
                .quantity(itemDto.getQuantity())
                .unitPrice(product.getPrice())
                .totalPrice(itemTotal)
                .build();
            
            items.add(item);
            total = total.add(itemTotal);
        }
        
        order.setTotalAmount(total);
        orderRepository.save(order);
        
        log.info("Order created: {} with total: {}", order.getOrderNumber(), total);
        
        return mapToResponse(order);
    }
    
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
    
    private String generateOrderNumber() {
        return "ORD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
    
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
