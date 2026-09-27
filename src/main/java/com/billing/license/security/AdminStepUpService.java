package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端敏感动作二次确认（step-up）令牌（plan-7.0 账户基础功能 / P2 · T05）。
 *
 * <p><b>保护对象</b>：代重置密码、改角色、停用 / 启用——这三类动作本身就能「接管账号」或
 * 「锁死管理台」，值得在普通访问令牌之上再要一道<b>刚发生的</b>第二因子证明。典型的缓解场景：
 * 管理员离开工位未锁屏，或令牌被浏览器扩展短窗窃取——攻击者拿着令牌仍过不了这一步。
 *
 * <p><b>三个硬性质</b>（缺一不可）：
 * <ul>
 *   <li><b>短时效</b>：TTL 默认 120 秒（{@code account.mfa.step-up-ttl-seconds}），
 *       远短于登录票据——它只是「即将执行某个具体动作」的临门一脚，不是会话凭证；</li>
 *   <li><b>单次使用</b>：JWT 本身无状态，做不到单次——故签发时带 {@code jti}，
 *       消费方在内存登记已消费的 {@code jti}（重启即清空，与 IP 限流同源的单实例限制）；</li>
 *   <li><b>动作绑定</b>：令牌携带 {@code act} 声明，为「改状态」取得的确认<b>不能</b>拿去代重置——
 *       否则一次确认可以被挪用给连环敏感操作。</li>
 * </ul>
 *
 * <p><b>为什么不复用普通访问 JWT 充当二次确认</b>：访问令牌 7 天有效、可重复使用，
 * 把它当「二次确认」等于没确认。本令牌用 {@link MfaKeyDeriver#ticketSigningKey()} 同源派生密钥
 * 签名，但 {@code typ} 固定为 {@code mfa-stepup}——登录票据、访问令牌在此处签名结构不符，
 * 必然被拒，反之亦然（该性质由单测固化）。
 *
 * <p><b>适用范围</b>：只对已开启 MFA 的管理员生效。MFA 默认关（B8 定案），未开启者
 * 没有可验证的第二因子，不因 P2 增强改变既有默认口径。
 */
@Service
public class AdminStepUpService {

    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_STEP_UP = "mfa-stepup";
    private static final String CLAIM_ACTION = "act";
    private static final String CLAIM_VERSION = "ver";

    /** 受 step-up 保护的敏感动作（与审计 action 同名，便于对账） */
    public static final String ACTION_RESET_USER_PASSWORD = "ADMIN_RESET_USER_PASSWORD";
    public static final String ACTION_CHANGE_USER_ROLE = "CHANGE_USER_ROLE";
    public static final String ACTION_CHANGE_USER_STATUS = "CHANGE_USER_STATUS";

    /** 已消费的 jti → 令牌过期时间（毫秒）。过期条目在消费时惰性清理，量级极小 */
    private final Map<String, Long> consumedTokens = new ConcurrentHashMap<>();

    private final AccountProperties.Mfa config;
    private final SecretKey signingKey;

    public AdminStepUpService(AccountProperties properties, MfaKeyDeriver keyDeriver) {
        this.config = properties.getMfa();
        this.signingKey = keyDeriver.ticketSigningKey();
    }

    /** 该动作是否受 step-up 保护（未知动作在签发侧即拒绝，防止拼错动作名造成「伪确认」）。 */
    public static boolean isProtectedAction(String action) {
        return ACTION_RESET_USER_PASSWORD.equals(action)
            || ACTION_CHANGE_USER_ROLE.equals(action)
            || ACTION_CHANGE_USER_STATUS.equals(action);
    }

    /**
     * 为指定管理员的指定敏感动作签发确认令牌。
     *
     * <p>{@code ver} 取签发时的 {@code tokenVersion}：管理员改密 / 登出后，已签发的
     * 确认令牌随之失效（与登录票据同机制）。
     */
    public String issue(User operator, String action) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(operator.getId().toString())
            .claim(CLAIM_VERSION, operator.getTokenVersion())
            .claim(CLAIM_ACTION, action)
            .claim(CLAIM_TYPE, TYPE_STEP_UP)
            .id(UUID.randomUUID().toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(config.getStepUpTtlSeconds(), ChronoUnit.SECONDS)))
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact();
    }

    /** 确认令牌有效期（秒），供响应体告知前端。 */
    public int expiresInSeconds() {
        return config.getStepUpTtlSeconds();
    }

    /**
     * 消费确认令牌：验签 + 比对身份 / 动作 / 令牌版本 + 单次使用登记。
     *
     * <p>通过即返回；任何不满足都抛业务异常——<b>缺令牌</b>与<b>令牌无效</b>分开报，
     * 前端据前者弹确认框、据后者提示重试。
     *
     * @throws BusinessException MFA_STEP_UP_REQUIRED（未携带）/ MFA_STEP_UP_INVALID（其余一切）
     */
    public void consume(UUID operatorId, int currentTokenVersion, String action, String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("MFA_STEP_UP_REQUIRED", "该操作需要二次确认：请先校验动态码");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
            if (!TYPE_STEP_UP.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new JwtException("非二次确认令牌");
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException("MFA_STEP_UP_INVALID", "二次确认已过期或无效，请重新校验");
        }
        if (!operatorId.toString().equals(claims.getSubject())
            || !action.equals(claims.get(CLAIM_ACTION, String.class))
            || currentTokenVersion != claims.get(CLAIM_VERSION, Integer.class)) {
            throw new BusinessException("MFA_STEP_UP_INVALID", "二次确认与当前操作不匹配，请重新校验");
        }
        String jti = claims.getId();
        long expiresAtMs = claims.getExpiration().getTime();
        if (jti == null || consumedTokens.putIfAbsent(jti, expiresAtMs) != null) {
            throw new BusinessException("MFA_STEP_UP_INVALID", "该二次确认已使用，请重新校验");
        }
        evictExpired();
    }

    /** 清掉已过期令牌的占位条目：过期本身已由 JWT exp 拦截，这里只为内存不涨。 */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        consumedTokens.values().removeIf(expiry -> expiry < now);
    }
}
