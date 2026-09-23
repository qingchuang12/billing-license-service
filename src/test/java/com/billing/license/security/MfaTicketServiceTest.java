package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一次性登录票据与密钥派生验证（plan-7.0 / M6）。
 *
 * <p><b>本类最重要的两条断言</b>：
 * <ol>
 *   <li>票据<b>不能</b>被 {@link JwtTokenService#parse} 接受——这是「MFA 不可被绕过」的技术前提。
 *       若票据与访问令牌同密钥，攻击者走完密码校验拿到票据后，就能拿它直接调
 *       {@code /api/admin/**}，整个第二因子形同虚设。</li>
 *   <li>访问令牌<b>不能</b>被当作票据用于第二因子校验（反向）。</li>
 * </ol>
 * 两条都是「机械验证」：一旦有人把两者改成同一密钥，测试立即失败。
 */
class MfaTicketServiceTest {

    private static final String MASTER_KEY = "mfa-master-key-for-unit-tests-0123456789abcdef";
    private static final String JWT_SECRET = "jwt-secret-for-unit-tests-0123456789abcdefghijkl";

    private AccountProperties properties;
    private JwtTokenService jwtTokenService;
    private MfaKeyDeriver keyDeriver;
    private MfaTicketService ticketService;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        properties.setJwtSecret(JWT_SECRET);
        properties.getMfa().setKey(MASTER_KEY);

        jwtTokenService = new JwtTokenService(properties);
        keyDeriver = new MfaKeyDeriver(properties);
        ticketService = new MfaTicketService(properties, keyDeriver);
    }

    // ==================== 防 MFA 绕过 ====================

    @Test
    @DisplayName("【防绕过】MFA 票据不得被访问令牌解析器接受（两者密钥必须隔离）")
    void ticket_shouldNotBeAcceptedByAccessTokenParser() {
        UUID userId = UUID.randomUUID();
        String ticket = ticketService.issue(userId, 0);

        assertNotNull(ticket);
        assertThrows(JwtException.class, () -> jwtTokenService.parse(ticket),
            "票据被当成了合法访问令牌——MFA 可被整体绕过，票据密钥与 jwt-secret 必须隔离");
    }

    @Test
    @DisplayName("【防绕过】访问令牌不得被当作 MFA 票据使用（反向）")
    void accessToken_shouldNotBeAcceptedAsTicket() {
        String accessToken = jwtTokenService.issue(UUID.randomUUID(), 0);

        assertThrows(JwtException.class, () -> ticketService.parse(accessToken),
            "访问令牌被当成了票据");
    }

    @Test
    @DisplayName("【防错用】用票据密钥签发的非 mfa 类型令牌也必须被拒（typ 校验）")
    void ticket_shouldRejectWrongTypeClaim() {
        // 模拟「有人复用票据密钥去签别的用途令牌」
        String otherToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("ver", 0)
            .claim("typ", "access")
            .signWith(keyDeriver.ticketSigningKey(), Jwts.SIG.HS256)
            .compact();

        assertThrows(JwtException.class, () -> ticketService.parse(otherToken),
            "typ 非 mfa 的令牌必须被拒");
    }

    // ==================== 正常往返 ====================

    @Test
    @DisplayName("票据往返保持 userId 与 tokenVersion 一致")
    void ticket_shouldRoundTripClaims() {
        UUID userId = UUID.randomUUID();
        String ticket = ticketService.issue(userId, 7);

        Claims claims = ticketService.parse(ticket);

        assertEquals(userId, ticketService.extractUserId(claims));
        assertEquals(7, ticketService.extractTokenVersion(claims),
            "ver 必须随票据走，才能让改密/登出后票据立即失效");
    }

    @Test
    @DisplayName("票据过期后必须被拒（有效期由 account.mfa.ticket-ttl-seconds 决定）")
    void ticket_shouldExpire() {
        // 用负的有效期构造一张「签发即过期」的票据，避免依赖时钟推进
        properties.getMfa().setTicketTtlSeconds(-60);
        MfaTicketService expired = new MfaTicketService(properties, keyDeriver);

        String ticket = expired.issue(UUID.randomUUID(), 0);

        assertThrows(JwtException.class, () -> expired.parse(ticket), "过期票据必须被拒");
    }

    @Test
    @DisplayName("篡改载荷后签名校验必须失败")
    void ticket_shouldRejectTamperedPayload() {
        String ticket = ticketService.issue(UUID.randomUUID(), 0);
        String[] parts = ticket.split("\\.");
        // 改动载荷首字符即破坏签名（票据为三段式 JWS）
        String tampered = parts[0] + "." + (parts[1].charAt(0) == 'e' ? "f" : "e")
            + parts[1].substring(1) + "." + parts[2];

        assertThrows(JwtException.class, () -> ticketService.parse(tampered));
    }

    // ==================== 密钥派生 ====================

    @Test
    @DisplayName("两个子密钥必须不同（票据签名与 TOTP 密钥加密不得同源复用）")
    void deriver_shouldProduceDistinctSubKeys() {
        byte[] ticketKey = keyDeriver.ticketSigningKey().getEncoded();
        byte[] cipherKey = keyDeriver.secretEncryptionKey().getEncoded();

        assertNotNull(ticketKey);
        assertNotNull(cipherKey);
        assertEquals(32, ticketKey.length, "HS256 要求 256 bit");
        assertEquals(32, cipherKey.length, "AES-256 要求 256 bit");
        assertFalse(java.util.Arrays.equals(ticketKey, cipherKey),
            "两个子密钥相同说明派生未做域分隔，一处泄漏将波及两处用途");
    }

    @Test
    @DisplayName("派生是确定性的：同一主密钥重复派生得到同一子密钥（否则重启后票据全失效）")
    void deriver_shouldBeDeterministic() {
        MfaKeyDeriver again = new MfaKeyDeriver(properties);

        assertTrue(java.util.Arrays.equals(
            keyDeriver.ticketSigningKey().getEncoded(), again.ticketSigningKey().getEncoded()));
        assertTrue(java.util.Arrays.equals(
            keyDeriver.secretEncryptionKey().getEncoded(), again.secretEncryptionKey().getEncoded()));
    }

    @Test
    @DisplayName("主密钥缺失即启动失败（fail-fast，与 jwt-secret 同风格）")
    void deriver_shouldFailFastWhenMasterKeyMissing() {
        AccountProperties empty = new AccountProperties();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new MfaKeyDeriver(empty));
        assertTrue(ex.getMessage().contains("ACCOUNT_MFA_KEY"), "报错须指明缺哪个环境变量");
    }

    @Test
    @DisplayName("主密钥过短即启动失败，不静默降级")
    void deriver_shouldFailFastWhenMasterKeyTooShort() {
        AccountProperties weak = new AccountProperties();
        weak.getMfa().setKey("too-short");

        assertThrows(IllegalStateException.class, () -> new MfaKeyDeriver(weak));
    }

    @Test
    @DisplayName("更换主密钥后无法解出旧票据（轮换即全量失效，符合预期）")
    void ticket_shouldBeRejectedAfterMasterKeyRotation() {
        String ticket = ticketService.issue(UUID.randomUUID(), 0);

        AccountProperties rotated = new AccountProperties();
        rotated.setJwtSecret(JWT_SECRET);
        rotated.getMfa().setKey("rotated-master-key-for-unit-tests-0123456789abcd");
        MfaTicketService afterRotation = new MfaTicketService(rotated, new MfaKeyDeriver(rotated));

        assertThrows(JwtException.class, () -> afterRotation.parse(ticket));
    }
}
