package com.billing.license.security;

import com.billing.license.config.AccountProperties;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AdminStepUpService} 单测（plan-7.0 / P2 · T05）。
 *
 * <p>与 {@code MfaServiceTest} 同哲学：用<b>真实密码学组件</b>（{@link MfaKeyDeriver} 派生密钥、
 * 真实 JWT 签发验签），只把 TTL 配置当变量。固化三个硬性质——短时效、单次使用、动作绑定——
 * 以及「登录票据不能冒充二次确认令牌」的密钥类型隔离。
 */
class AdminStepUpServiceTest {

    private static final String MASTER_KEY = "mfa-master-key-for-unit-tests-0123456789abcdef";

    private AccountProperties properties;
    private AdminStepUpService service;
    private User operator;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        properties.getMfa().setKey(MASTER_KEY);
        service = new AdminStepUpService(properties, new MfaKeyDeriver(properties));

        operator = User.builder()
            .id(UUID.randomUUID())
            .email("admin@example.com")
            .passwordHash("x")
            .role(User.UserRole.ADMIN)
            .status(User.UserStatus.ACTIVE)
            .tokenVersion(3)
            .build();
    }

    @Test
    @DisplayName("签发 → 消费：身份 / 动作 / 令牌版本全部匹配时放行")
    void issueThenConsume_shouldPass() {
        String token = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_STATUS);

        assertDoesNotThrow(() -> service.consume(operator.getId(), 3,
            AdminStepUpService.ACTION_CHANGE_USER_STATUS, token));
    }

    @Test
    @DisplayName("未携带令牌 → MFA_STEP_UP_REQUIRED（前端据此弹确认框）")
    void consume_withoutToken_shouldRequire() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 3, AdminStepUpService.ACTION_CHANGE_USER_STATUS, null));
        assertEquals("MFA_STEP_UP_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("单次使用：同一令牌消费第二次必须被拒")
    void consume_twice_shouldRejectSecondUse() {
        String token = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);
        service.consume(operator.getId(), 3, AdminStepUpService.ACTION_CHANGE_USER_ROLE, token);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 3, AdminStepUpService.ACTION_CHANGE_USER_ROLE, token));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("动作绑定：为改角色取得的确认不能拿去代重置")
    void consume_withDifferentAction_shouldReject() {
        String token = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 3, AdminStepUpService.ACTION_RESET_USER_PASSWORD, token));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("身份绑定：别人的令牌不能给另一个管理员用")
    void consume_withDifferentUser_shouldReject() {
        String token = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            UUID.randomUUID(), 3, AdminStepUpService.ACTION_CHANGE_USER_ROLE, token));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    /** 操作者改密 / 登出会递增 tokenVersion——签发时记下的版本随即失配，令牌作废。 */
    @Test
    @DisplayName("令牌版本失配（操作者已改密 / 登出）→ 拒绝")
    void consume_afterTokenVersionBump_shouldReject() {
        String token = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 4, AdminStepUpService.ACTION_CHANGE_USER_ROLE, token));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    /** 短时效：TTL=0 即签发即过期。 */
    @Test
    @DisplayName("过期令牌 → 拒绝")
    void consume_expiredToken_shouldReject() {
        properties.getMfa().setStepUpTtlSeconds(0);
        AdminStepUpService zeroTtl = new AdminStepUpService(properties, new MfaKeyDeriver(properties));
        String token = zeroTtl.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 3, AdminStepUpService.ACTION_CHANGE_USER_ROLE, token));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    /**
     * 密钥类型隔离：登录票据（typ=mfa）与二次确认令牌（typ=mfa-stepup）虽同源派生密钥，
     * 但类型声明互斥——拿登录票据冒充二次确认必须被拒，反之亦然。
     */
    @Test
    @DisplayName("登录票据不能冒充二次确认令牌")
    void consume_loginTicketAsStepUp_shouldReject() {
        String loginTicket = new MfaTicketService(properties, new MfaKeyDeriver(properties))
            .issue(operator.getId(), 3);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 3, AdminStepUpService.ACTION_CHANGE_USER_ROLE, loginTicket));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    /** 篡改载荷（换动作）后签名不再匹配 → 拒绝。 */
    @Test
    @DisplayName("被篡改的令牌 → 拒绝")
    void consume_tamperedToken_shouldReject() {
        String token = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        BusinessException ex = assertThrows(BusinessException.class, () -> service.consume(
            operator.getId(), 3, AdminStepUpService.ACTION_CHANGE_USER_ROLE, tampered));
        assertEquals("MFA_STEP_UP_INVALID", ex.getErrorCode());
    }

    @Test
    @DisplayName("签发的令牌各不相同（jti 唯一），且受保护动作清单判断准确")
    void issue_shouldProduceDistinctTokens() {
        String t1 = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);
        String t2 = service.issue(operator, AdminStepUpService.ACTION_CHANGE_USER_ROLE);
        assertNotEquals(t1, t2);

        assertTrue(AdminStepUpService.isProtectedAction(AdminStepUpService.ACTION_RESET_USER_PASSWORD));
        assertFalse(AdminStepUpService.isProtectedAction("SOMETHING_ELSE"));

        Claims claims = io.jsonwebtoken.Jwts.parser()
            .verifyWith(new MfaKeyDeriver(properties).ticketSigningKey())
            .build()
            .parseSignedClaims(t1)
            .getPayload();
        assertEquals(120, service.expiresInSeconds());
        assertTrue(claims.getExpiration().getTime() - claims.getIssuedAt().getTime() >= 1000);
    }
}
