package com.billing.license.dto;

import com.billing.license.entity.License;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 兑换响应 DTO 单测（K4，2026-09-18）。
 *
 * <p>客户端用 {@code serverTime} 抬高本地单调时间下界（`server_time_floor`）以防「改系统时间让过期授权复活」；
 * 此前客户端读取该字段但服务端从未返回，防回拨能力实际未生效——本用例锁住该字段存在且为 epoch 毫秒。
 */
class RedeemResponseTest {

    @Test
    void from_shouldCarryServerTimeInEpochMillis() {
        long before = System.currentTimeMillis();
        License license = License.builder()
            .licenseKey("2F8A-7C31-9D04-B5E6")
            .signedToken("header.payload.signature")
            .expiresAt(LocalDateTime.now().plusDays(365))
            .build();

        RedeemResponse response = RedeemResponse.from(license);
        long after = System.currentTimeMillis();

        assertTrue(response.isSuccess());
        assertEquals("2F8A-7C31-9D04-B5E6", response.getLicenseKey());
        assertEquals("header.payload.signature", response.getSignedToken());
        assertNotNull(response.getServerTime(), "K4：必须回传 serverTime");
        assertTrue(response.getServerTime() >= before && response.getServerTime() <= after,
            "serverTime 应为服务端当前时间的 epoch 毫秒（不是秒）");
    }

    @Test
    void from_shouldTolerateNullExpiryForPerpetualLicense() {
        License license = License.builder()
            .licenseKey("AAAA-BBBB-CCCC-DDDD")
            .signedToken("header.payload.signature")
            .expiresAt(null)
            .build();

        RedeemResponse response = RedeemResponse.from(license);

        assertEquals(null, response.getExpiresAt());
        assertNotNull(response.getServerTime());
    }
}
