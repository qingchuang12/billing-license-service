package com.billing.license.controller;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.service.RedeemCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/redeem")
@RequiredArgsConstructor
public class RedeemCodeController {
    
    private final RedeemCodeService redeemCodeService;
    
    /**
     * Generate redeem codes (admin only)
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateCodes(
            @RequestParam String productSku,
            @RequestParam int count,
            @RequestParam(required = false) LocalDateTime expiresAt) {
        
        int created = redeemCodeService.generateCodes(productSku, count, expiresAt);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", created);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Redeem a code
     */
    @PostMapping("/redeem")
    public ResponseEntity<Map<String, Object>> redeemCode(@RequestBody RedeemCodeRequest request) {
        License license = redeemCodeService.redeemCode(request);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("licenseKey", license.getLicenseKey());
        response.put("signedToken", license.getSignedToken());
        response.put("expiresAt", license.getExpiresAt());
        return ResponseEntity.ok(response);
    }
    
    /**
     * Revoke a redeem code (admin only)
     */
    @PostMapping("/revoke/{code}")
    public ResponseEntity<Map<String, Object>> revokeCode(@PathVariable String code) {
        redeemCodeService.revokeCode(code);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return ResponseEntity.ok(response);
    }
}
