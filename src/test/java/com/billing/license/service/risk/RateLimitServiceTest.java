package com.billing.license.service.risk;

import com.billing.license.config.BillingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimitService 单元测试 - 覆盖邮箱/IP/机器码频控与暴力猜测限流（架构十七）
 */
class RateLimitServiceTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() throws Exception {
        BillingProperties props = new BillingProperties();
        BillingProperties.Risk risk = new BillingProperties.Risk();
        risk.setEmailPurchaseMax(3);
        risk.setEmailPurchaseWindowMinutes(60);
        risk.setMachineReissueMax(2);
        risk.setMachineReissueWindowMinutes(60);
        risk.setRedeemIpMax(5);
        risk.setRedeemIpWindowMinutes(60);
        risk.setRedeemFailureMax(3);
        risk.setRedeemFailureWindowMinutes(60);
        risk.setLicenseReissueMax(5);
        props.setRisk(risk);
        rateLimitService = new RateLimitService(props);
    }

    @Test
    void checkEmailPurchase_shouldAllowWithinLimit() {
        rateLimitService.checkEmailPurchase("a@b.com");
        rateLimitService.checkEmailPurchase("a@b.com");
        assertDoesNotThrow(() -> rateLimitService.checkEmailPurchase("a@b.com"));
    }

    @Test
    void checkEmailPurchase_shouldThrow_whenExceeded() {
        rateLimitService.checkEmailPurchase("x@y.com");
        rateLimitService.checkEmailPurchase("x@y.com");
        rateLimitService.checkEmailPurchase("x@y.com");
        assertThrows(RateLimitService.RateLimitExceededException.class,
            () -> rateLimitService.checkEmailPurchase("x@y.com"));
    }

    @Test
    void checkMachineReissue_shouldThrow_whenExceeded() {
        rateLimitService.checkMachineReissue("M1");
        rateLimitService.checkMachineReissue("M1");
        assertThrows(RateLimitService.RateLimitExceededException.class,
            () -> rateLimitService.checkMachineReissue("M1"));
    }

    @Test
    void checkRedeemIp_shouldThrow_whenExceeded() {
        for (int i = 0; i < 5; i++) rateLimitService.checkRedeemIp("1.2.3.4");
        assertThrows(RateLimitService.RateLimitExceededException.class,
            () -> rateLimitService.checkRedeemIp("1.2.3.4"));
    }

    @Test
    void recordRedeemFailure_shouldThrow_whenBruteForce() {
        rateLimitService.recordRedeemFailure("9.9.9.9");
        rateLimitService.recordRedeemFailure("9.9.9.9");
        rateLimitService.recordRedeemFailure("9.9.9.9");
        assertThrows(RateLimitService.RateLimitExceededException.class,
            () -> rateLimitService.recordRedeemFailure("9.9.9.9"));
    }

    @Test
    void differentKeys_shouldNotInterfere() {
        rateLimitService.checkEmailPurchase("a@b.com");
        rateLimitService.checkEmailPurchase("a@b.com");
        rateLimitService.checkEmailPurchase("a@b.com");
        // 不同邮箱不受影响
        assertDoesNotThrow(() -> rateLimitService.checkEmailPurchase("other@b.com"));
    }

    @Test
    void emptyKey_shouldBeIgnored() {
        assertEquals(0, rateLimitService.checkEmailPurchase(""));
        assertEquals(0, rateLimitService.checkEmailPurchase(null));
    }
}
