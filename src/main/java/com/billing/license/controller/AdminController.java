package com.billing.license.controller;

import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.service.AdminService;
import com.billing.license.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理后台控制器（架构十一.6、十六 退款/作废）
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final LicenseService licenseService;

    @Value("${security.admin-api-keys:admin-key-change-me}")
    private String adminApiKeys;

    /**
     * 校验管理员 API Key；返回 true 通过
     */
    private boolean authorized(String key) {
        if (key == null || key.isEmpty()) return false;
        Set<String> valid = Set.of(adminApiKeys.split(",")).stream()
            .map(String::trim).collect(Collectors.toSet());
        return valid.contains(key);
    }

    private void ensureAuthorized(String key) {
        if (!authorized(key)) {
            throw new AdminUnauthorizedException();
        }
    }

    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponse>> listOrders(
            @RequestHeader("X-Admin-API-Key") String apiKey) {
        ensureAuthorized(apiKey);
        return ResponseEntity.ok(adminService.listOrders());
    }

    @GetMapping("/orders/status/{status}")
    public ResponseEntity<List<OrderResponse>> listOrdersByStatus(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String status) {
        ensureAuthorized(apiKey);
        Order.OrderStatus orderStatus;
        try {
            orderStatus = Order.OrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(adminService.listOrdersByStatus(orderStatus));
    }

    @PostMapping("/orders/{orderNumber}/refund")
    public ResponseEntity<OrderResponse> refundOrder(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String orderNumber,
            @RequestParam(required = false) String reason) {
        ensureAuthorized(apiKey);
        return ResponseEntity.ok(adminService.refundOrder(orderNumber, reason));
    }

    @GetMapping("/orders/{orderNumber}/licenses")
    public ResponseEntity<List<License>> listLicenses(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String orderNumber) {
        ensureAuthorized(apiKey);
        return ResponseEntity.ok(adminService.listLicensesByOrder(orderNumber));
    }

    @PostMapping("/licenses/{licenseKey}/revoke")
    public ResponseEntity<Void> revokeLicense(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String licenseKey,
            @RequestParam(required = false) String reason) {
        ensureAuthorized(apiKey);
        adminService.revokeLicense(licenseKey, reason);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/licenses/{licenseKey}/reissue")
    public ResponseEntity<?> reissueLicense(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String licenseKey,
            @RequestParam String newMachineId,
            @RequestParam(required = false) String reason) {
        ensureAuthorized(apiKey);
        return ResponseEntity.ok(licenseService.reissueLicense(licenseKey, newMachineId, reason));
    }

    /** 鉴权失败异常（由 GlobalExceptionHandler 统一处理） */
    public static class AdminUnauthorizedException extends RuntimeException {
        public AdminUnauthorizedException() {
            super("Admin API key invalid");
        }
    }
}
