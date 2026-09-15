package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.entity.VerificationCode;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.VerificationCodeRepository;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.service.risk.RateLimitService;
import com.billing.license.util.KeyHashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VerificationCodeService} 单元测试：只存哈希、一次性消费、错误计数作废、过期与无效区分。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerificationCodeServiceTest {

    private static final String EMAIL = "User@Example.com";
    private static final String NORMALIZED = "user@example.com";

    @Mock private VerificationCodeRepository codeRepository;
    @Mock private RateLimitService rateLimitService;
    @Mock private EmailNotificationService emailNotificationService;

    private AccountProperties properties;
    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        // 联调模式：避免测试真的去发邮件
        properties.setCodeLogOnly(true);
        properties.setCodePepper("test-pepper");
        service = new VerificationCodeService(codeRepository, rateLimitService, properties, emailNotificationService);
    }

    @Test
    void send_storesHashOnlyAndInvalidatesOldCodes() {
        service.send(EMAIL, VerificationCode.CodePurpose.REGISTER, "1.2.3.4");

        ArgumentCaptor<VerificationCode> captor = ArgumentCaptor.forClass(VerificationCode.class);
        verify(codeRepository).save(captor.capture());
        VerificationCode saved = captor.getValue();

        assertEquals(NORMALIZED, saved.getEmail(), "邮箱须归一化为小写");
        assertEquals(6, properties.getCodeLength());
        assertNotNull(saved.getCodeHash());
        assertEquals(KeyHashUtil.sha256Hex("test-pepper" + "000000").length(), saved.getCodeHash().length(),
            "存的是 SHA-256 十六进制，不是明文");
        verify(codeRepository).invalidateUnused(eq(NORMALIZED), eq(VerificationCode.CodePurpose.REGISTER), any());
    }

    @Test
    void send_rateLimited_throws() {
        when(rateLimitService.checkAndCount(anyString(), anyString(), anyInt(), anyInt()))
            .thenThrow(new RateLimitService.RateLimitExceededException("acct-code-cooldown", NORMALIZED, 2, 1));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.send(EMAIL, VerificationCode.CodePurpose.REGISTER, "1.2.3.4"));
        assertEquals("CODE_SEND_TOO_FREQUENT", ex.getErrorCode());
    }

    @Test
    void verifyAndConsume_correctCode_marksConsumed() {
        String plain = "123456";
        VerificationCode record = recordWith(hashOf(plain), LocalDateTime.now().plusMinutes(10), 0);
        when(codeRepository.findUsable(eq(NORMALIZED), eq(VerificationCode.CodePurpose.REGISTER), any()))
            .thenReturn(List.of(record));

        service.verifyAndConsume(EMAIL, VerificationCode.CodePurpose.REGISTER, plain);

        assertNotNull(record.getConsumedAt(), "校验通过后须立即消费，防止重放");
    }

    @Test
    void verifyAndConsume_wrongCode_incrementsAttempt() {
        VerificationCode record = recordWith(hashOf("123456"), LocalDateTime.now().plusMinutes(10), 0);
        when(codeRepository.findUsable(eq(NORMALIZED), eq(VerificationCode.CodePurpose.REGISTER), any()))
            .thenReturn(List.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verifyAndConsume(EMAIL, VerificationCode.CodePurpose.REGISTER, "999999"));

        assertEquals("CODE_INVALID", ex.getErrorCode());
        assertEquals(1, record.getAttemptCount());
    }

    @Test
    void verifyAndConsume_exhaustedAttempts_invalidatesCode() {
        VerificationCode record = recordWith(hashOf("123456"), LocalDateTime.now().plusMinutes(10),
            properties.getRisk().getCodeVerifyFailMax() - 1);
        when(codeRepository.findUsable(eq(NORMALIZED), eq(VerificationCode.CodePurpose.REGISTER), any()))
            .thenReturn(List.of(record));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verifyAndConsume(EMAIL, VerificationCode.CodePurpose.REGISTER, "999999"));

        assertEquals("CODE_TOO_MANY_ATTEMPTS", ex.getErrorCode());
        assertNotNull(record.getConsumedAt(), "达到失败上限须作废该码");
    }

    @Test
    void verifyAndConsume_expiredCode_distinctErrorCode() {
        when(codeRepository.findUsable(eq(NORMALIZED), eq(VerificationCode.CodePurpose.REGISTER), any()))
            .thenReturn(List.of());
        when(codeRepository.findUnconsumed(eq(NORMALIZED), eq(VerificationCode.CodePurpose.REGISTER)))
            .thenReturn(List.of(recordWith(hashOf("123456"), LocalDateTime.now().minusMinutes(1), 0)));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.verifyAndConsume(EMAIL, VerificationCode.CodePurpose.REGISTER, "123456"));

        assertEquals("CODE_EXPIRED", ex.getErrorCode(), "过期须与「输错」区分开");
    }

    private String hashOf(String code) {
        return KeyHashUtil.sha256Hex(properties.getCodePepper() + code);
    }

    private VerificationCode recordWith(String codeHash, LocalDateTime expiresAt, int attempts) {
        return VerificationCode.builder()
            .email(NORMALIZED)
            .purpose(VerificationCode.CodePurpose.REGISTER)
            .codeHash(codeHash)
            .expiresAt(expiresAt)
            .attemptCount(attempts)
            .build();
    }
}
