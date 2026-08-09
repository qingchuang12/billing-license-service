package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseService {
    
    private final LicenseRepository licenseRepository;
    private final OrderRepository orderRepository;
    private final LicenseIssuer licenseIssuer;
    private final BillingProperties billingProperties;
    
    /**
     * Issue licenses for a paid order
     */
    @Transactional
    public List<LicenseResponse> issueLicensesForOrder(UUID orderId) {
        log.info("Issuing licenses for order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", 
                "Order not found: " + orderId));
        
        if (order.getPaymentStatus() != Order.PaymentStatus.PAID) {
            throw new BusinessException("ORDER_NOT_PAID", 
                "Order must be paid before issuing licenses");
        }
        
        // Get order items and issue licenses
        List<License> licenses = order.getOrderItems().stream()
            .flatMap(item -> {
                // Issue one license per quantity
                return java.util.stream.IntStream.range(0, item.getQuantity())
                    .mapToObj(i -> createLicense(order, item.getProduct()));
            })
            .collect(Collectors.toList());
        
        licenseRepository.saveAll(licenses);
        
        log.info("Issued {} licenses for order: {}", licenses.size(), orderId);
        
        return licenses.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Verify a license by key
     */
    @Transactional
    public LicenseResponse verifyLicense(String licenseKey) {
        log.info("Verifying license: {}", licenseKey);
        
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND", 
                "License not found: " + licenseKey));
        
        // Update last verified timestamp
        license.setLastVerifiedAt(LocalDateTime.now());
        licenseRepository.save(license);
        
        // Check status
        if (license.getStatus() != License.LicenseStatus.ACTIVE) {
            throw new BusinessException("LICENSE_INVALID", 
                "License is not active: " + license.getStatus().name());
        }
        
        // Check expiration
        if (license.getExpiresAt() != null && 
            LocalDateTime.now().isAfter(license.getExpiresAt())) {
            license.setStatus(License.LicenseStatus.EXPIRED);
            licenseRepository.save(license);
            throw new BusinessException("LICENSE_EXPIRED", 
                "License has expired");
        }
        
        return mapToResponse(license);
    }
    
    /**
     * Get licenses by customer ID
     */
    public List<LicenseResponse> getLicensesByCustomer(UUID customerId) {
        List<License> licenses = licenseRepository.findByCustomerId(customerId);
        return licenses.stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }
    
    /**
     * Revoke a license
     */
    @Transactional
    public void revokeLicense(String licenseKey) {
        License license = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND", 
                "License not found: " + licenseKey));
        
        license.setStatus(License.LicenseStatus.REVOKED);
        licenseRepository.save(license);
        
        log.info("Revoked license: {}", licenseKey);
    }
    
    private License createLicense(Order order, Product product) {
        String licenseKey = generateLicenseKey();
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plusDays(product.getLicenseDurationDays());
        
        License license = License.builder()
            .licenseKey(licenseKey)
            .customerId(order.getCustomerId())
            .order(order)
            .product(product)
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .build();
        
        // Sign the license
        String signedToken = licenseIssuer.issueLicense(license);
        license.setSignedToken(signedToken);
        
        return license;
    }
    
    private String generateLicenseKey() {
        // Format: XXXX-XXXX-XXXX-XXXX
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("-");
            String segment = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            sb.append(segment);
        }
        return sb.toString();
    }
    
    private LicenseResponse mapToResponse(License license) {
        return LicenseResponse.builder()
            .id(license.getId())
            .licenseKey(license.getLicenseKey())
            .customerId(license.getCustomerId())
            .productSku(license.getProduct().getSku())
            .status(license.getStatus().name())
            .issuedAt(license.getIssuedAt())
            .expiresAt(license.getExpiresAt())
            .activatedAt(license.getActivatedAt())
            .signedToken(license.getSignedToken())
            .build();
    }
}
