package com.billing.license.controller;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/licenses")
@RequiredArgsConstructor
public class LicenseController {
    
    private final LicenseService licenseService;
    
    /**
     * Issue licenses for a paid order
     */
    @PostMapping("/issue/{orderId}")
    public ResponseEntity<List<LicenseResponse>> issueLicenses(@PathVariable UUID orderId) {
        return ResponseEntity.ok(licenseService.issueLicensesForOrder(orderId));
    }
    
    /**
     * Verify a license by key
     */
    @GetMapping("/verify/{licenseKey}")
    public ResponseEntity<LicenseResponse> verifyLicense(@PathVariable String licenseKey) {
        return ResponseEntity.ok(licenseService.verifyLicense(licenseKey));
    }
    
    /**
     * Get all licenses for a customer
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<LicenseResponse>> getCustomerLicenses(@PathVariable UUID customerId) {
        return ResponseEntity.ok(licenseService.getLicensesByCustomer(customerId));
    }
    
    /**
     * Revoke a license
     */
    @PostMapping("/revoke/{licenseKey}")
    public ResponseEntity<Void> revokeLicense(@PathVariable String licenseKey) {
        licenseService.revokeLicense(licenseKey);
        return ResponseEntity.ok().build();
    }
}
