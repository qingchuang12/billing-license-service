package com.billing.license.service.risk;

import com.billing.license.config.BillingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimitService 内存回收测试（H8）。
 * 验证 evictStaleWindows 在超过阈值时能回收长期无活动的窗口，防止 windows 无限增长（内存泄漏）。
 */
class RateLimitServiceEvictionTest {

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() throws Exception {
        BillingProperties props = new BillingProperties();
        BillingProperties.Risk risk = new BillingProperties.Risk();
        risk.setEmailPurchaseMax(100000);
        risk.setEmailPurchaseWindowMinutes(60);
        risk.setMachineReissueMax(100000);
        risk.setMachineReissueWindowMinutes(60);
        risk.setRedeemIpMax(100000);
        risk.setRedeemIpWindowMinutes(60);
        risk.setRedeemFailureMax(100000);
        risk.setRedeemFailureWindowMinutes(60);
        risk.setLicenseReissueMax(100000);
        props.setRisk(risk);
        rateLimitService = new RateLimitService(props);
    }

    @Test
    void evictStaleWindows_shouldRemoveStaleEntriesBeyondThreshold() throws Exception {
        // 注入超过阈值（4096）的条目
        for (int i = 0; i < 5000; i++) {
            rateLimitService.checkEmailPurchase("key" + i + "@x.com");
        }

        Field windowsField = RateLimitService.class.getDeclaredField("windows");
        windowsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> windows = (Map<String, Object>) windowsField.get(rateLimitService);
        assertEquals(5000, windows.size());

        // 将第一个窗口的最后活动时间置为 2 小时前，模拟"陈旧"
        Object firstRing = windows.values().iterator().next();
        Field lastAccessField = firstRing.getClass().getDeclaredField("lastAccess");
        lastAccessField.setAccessible(true);
        ((AtomicLong) lastAccessField.get(firstRing)).set(System.currentTimeMillis() - 2 * 60 * 60_000L);

        int before = windows.size();
        rateLimitService.evictStaleWindows();
        int after = windows.size();

        assertTrue(after < before, "evictStaleWindows 应回收至少 1 个陈旧窗口（before=" + before + ", after=" + after + "）");
    }

    @Test
    void evictStaleWindows_shouldBeNoop_underThreshold() {
        rateLimitService.checkEmailPurchase("still-active@x.com");
        assertDoesNotThrow(rateLimitService::evictStaleWindows);
    }
}
