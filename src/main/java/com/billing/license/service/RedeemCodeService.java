package com.billing.license.service;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.*;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.risk.RateLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedeemCodeService {
    
    private final RedeemCodeRepository redeemCodeRepository;
    private final LicenseRepository licenseRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final LicenseIssuer licenseIssuer;
    private final RateLimitService rateLimitService;
    // E1（客户标识邮箱化）：对外邮箱 → 内部 userId 解析；未注册自动建访客账户
    private final CustomerIdentityService customerIdentityService;
    // C8：机器码首次出现账本——兑换签发时顺带登记，堵住「删档重装再领一次试用」
    private final MachineRegistryService machineRegistryService;

    // B8：使用密码学安全随机源生成兑换码，替代可预测的 Math.random()
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // i3：服务层批量生成硬上限（与 Controller 的可配置上限默认值对齐），防止超大批量写库打爆资源
    private static final int MAX_GENERATE_COUNT = 1000;
    
    /**
     * Generate a single redeem code for an order
     */
    @Transactional
    public String generateCode(String orderId) {
        log.info("Generating redeem code for order: {}", orderId);

        // C5：按 orderId 幂等——同一订单已生成过兑换码则直接返回已有码，避免 Webhook 迟到与
        // 补偿并发导致「一笔付款发两个兑换码」的资损。
        var existing = redeemCodeRepository.findByOrderId(orderId);
        if (!existing.isEmpty()) {
            log.info("订单 {} 已存在兑换码，幂等返回：{}", orderId, existing.get(0).getCode());
            return existing.get(0).getCode();
        }

        // B10 修复：下单自动发货路径（Webhook）生成的兑换码必须绑定 product，
        // 否则 redeem_codes.product_id NOT NULL 会崩溃，且 doRedeem 中取 product 会 NPE
        Order order = orderRepository.findByOrderNumber(orderId)
            .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId));
        Product product = order.getOrderItems().stream()
            .findFirst()
            .map(OrderItem::getProduct)
            .orElseThrow(() -> new BusinessException("NO_ORDER_ITEMS", "Order has no items: " + orderId));

        String code = generateCode();

        RedeemCode redeemCode = RedeemCode.builder()
            .code(code)
            .product(product)
            .orderId(orderId)
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
     * Generate a batch of redeem codes.
     *
     * <p>I5（2026-09-14）：返回**生成的码明文列表**。原实现只返回数量，导致管理端生成后
     * 无法通过 API 取回码明文（"批量生成导入发卡平台"实际不可用）。
     */
    @Transactional
    public List<String> generateCodes(String productSku, int count, LocalDateTime expiresAt) {
        log.info("Generating {} redeem codes for product: {}", count, productSku);

        // i3：防御性二次校验——即便绕过 Controller 边界，服务层也拒绝非法/超大批量
        if (count <= 0) {
            throw new BusinessException("INVALID_COUNT", "生成数量必须为正整数");
        }
        if (count > MAX_GENERATE_COUNT) {
            throw new BusinessException("COUNT_EXCEED_LIMIT",
                "批量生成数量超过上限（上限=" + MAX_GENERATE_COUNT + "），请分批生成");
        }

        Product product = productRepository.findBySku(productSku)
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", 
                "Product not found: " + productSku));
        
        List<String> codes = new ArrayList<>(count);
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
            codes.add(code);
        }
        
        log.info("Generated {} redeem codes", codes.size());
        return codes;
    }

    /**
     * I6：按产品 SKU + 状态检索兑换码（参数为空表示不过滤），供管理端导出与对账。
     */
    public List<RedeemCode> listCodes(String productSku, String status) {
        RedeemCode.RedeemCodeStatus target = null;
        if (status != null && !status.isBlank()) {
            try {
                target = RedeemCode.RedeemCodeStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_CODE_STATUS", "非法的兑换码状态值: " + status);
            }
        }
        String sku = (productSku == null || productSku.isBlank()) ? null : productSku.trim();
        return redeemCodeRepository.search(sku, target);
    }
    
    /**
     * Redeem a code to get a license
     */
    @Transactional
    public License redeemCode(RedeemCodeRequest request) {
        // E4（客户标识邮箱化）：日志仅打印掩码邮箱，避免 PII 泄漏
        log.info("Redeeming code for customer: {}", CustomerIdentityService.maskEmail(request.getCustomerEmail()));

        // 风控前置：同一 IP 高频兑换限流 + 暴力猜测拦截（架构十七）
        String clientIp = request.getClientIp();
        if (clientIp != null && !clientIp.isEmpty()) {
            try {
                rateLimitService.checkRedeemIp(clientIp);
            } catch (RateLimitService.RateLimitExceededException e) {
                throw new BusinessException("REDEEM_IP_LIMIT", "兑换请求过于频繁，请稍后再试");
            }
        }

        try {
            return doRedeem(request);
        } catch (BusinessException e) {
            // 兑换失败（码无效/已用/过期）计入暴力猜测风控
            if (clientIp != null && !clientIp.isEmpty()) {
                try {
                    rateLimitService.recordRedeemFailure(clientIp);
                } catch (RateLimitService.RateLimitExceededException ex) {
                    throw new BusinessException("REDEEM_BRUTE_FORCE", "兑换失败次数过多，已临时锁定");
                }
            }
            throw e;
        }
    }

    private License doRedeem(RedeemCodeRequest request) {
        RedeemCode redeemCode = redeemCodeRepository.findByCode(request.getCode())
            .orElseThrow(() -> new BusinessException("CODE_NOT_FOUND",
                "Invalid redeem code"));

        // Validate status
        if (redeemCode.getStatus() != RedeemCode.RedeemCodeStatus.UNUSED) {
            // B9（幂等修复）：兑换成功后 HTTP 响应在返回途中丢失，客户端重试原本会撞 CODE_ALREADY_USED
            // 而永远拿不到已签发的 License（资损：钱已付、码已耗、License 拿不到）。
            // 现改为：若是「同一兑换人」重试同一枚 USED 码，直接返回其已签发的 License（幂等）；
            // 非本人或找不到对应 License 才按已用/已撤销/过期报错。
            if (redeemCode.getStatus() == RedeemCode.RedeemCodeStatus.USED
                    && redeemCode.getUsedBy() != null
                    && request.getCustomerEmail() != null && !request.getCustomerEmail().isBlank()) {
                UUID retryCustomerId = customerIdentityService.resolveOrCreate(request.getCustomerEmail());
                if (redeemCode.getUsedBy().equals(retryCustomerId)) {
                    License existing = findRedeemedLicense(redeemCode, retryCustomerId);
                    if (existing != null) {
                        log.info("兑换码重试幂等命中，返回已签发 License：licenseKey={}", existing.getLicenseKey());
                        return existing;
                    }
                }
            }
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

        // E4（客户标识邮箱化）：删除原匿名随机 UUID 兜底与 UUID.fromString 解析——
        // 对外邮箱经解析器换成内部 userId，未注册自动建访客账户；错误码收敛为
        // EMAIL_REQUIRED / INVALID_EMAIL（均由 CustomerIdentityService 抛出）。
        UUID customerId = customerIdentityService.resolveOrCreate(request.getCustomerEmail());

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
            .machineCode(request.getMachineId()) // 绑定兑换时传入的机器码（架构十一.4）
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
        // C8：登记这台机器的首次出现时间（幂等）
        machineRegistryService.touch(request.getMachineId(), MachineRegistryService.SRC_REDEEM);

        log.info("Code redeemed successfully, license issued: {}", licenseKey);

        return license;
    }
    
    /**
     * B9：定位某兑换人凭该兑换码已签发的 License。
     * RedeemCode 无直接 License 外键，按「兑换人 + 该码绑定产品」在其名下 License 中匹配
     * （兑换签发的 License order 为 null，用 customerId + product 足以唯一定位单码单用场景）。
     * 找不到返回 null（调用方回落 CODE_ALREADY_USED）。
     */
    private License findRedeemedLicense(RedeemCode redeemCode, UUID customerId) {
        UUID productId = redeemCode.getProduct() != null ? redeemCode.getProduct().getId() : null;
        if (productId == null) {
            return null;
        }
        return licenseRepository.findByCustomerIdOrderByIssuedAtDesc(customerId).stream()
            .filter(l -> l.getProduct() != null && productId.equals(l.getProduct().getId()))
            .findFirst()
            .orElse(null);
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
                int idx = SECURE_RANDOM.nextInt(chars.length());
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
