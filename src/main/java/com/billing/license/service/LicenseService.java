package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.entity.License;
import com.billing.license.entity.LicenseEvent;
import com.billing.license.entity.Order;
import com.billing.license.entity.Product;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.repository.LicenseEventRepository;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.risk.RateLimitService;
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
    private final LicenseEventRepository licenseEventRepository;
    private final LicenseIssuer licenseIssuer;
    private final BillingProperties billingProperties;
    private final EmailNotificationService emailNotificationService;
    private final RateLimitService rateLimitService;
    
    /**
     * Issue a license bound to a specific machine code
     */
    @Transactional
    public License issueLicense(String orderId, String machineCode) {
        log.info("Issuing license for order: {} with machineCode: {}", orderId, machineCode);

        Order order = orderRepository.findByOrderNumber(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND",
                "Order not found: " + orderId));

        if (order.getPaymentStatus() != Order.PaymentStatus.PAID) {
            throw new BusinessException("ORDER_NOT_PAID",
                "Order must be paid before issuing licenses");
        }

        // Get first product from order items
        Product product = order.getOrderItems().stream()
            .findFirst()
            .map(item -> item.getProduct())
            .orElseThrow(() -> new BusinessException("NO_ORDER_ITEMS", "Order has no items"));

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
            .machineCode(machineCode)
            .build();

        // Sign the license
        String signedToken = licenseIssuer.issueLicense(license);
        license.setSignedToken(signedToken);

        licenseRepository.save(license);
        recordLicenseEvent(license, LicenseEvent.EventType.ISSUED, machineCode, "Issued for order " + orderId);
        log.info("License issued successfully: {}", licenseKey);

        return license;
    }

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
            recordLicenseEvent(license, LicenseEvent.EventType.VERIFY_FAILED, license.getMachineCode(),
                "Inactive status: " + license.getStatus().name());
            throw new BusinessException("LICENSE_INVALID", 
                "License is not active: " + license.getStatus().name());
        }
        
        // Check expiration
        if (license.getExpiresAt() != null && 
            LocalDateTime.now().isAfter(license.getExpiresAt())) {
            license.setStatus(License.LicenseStatus.EXPIRED);
            licenseRepository.save(license);
            recordLicenseEvent(license, LicenseEvent.EventType.VERIFY_FAILED, license.getMachineCode(),
                "Expired at " + license.getExpiresAt());
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
        license.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(license);
        recordLicenseEvent(license, LicenseEvent.EventType.REVOKED, license.getMachineCode(),
            "Revoked");
        log.info("Revoked license: {}", licenseKey);
    }

    /**
     * 换机重发 - 为已绑定设备的 License 重新签发一个新 License 绑定到新机器码（架构十一.5）
     * 原 License 标记为 REISSUED，新 License 通过 reissuedFrom 指回原 License。
     */
    @Transactional
    public LicenseResponse reissueLicense(String licenseKey, String newMachineId, String reason) {
        log.info("Reissuing license {} to new machine {}", licenseKey, newMachineId);

        License original = licenseRepository.findByLicenseKey(licenseKey)
            .orElseThrow(() -> new BusinessException("LICENSE_NOT_FOUND",
                "License not found: " + licenseKey));

        if (original.getStatus() == License.LicenseStatus.REVOKED) {
            throw new BusinessException("LICENSE_REVOKED", "Cannot reissue a revoked license");
        }

        // 风控：同一机器码窗口内频繁换机（架构十七）
        if (newMachineId != null && !newMachineId.isEmpty()) {
            try {
                rateLimitService.checkMachineReissue(newMachineId);
            } catch (RateLimitService.RateLimitExceededException e) {
                throw new BusinessException("MACHINE_REISSUE_LIMIT", "该设备换机重发过于频繁，请稍后再试");
            }
        }

        // 风控：单个 License 累计重发次数上限（架构十七：License 重发次数限制）
        long reissuedCount = licenseEventRepository.findByLicenseKey(licenseKey).stream()
            .filter(e -> e.getEventType() == LicenseEvent.EventType.REISSUED)
            .count();
        if (reissuedCount >= billingProperties.getRisk().getLicenseReissueMax()) {
            throw new BusinessException("LICENSE_REISSUE_LIMIT",
                "License 重发次数已达上限：" + billingProperties.getRisk().getLicenseReissueMax());
        }

        // 原 License 标记为 REISSUED
        original.setStatus(License.LicenseStatus.REVOKED);
        original.setRevokedAt(LocalDateTime.now());
        licenseRepository.save(original);
        recordLicenseEvent(original, LicenseEvent.EventType.REISSUED, newMachineId,
            "Reissued to new machine. reason=" + (reason != null ? reason : ""));

        // 签发新的绑定新机器码的 License
        LocalDateTime issuedAt = LocalDateTime.now();
        LocalDateTime expiresAt = original.getExpiresAt() != null
            ? original.getExpiresAt() : issuedAt.plusDays(billingProperties.getDefaultLicenseDurationDays());

        License newLicense = License.builder()
            .licenseKey(generateLicenseKey())
            .customerId(original.getCustomerId())
            .order(original.getOrder())
            .product(original.getProduct())
            .status(License.LicenseStatus.ACTIVE)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .machineCode(newMachineId)
            .reissuedFrom(original.getId())
            .build();

        String signedToken = licenseIssuer.issueLicense(newLicense);
        newLicense.setSignedToken(signedToken);

        licenseRepository.save(newLicense);
        recordLicenseEvent(newLicense, LicenseEvent.EventType.ISSUED, newMachineId,
            "Reissued license for original " + licenseKey);

        log.info("Reissued license created: {}", newLicense.getLicenseKey());
        return mapToResponse(newLicense);
    }

    /**
     * 记录 License 事件用于审计
     */
    private void recordLicenseEvent(License license, LicenseEvent.EventType type, String machineId, String detail) {
        try {
            LicenseEvent event = LicenseEvent.builder()
                .licenseId(license.getId())
                .licenseKey(license.getLicenseKey())
                .eventType(type)
                .machineId(machineId)
                .detail(detail)
                .build();
            licenseEventRepository.save(event);
        } catch (Exception e) {
            log.error("记录 License 事件失败：type={}, licenseKey={}", type, license.getLicenseKey(), e);
        }
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
