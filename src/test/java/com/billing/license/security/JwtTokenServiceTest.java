package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link JwtTokenService} 单元测试。
 *
 * <p>覆盖：签发/解析往返、载荷字段、密钥缺失与过短的 fail-fast、签名不符被拒。
 * 过期场景依赖真实时钟，留给集成测试（可用 jjwt 的 clock 注入后再补）。
 */
class JwtTokenServiceTest {

    /** HS256 要求 ≥32 字节 */
    private static final String SECRET = "unit-test-secret-unit-test-secret-32b";

    private JwtTokenService service() {
        AccountProperties props = new AccountProperties();
        props.setJwtSecret(SECRET);
        return new JwtTokenService(props);
    }

    @Test
    void issueAndParse_roundTrip() {
        JwtTokenService svc = service();
        UUID userId = UUID.randomUUID();

        String token = svc.issue(userId, 3);
        Claims claims = svc.parse(token);

        assertEquals(userId, svc.extractUserId(claims));
        assertEquals(3, svc.extractTokenVersion(claims));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void expiresInSeconds_matchesTtlHours() {
        AccountProperties props = new AccountProperties();
        props.setJwtSecret(SECRET);
        props.setTokenTtlHours(2);
        assertEquals(7200L, new JwtTokenService(props).expiresInSeconds());
    }

    @Test
    void missingSecret_failsFast() {
        AccountProperties props = new AccountProperties();
        props.setJwtSecret("");
        assertThrows(IllegalStateException.class, () -> new JwtTokenService(props));
    }

    @Test
    void shortSecret_failsFast() {
        AccountProperties props = new AccountProperties();
        props.setJwtSecret("too-short");
        assertThrows(IllegalStateException.class, () -> new JwtTokenService(props));
    }

    @Test
    void tamperedToken_rejected() {
        JwtTokenService svc = service();
        String token = svc.issue(UUID.randomUUID(), 0);
        String tampered = token.substring(0, token.length() - 2)
            + (token.endsWith("AA") ? "BB" : "AA");

        assertThrows(JwtException.class, () -> svc.parse(tampered));
    }

    @Test
    void foreignKeySignedToken_rejected() {
        AccountProperties other = new AccountProperties();
        other.setJwtSecret("another-secret-another-secret-32byte");
        String token = new JwtTokenService(other).issue(UUID.randomUUID(), 0);

        assertThrows(JwtException.class, () -> service().parse(token));
    }
}
