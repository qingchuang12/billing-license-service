package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * 登录一次性票据的签发与验签（plan-7.0 / M2）。
 *
 * <p>用途：「密码已校验通过、但第二因子尚未校验」这一中间态的凭证。它只证明
 * 「这一步密码是对的」，<b>不是</b>访问令牌。
 *
 * <p><b>本类是整个 MFA 设计的安全支点——票据绝不能等价于访问令牌。</b>
 * 假设票据复用 {@code account.jwt-secret} 签发：那么它携带的 {@code sub} + {@code ver} 就与
 * 正常令牌同构，会被 {@link JwtAuthFilter} 直接接受为<b>合法身份</b>——攻击者只要走完第一步
 * 密码校验拿到票据，就能拿它去调 {@code /api/admin/**}，<b>MFA 被整体绕过</b>。
 *
 * <p>因此本类使用 {@link MfaKeyDeriver#ticketSigningKey()} 这一<b>派生自独立主密钥</b>的密钥
 * 签名，使票据<b>不可能</b>通过 {@link JwtTokenService#parse}（签名不符）。
 * 该性质由 {@code MfaTicketServiceTest} 以断言固化，不依赖注释或人工自觉。
 *
 * <p>载荷刻意最小化：{@code sub}(userId) / {@code ver}(tokenVersion) / {@code typ}(固定 "mfa") /
 * {@code iat} / {@code exp}。其中 {@code ver} 使票据随改密、登出、降权（均递增 tokenVersion）
 * <b>立即失效</b>，无需额外撤销机制。
 */
@Service
public class MfaTicketService {

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_MFA = "mfa";

    private final AccountProperties.Mfa config;
    private final SecretKey ticketKey;

    public MfaTicketService(AccountProperties properties, MfaKeyDeriver keyDeriver) {
        this.config = properties.getMfa();
        this.ticketKey = keyDeriver.ticketSigningKey();
    }

    /**
     * 签发票据。
     *
     * @param userId       已通过密码校验的用户
     * @param tokenVersion 该用户当前令牌版本；校验时须与库中现值一致（改密即失效）
     */
    public String issue(UUID userId, int tokenVersion) {
        Instant now = Instant.now();
        Instant expiration = now.plus(config.getTicketTtlSeconds(), ChronoUnit.SECONDS);
        return Jwts.builder()
            .subject(userId.toString())
            .claim("ver", tokenVersion)
            .claim(CLAIM_TYPE, TYPE_MFA)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiration))
            .signWith(ticketKey, Jwts.SIG.HS256)
            .compact();
    }

    /**
     * 解析并验签。
     *
     * <p>{@code typ} 必须为 {@code mfa}：万一将来有人把这个密钥复用于签发别的令牌，
     * 也拦得住错用。
     *
     * @throws JwtException 签名不符 / 过期 / 格式错误 / 非 MFA 票据
     */
    public Claims parse(String ticket) {
        Claims claims = Jwts.parser()
            .verifyWith(ticketKey)
            .build()
            .parseSignedClaims(ticket)
            .getPayload();
        if (!TYPE_MFA.equals(claims.get(CLAIM_TYPE))) {
            throw new JwtException("非 MFA 票据");
        }
        return claims;
    }

    /** 从载荷取用户 ID；非法返回 {@code null} */
    public UUID extractUserId(Claims claims) {
        try {
            return UUID.fromString(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    /** 从载荷取令牌版本；缺失按 0（与 {@code JwtTokenService} 同口径） */
    public int extractTokenVersion(Claims claims) {
        Object ver = claims.get("ver");
        return ver instanceof Number n ? n.intValue() : 0;
    }
}
