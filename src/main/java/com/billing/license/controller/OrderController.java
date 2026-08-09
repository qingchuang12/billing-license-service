package com.billing.license.controller;

import com.billing.license.dto.CreateOrderRequest;
import com.billing.license.dto.OrderResponse;
import com.billing.license.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 订单控制器
 * 提供订单创建和查询功能
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {
    
    private final OrderService orderService;
    
    /**
     * 创建订单
     * @param request 订单创建请求，包含客户 ID 和订单项列表
     * @return 创建的订单信息
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(orderService.createOrder(request));
    }
    
    /**
     * 根据订单 ID 查询订单
     * @param orderId 订单 UUID
     * @return 订单详细信息
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }
    
    /**
     * 根据订单号查询订单
     * @param orderNumber 业务订单号
     * @return 订单详细信息
     */
    @GetMapping("/number/{orderNumber}")
    public ResponseEntity<OrderResponse> getOrderByNumber(@PathVariable String orderNumber) {
        return ResponseEntity.ok(orderService.getOrderByNumber(orderNumber));
    }
}
