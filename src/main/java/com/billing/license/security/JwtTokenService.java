package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * 用户会话令牌签发与解析（plan v2.10 / A2）。
 *
 * <p><b>API 依据</b>：jjwt <b>0.13.0</b>，已用 {@code javap} 核对——
 * {@code Jwts.builder()} / {@code Jwts.parser()}（0.12 起由 {@code parserBuilder()} 更名）/
 * {@code JwtParserBuilder#verifyWith(SecretKey)} / {@code JwtParser#parseSignedClaims(String)} /
 * {@code Keys#hmacShaKeyFor(byte[])} / {@code Jwts.SIG.HS256}。勿按旧版写法套用。
 *
 * <p><b>载荷最小化</b>：仅 {@code sub}(userId) / {@code ver}(tokenVersion) / {@code iat} / {@code exp}，
 * 不携带邮箱等 PII——令牌可被客户端解出（仅 Base64，非加密）。
 *
 * <p>本类只做「令牌本身的签发与验签」，<b>不查库</b>；用户状态与 tokenVersion 的比对由
 * {@link JwtAuthFilter} 每请求完成。
 */
@Slf4j
@Service
public class JwtTokenService {

    /** HS256 要求密钥 ≥ 256 bit */
    private static final int MIN_SECRET_BYTES = 32;

    private final AccountProperties properties;
    private final SecretKey key;

    public JwtTokenService(AccountProperties properties) {
        this.properties = properties;
        String secret = properties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "account.jwt-secret 未配置：请设置环境变量 ACCOUNT_JWT_SECRET（至少 32 字符）");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "account.jwt-secret 过短：HS256 要求至少 " + MIN_SECRET_BYTES + " 字节，当前 " + secretBytes.length);
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 签发访问令牌。
     *
     * @param userId       用户 ID（作为 {@code sub}）
     * @param tokenVersion 用户当前令牌版本号（作为 {@code ver}）
     * @return JWS compact 串
     */
    public String issue(UUID userId, int tokenVersion) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.getTokenTtlHours(), ChronoUnit.HOURS);
        return Jwts.builder()
            .subject(userId.toString())
            .claim("ver", tokenVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    }

    /** 令牌有效期（秒），供响应体 {@code expiresIn} 使用 */
    public long expiresInSeconds() {
        return (long) properties.getTokenTtlHours() * 3600L;
    }

    /**
     * 解析并验签令牌。
     *
     * @throws JwtException 令牌格式错误、签名不符或已过期（过期为 {@code ExpiredJwtException} 子类）
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** 从已解析的载荷取用户 ID；非法则返回 null */
    public UUID extractUserId(Claims claims) {
        try {
            return UUID.fromString(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    /** 从已解析的载荷取令牌版本号；缺失按 0 处理 */
    public int extractTokenVersion(Claims claims) {
        Object ver = claims.get("ver");
        return ver instanceof Number n ? n.intValue() : 0;
    }
}
