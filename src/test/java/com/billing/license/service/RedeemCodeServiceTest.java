package com.billing.license.service;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.entity.Product;
import com.billing.license.entity.RedeemCode;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.ProductRepository;
import com.billing.license.repository.RedeemCodeRepository;
import com.billing.license.service.risk.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RedeemCodeService 兑换流程「客户标识邮箱化」测试（plan-3.0 §三 E4 / E5）。
 *
 * <p>覆盖：未注册邮箱兑换 → 自动建访客账户且 License 挂到该 userId；
 * 已注册邮箱 → 复用同一 userId，不重复建号。
 */
class RedeemCodeServiceTest {

    private RedeemCodeRepository redeemCodeRepository;
    private LicenseRepository licenseRepository;
    private ProductRepository productRepository;
    private OrderRepository orderRepository;
    private LicenseIssuer licenseIssuer;
    private RateLimitService rateLimitService;
    private CustomerIdentityService customerIdentityService;
    private RedeemCodeService redeemCodeService;

    @BeforeEach
    void setUp() {
        redeemCodeRepository = mock(RedeemCodeRepository.class);
        licenseRepository = mock(LicenseRepository.class);
        productRepository = mock(ProductRepository.class);
        orderRepository = mock(OrderRepository.class);
        licenseIssuer = mock(LicenseIssuer.class);
        rateLimitService = mock(RateLimitService.class);
        customerIdentityService = mock(CustomerIdentityService.class);
        redeemCodeService = new RedeemCodeService(
            redeemCodeRepository, licenseRepository, productRepository,
            orderRepository, licenseIssuer, rateLimitService, customerIdentityService);
    }

    /** 桩一个可用的未使用兑换码及其签发/落库路径。 */
    private void stubUnusedCode(String code) {
        RedeemCode redeemCode = RedeemCode.builder()
            .code(code)
            .status(RedeemCode.RedeemCodeStatus.UNUSED)
            .product(Product.builder().sku("pro").licenseDurationDays(365).build())
            .maxUses(1).currentUses(0)
            .build();
        when(redeemCodeRepository.findByCode(code)).thenReturn(Optional.of(redeemCode));
        when(licenseIssuer.issueLicense(any(License.class))).thenReturn("sig.token.value");
        when(licenseRepository.save(any(License.class))).thenAnswer(i -> i.getArgument(0));
        when(redeemCodeRepository.save(any(RedeemCode.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void redeemCode_shouldBindLicenseToGuestUserId_whenEmailUnregistered() {
        stubUnusedCode("CODE-GUEST");
        UUID guestId = UUID.randomUUID();
        when(customerIdentityService.resolveOrCreate("guest@example.com")).thenReturn(guestId);

        RedeemCodeRequest req = RedeemCodeRequest.builder()
            .code("CODE-GUEST").customerEmail("guest@example.com").machineId("M1").build();
        License license = redeemCodeService.redeemCode(req);

        // 未注册邮箱自动建访客账户：License 挂到该 userId
        assertEquals(guestId, license.getCustomerId());
        assertEquals("M1", license.getMachineCode());
        verify(customerIdentityService).resolveOrCreate("guest@example.com");
    }

    @Test
    void redeemCode_shouldReuseExistingUserId_whenEmailRegistered() {
        stubUnusedCode("CODE-MEMBER");
        UUID memberId = UUID.randomUUID();
        when(customerIdentityService.resolveOrCreate("member@example.com")).thenReturn(memberId);

        RedeemCodeRequest req = RedeemCodeRequest.builder()
            .code("CODE-MEMBER").customerEmail("member@example.com").build();
        License license = redeemCodeService.redeemCode(req);

        // 已注册邮箱：复用既有 userId，不重复建号
        assertEquals(memberId, license.getCustomerId());
    }
}
