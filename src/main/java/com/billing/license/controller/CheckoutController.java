package com.billing.license.controller;

import com.billing.license.dto.CheckoutRequest;
import com.billing.license.dto.CheckoutResponse;
import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.service.CheckoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 统一收银台控制器（架构十一）
 */
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    /**
     * 创建收银台会话
     */
    @PostMapping("/create")
    public ResponseEntity<CheckoutResponse> create(@Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(checkoutService.createCheckout(request));
    }

    /**
     * 选择支付方式
     */
    @PostMapping("/{checkoutId}/select-provider")
    public ResponseEntity<CheckoutResponse> selectProvider(
            @PathVariable String checkoutId,
            @Valid @RequestBody SelectProviderRequest request) {
        return ResponseEntity.ok(checkoutService.selectProvider(checkoutId, request));
    }

    /**
     * 查询收银台/支付状态
     */
    @GetMapping("/{checkoutId}/status")
    public ResponseEntity<CheckoutResponse> status(@PathVariable String checkoutId) {
        return ResponseEntity.ok(checkoutService.getStatus(checkoutId));
    }
}
