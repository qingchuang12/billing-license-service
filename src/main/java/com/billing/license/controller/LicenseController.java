package com.billing.license.controller;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * License 控制器
 * 提供软件许可证的签发、验证和管理功能
 */
@RestController
@RequestMapping("/api/v1/licenses")
@RequiredArgsConstructor
public class LicenseController {
    
    private final LicenseService licenseService;
    
    /**
     * 为已支付订单签发 License
     * @param orderId 订单 UUID
     * @return 签发的 License 列表
     */
    @PostMapping("/issue/{orderId}")
    public ResponseEntity<List<LicenseResponse>> issueLicenses(@PathVariable UUID orderId) {
        return ResponseEntity.ok(licenseService.issueLicensesForOrder(orderId));
    }
    
    /**
     * 验证 License 有效性
     * @param licenseKey License 密钥
     * @return License 详细信息
     */
    @GetMapping("/verify/{licenseKey}")
    public ResponseEntity<LicenseResponse> verifyLicense(@PathVariable String licenseKey) {
        return ResponseEntity.ok(licenseService.verifyLicense(licenseKey));
    }
    
    /**
     * 查询客户的所有 License
     * @param customerId 客户 UUID
     * @return License 列表
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<LicenseResponse>> getCustomerLicenses(@PathVariable UUID customerId) {
        return ResponseEntity.ok(licenseService.getLicensesByCustomer(customerId));
    }
    
    /**
     * 撤销 License
     * @param licenseKey License 密钥
     * @return 操作结果
     */
    // i8：客户端自吊销端点（带 X-API-Key 即 ROLE_ADMIN 的客户端调用）；与 AdminController 的
    // /api/admin/licenses/{key}/revoke（管理端特权吊销）功能重叠但分工不同，二者均保留。
    @PostMapping("/revoke/{licenseKey}")
    public ResponseEntity<Void> revokeLicense(@PathVariable String licenseKey) {
        licenseService.revokeLicense(licenseKey);
        return ResponseEntity.ok().build();
    }
}
