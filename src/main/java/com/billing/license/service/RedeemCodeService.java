package com.billing.license.service;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.entity.Product;
import com.billing.license.entity.RedeemCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedeemCodeService {
    
    private final RedeemCodeRepository redeemCodeRepository;
    private final LicenseRepository licenseRepository;
    private final ProductRepository productRepository;
    private final LicenseIssuer licenseIssuer;
    
    /**
     * Generate a single redeem code for an order
     */
    @Transactional
    public String generateCode(String orderId) {
        log.info("Generating redeem code for order: {}", orderId);
        
        // For order-based code generation, we use a simple approach
        String code = generateCode();
        
        RedeemCode redeemCode = RedeemCode.builder()
            .code(code)
            .status(RedeemCode.RedeemCodeStatus.UNUSED)
            .maxUses(1)
            .currentUses(0)
            .metadata("{\"orderId\":\"" + orderId + "\"}")
            .build();
        
        redeemCodeRepository.save(redeemCode);
        log.info("Generated redeem code: {} for order: {}", code, orderId);
        
        return code;
    }

    /**
     * Generate a batch of redeem codes
     */
    @Transactional
    public int generateCodes(String productSku, int count, LocalDateTime expiresAt) {
        log.info("Generating {} redeem codes for product: {}", count, productSku);
        
        Product product = productRepository.findBySku(productSku)
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", 
                "Product not found: " + productSku));
        
        int created = 0;
        for (int i = 0; i < count; i++) {
            String code = generateCode();
            
            RedeemCode redeemCode = RedeemCode.builder()
                .code(code)
                .product(product)
                .status(RedeemCode.RedeemCodeStatus.UNUSED)
                .expiresAt(expiresAt)
                .maxUses(1)
                .currentUses(0)
                .build();
            
            redeemCodeRepository.save(redeemCode);
            created++;
        }
        
        log.info("Generated {} redeem codes", created);
        return created;
    }
    
    /**
     * Redeem a code to get a license
     */
    @Transactional
    public License redeemCode(RedeemCodeRequest request) {
        log.info("Redeeming code for customer: {}", request.getCustomerId());
        
        RedeemCode redeemCode = redeemCodeRepository.findByCode(request.getCode())
            .orElseThrow(() -> new BusinessException("CODE_NOT_FOUND", 
                "Invalid redeem code"));
        
        // Validate status
        if (redeemCode.getStatus() != RedeemCode.RedeemCodeStatus.UNUSED) {
            throw new BusinessException("CODE_ALREADY_USED", 
                "This code has already been used");
        }
        
        // Check expiration
        if (redeemCode.getExpiresAt() != null && 
            LocalDateTime.now().isAfter(redeemCode.getExpiresAt())) {
            redeemCode.setStatus(RedeemCode.RedeemCodeStatus.EXPIRED);
            redeemCodeRepository.save(redeemCode);
            throw new BusinessException("CODE_EXPIRED", 
                "This code has expired");
        }
        
        UUID customerId = UUID.fromString(request.getCustomerId());
        
        // Create license
        String licenseKey = generateLicenseKey();
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plusDays(redeemCode.getProduct().getLicenseDurationDays());
        
        License license = License.builder()
            .licenseKey(licenseKey)
            .customerId(customerId)
            .order(null) // No order for redeemed codes
            .product(redeemCode.getProduct())
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
        
        // Sign the license
        String signedToken = licenseIssuer.issueLicense(license);
        license.setSignedToken(signedToken);
        
        // Mark code as used
        redeemCode.setStatus(RedeemCode.RedeemCodeStatus.USED);
        redeemCode.setUsedBy(customerId);
        redeemCode.setUsedAt(LocalDateTime.now());
        redeemCode.setCurrentUses(redeemCode.getCurrentUses() + 1);
        
        licenseRepository.save(license);
        redeemCodeRepository.save(redeemCode);
        
        log.info("Code redeemed successfully, license issued: {}", licenseKey);
        
        return license;
    }
    
    /**
     * Revoke a redeem code
     */
    @Transactional
    public void revokeCode(String code) {
        RedeemCode redeemCode = redeemCodeRepository.findByCode(code)
            .orElseThrow(() -> new BusinessException("CODE_NOT_FOUND", 
                "Code not found"));
        
        if (redeemCode.getStatus() == RedeemCode.RedeemCodeStatus.USED) {
            throw new BusinessException("CODE_ALREADY_USED", 
                "Cannot revoke an already used code");
        }
        
        redeemCode.setStatus(RedeemCode.RedeemCodeStatus.REVOKED);
        redeemCodeRepository.save(redeemCode);
        
        log.info("Revoked redeem code: {}", code);
    }
    
    private String generateCode() {
        // Format: XXXX-XXXX-XXXX-XXXX (alphanumeric)
        StringBuilder sb = new StringBuilder();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Excluding similar chars
        
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("-");
            for (int j = 0; j < 4; j++) {
                int idx = (int)(Math.random() * chars.length());
                sb.append(chars.charAt(idx));
            }
        }
        return sb.toString();
    }
    
    private String generateLicenseKey() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("-");
            String segment = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            sb.append(segment);
        }
        return sb.toString();
    }
}
