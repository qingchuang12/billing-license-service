package com.billing.license.controller;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.exception.AdminUnauthorizedException;
import com.billing.license.service.AdminService;
import com.billing.license.service.AuditLogService;
import com.billing.license.service.LicenseService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理后台控制器（架构十一.6、十六 退款/作废）
 *
 * H11 安全加固：
 * 1. 管理密钥不在运行时做明文集合比较，而是在启动时计算各密钥的 SHA-256 哈希并缓存；
 *    请求时仅对传入密钥做哈希，再以常量时间比较（MessageDigest.isEqual），抵御时序侧信道。
 * 2. 所有敏感操作（退款 / 作废 / 换机 / 列表查询）均经 AuditLogService 留痕。
 * 3. 列表接口返回脱敏的 LicenseResponse（不回显 signedToken）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final LicenseService licenseService;
    private final AuditLogService auditLogService;

    // B7：与管理端 fail-fast 配置（application.yml 中 ${ADMIN_API_KEYS} 无默认值）保持一致，
    // 不保留弱口令默认值；环境变量未注入时应用启动即失败，避免带着 admin-key-change-me 上线
    @Value("${security.admin-api-keys}")
    private String adminApiKeys;

    /** 启动时计算并缓存的各管理密钥 SHA-256 哈希（十六进制） */
    private Set<String> keyHashes;

    @PostConstruct
    void initKeyHashes() {
        this.keyHashes = Set.of(adminApiKeys.split(",")).stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(this::sha256Hex)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 校验管理员 API Key：计算传入密钥哈希，与缓存哈希集合做常量时间比较。
     */
    private boolean authorized(String key) {
        if (key == null || key.isEmpty()) return false;
        String hash = sha256Hex(key);
        // 常量时间比较，避免时序侧信道泄露匹配位置
        for (String stored : keyHashes) {
            if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /** 取密钥哈希前缀用于审计关联（不记录明文密钥） */
    private String actorHash(String key) {
        if (key == null || key.isEmpty()) return "anonymous";
        String h = sha256Hex(key);
        return h.length() > 12 ? h.substring(0, 12) : h;
    }

    private void ensureAuthorized(String key) {
        if (!authorized(key)) {
            throw new AdminUnauthorizedException();
        }
    }

    private String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponse>> listOrders(
            @RequestHeader("X-Admin-API-Key") String apiKey) {
        ensureAuthorized(apiKey);
        auditLogService.audit(actorHash(apiKey), "LIST_ORDERS", "-", true, "");
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
            auditLogService.audit(actorHash(apiKey), "LIST_ORDERS_BY_STATUS", status, false, "非法状态值");
            return ResponseEntity.badRequest().build();
        }
        auditLogService.audit(actorHash(apiKey), "LIST_ORDERS_BY_STATUS", status, true, "");
        return ResponseEntity.ok(adminService.listOrdersByStatus(orderStatus));
    }

    @PostMapping("/orders/{orderNumber}/refund")
    public ResponseEntity<OrderResponse> refundOrder(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String orderNumber,
            @RequestParam(required = false) String reason) {
        ensureAuthorized(apiKey);
        try {
            OrderResponse resp = adminService.refundOrder(orderNumber, reason);
            auditLogService.audit(actorHash(apiKey), "REFUND_ORDER", orderNumber, true, reason != null ? reason : "");
            return ResponseEntity.ok(resp);
        } catch (RuntimeException e) {
            // H4/H11：退款失败（含渠道未成功）必须如实记录，不谎报成功
            auditLogService.audit(actorHash(apiKey), "REFUND_ORDER", orderNumber, false, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/orders/{orderNumber}/licenses")
    public ResponseEntity<List<LicenseResponse>> listLicenses(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String orderNumber) {
        ensureAuthorized(apiKey);
        // H10：返回脱敏视图，不回显 signedToken 等内部字段
        List<LicenseResponse> views = adminService.listLicensesByOrder(orderNumber).stream()
            .map(LicenseResponse::adminView)
            .collect(Collectors.toList());
        auditLogService.audit(actorHash(apiKey), "LIST_LICENSES", orderNumber, true, "");
        return ResponseEntity.ok(views);
    }

    // i8：管理端特权吊销端点；与 LicenseController 的 /api/v1/licenses/revoke/{key}（客户端自吊销）分工不同，二者均保留
    @PostMapping("/licenses/{licenseKey}/revoke")
    public ResponseEntity<Void> revokeLicense(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String licenseKey,
            @RequestParam(required = false) String reason) {
        ensureAuthorized(apiKey);
        adminService.revokeLicense(licenseKey, reason);
        auditLogService.audit(actorHash(apiKey), "REVOKE_LICENSE", licenseKey, true, reason != null ? reason : "");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/licenses/{licenseKey}/reissue")
    public ResponseEntity<?> reissueLicense(
            @RequestHeader("X-Admin-API-Key") String apiKey,
            @PathVariable String licenseKey,
            @RequestParam String newMachineId,
            @RequestParam(required = false) String reason) {
        ensureAuthorized(apiKey);
        Object result = licenseService.reissueLicense(licenseKey, newMachineId, reason);
        auditLogService.audit(actorHash(apiKey), "REISSUE_LICENSE", licenseKey, true, "newMachineId=" + newMachineId);
        return ResponseEntity.ok(result);
    }

    /** 鉴权失败异常由 exception 包中的顶层 {@link AdminUnauthorizedException} 承载（见 i9 修正）。 */
}
