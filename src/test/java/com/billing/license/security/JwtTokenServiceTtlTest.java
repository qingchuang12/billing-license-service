package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtTokenService} 的<b>角色 TTL 分叉</b>单测（plan-7.0 / SEC-3）。
 *
 * <p>管理端令牌权限域更大、泄漏危害更高，故用更短的 TTL；消费端维持 7 天。这里不启 Spring，
 * 直接把 {@link AccountProperties} 当变量注入，用<b>真实签发 + 真实验签</b>读回 {@code exp}，
 * 断言两种角色的有效期确实分叉到各自配置值——避免「配了短 TTL 但签发时没用上」这类静默失效。
 */
class JwtTokenServiceTtlTest {

    private static final String SECRET = "test-only-jwt-secret-for-unit-tests-0123456789abcdef";

    private JwtTokenService newService(int consumerTtlHours, int adminTtlHours) {
        AccountProperties properties = new AccountProperties();
        properties.setJwtSecret(SECRET);
        properties.setTokenTtlHours(consumerTtlHours);
        properties.setAdminTokenTtlHours(adminTtlHours);
        return new JwtTokenService(properties);
    }

    /** 从签发令牌的 exp - iat 反解实际 TTL（小时），四舍五入到整点容差内。 */
    private long ttlHoursOf(JwtTokenService service, String token) {
        Claims claims = service.parse(token);
        long seconds = Duration.between(claims.getIssuedAt().toInstant(),
            claims.getExpiration().toInstant()).getSeconds();
        return Math.round(seconds / 3600.0);
    }

    @Test
    @DisplayName("管理端令牌 TTL 短于消费端，且各自等于配置值")
    void adminAndConsumerTtlDiverge() {
        JwtTokenService service = newService(168, 12);
        UUID userId = UUID.randomUUID();

        String consumerToken = service.issue(userId, 1, false);
        String adminToken = service.issue(userId, 1, true);

        assertEquals(168L, ttlHoursOf(service, consumerToken), "消费端 TTL 应为 168h");
        assertEquals(12L, ttlHoursOf(service, adminToken), "管理端 TTL 应为 12h");
        assertTrue(ttlHoursOf(service, adminToken) < ttlHoursOf(service, consumerToken),
            "管理端 TTL 必须严格短于消费端");
    }

    @Test
    @DisplayName("expiresInSeconds 与 issue 采用同一 TTL，响应体不会与实际 exp 脱节")
    void expiresInSecondsMatchesIssuedTtl() {
        JwtTokenService service = newService(168, 12);
        assertEquals(168L * 3600L, service.expiresInSeconds(false), "消费端 expiresIn");
        assertEquals(12L * 3600L, service.expiresInSeconds(true), "管理端 expiresIn");
    }

    @Test
    @DisplayName("无参重载等价于消费端 TTL（既有调用方语义不变）")
    void noArgOverloadsDefaultToConsumer() {
        JwtTokenService service = newService(168, 12);
        UUID userId = UUID.randomUUID();
        assertEquals(ttlHoursOf(service, service.issue(userId, 1, false)),
            ttlHoursOf(service, service.issue(userId, 1)),
            "issue(id,ver) 应与消费端 TTL 一致");
        assertEquals(service.expiresInSeconds(false), service.expiresInSeconds(),
            "expiresInSeconds() 应与消费端一致");
    }
}
