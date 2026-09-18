package com.billing.license.service;

import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CustomerIdentityService 单元测试（plan-3.0 §三 E1 / E5）。
 *
 * <p>覆盖：未注册邮箱自动建访客账户、已注册邮箱复用同一 userId、邮箱归一化、
 * 空/非法邮箱报错、并发唯一索引冲突重查兜底、重复调用仅建一条 User、只读解析不建号。
 */
class CustomerIdentityServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private CustomerIdentityService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$encoded-random");
        service = new CustomerIdentityService(userRepository, passwordEncoder);
    }

    @Test
    void resolveOrCreate_shouldCreateGuestAccount_whenEmailUnregistered() {
        UUID generated = UUID.randomUUID();
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(generated);
            return u;
        });

        UUID id = service.resolveOrCreate("New@Example.com ");

        assertEquals(generated, id);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("new@example.com", saved.getEmail(), "邮箱须归一化为小写去空白");
        assertEquals(User.UserStatus.ACTIVE, saved.getStatus());
        assertFalse(saved.isEmailVerified(), "访客账户邮箱未验证（供后续认领）");
        assertEquals(0, saved.getTokenVersion());
        assertNotNull(saved.getPasswordHash(), "访客账户须带随机不可登录密码哈希");
    }

    @Test
    void resolveOrCreate_shouldReturnExistingId_whenEmailRegistered() {
        UUID existing = UUID.randomUUID();
        User user = User.builder().id(existing).email("member@example.com").build();
        when(userRepository.findByEmail("member@example.com")).thenReturn(Optional.of(user));

        UUID id = service.resolveOrCreate("member@example.com");

        assertEquals(existing, id);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resolveOrCreate_shouldNormalizeQueryEmail() {
        UUID existing = UUID.randomUUID();
        when(userRepository.findByEmail("buyer@example.com"))
            .thenReturn(Optional.of(User.builder().id(existing).email("buyer@example.com").build()));

        service.resolveOrCreate("  Buyer@Example.COM  ");

        verify(userRepository).findByEmail("buyer@example.com");
    }

    @Test
    void resolveOrCreate_shouldThrowEmailRequired_whenBlank() {
        for (String blank : new String[]{null, "", "   "}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> service.resolveOrCreate(blank));
            assertEquals("EMAIL_REQUIRED", ex.getErrorCode());
        }
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resolveOrCreate_shouldThrowInvalidEmail_whenMalformed() {
        for (String bad : new String[]{"not-an-email", "a@b", "@example.com", "a b@example.com"}) {
            BusinessException ex = assertThrows(BusinessException.class, () -> service.resolveOrCreate(bad));
            assertEquals("INVALID_EMAIL", ex.getErrorCode());
        }
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resolveOrCreate_shouldRequeryExisting_onConcurrentUniqueViolation() {
        UUID existing = UUID.randomUUID();
        when(userRepository.findByEmail("race@example.com"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(User.builder().id(existing).email("race@example.com").build()));
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        UUID id = service.resolveOrCreate("race@example.com");

        assertEquals(existing, id, "并发唯一索引冲突应重查返回既有账户，不抛错");
    }

    @Test
    void resolveOrCreate_shouldCreateOnlyOneUser_onRepeatedCalls() {
        String email = "repeat@example.com";
        UUID generated = UUID.randomUUID();
        AtomicReference<User> store = new AtomicReference<>();
        when(userRepository.findByEmail(email)).thenAnswer(i -> Optional.ofNullable(store.get()));
        when(userRepository.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId(generated);
            store.set(u);
            return u;
        });

        UUID first = service.resolveOrCreate(email);
        UUID second = service.resolveOrCreate(email);

        assertEquals(first, second);
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void resolveExisting_shouldReturnId_whenRegistered() {
        UUID existing = UUID.randomUUID();
        when(userRepository.findByEmail("known@example.com"))
            .thenReturn(Optional.of(User.builder().id(existing).email("known@example.com").build()));

        assertEquals(Optional.of(existing), service.resolveExisting(" Known@Example.com "));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resolveExisting_shouldReturnEmptyAndNotCreate_whenUnknown() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertTrue(service.resolveExisting("ghost@example.com").isEmpty());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void maskEmail_shouldMaskLocalPart() {
        assertEquals("a***@x.com", CustomerIdentityService.maskEmail("abc@x.com"));
        assertEquals("", CustomerIdentityService.maskEmail(null));
        assertEquals("***", CustomerIdentityService.maskEmail("bad"));
    }
}
