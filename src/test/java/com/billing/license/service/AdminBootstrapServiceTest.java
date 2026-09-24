package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdminBootstrapService 测试（初始管理员 bootstrap）。
 *
 * <p>覆盖四条关键语义：
 * <ol>
 *   <li><b>只要有管理员，配置完全失效</b>——不新建也不提权。这是防「管理员被误删后，
 *       残留的环境变量被人拿来重开后门」的核心底线；</li>
 *   <li>空库 + 全新邮箱 → 建 ADMIN，密码经 BCrypt 散列、邮箱视为已验证；</li>
 *   <li>空库 + 邮箱已存在 → 只提权，<b>不动其原密码</b>（不许配置里的口令顶掉用户正在用的密码）；</li>
 *   <li>只配一半 / 口令不合策略 → 拒绝，且不在库里留下半个管理员。</li>
 * </ol>
 */
class AdminBootstrapServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private AccountProperties props(String email, String password) {
        AccountProperties properties = new AccountProperties();
        properties.getBootstrapAdmin().setEmail(email);
        properties.getBootstrapAdmin().setPassword(password);
        return properties;
    }

    private AdminBootstrapService service(AccountProperties properties) {
        return new AdminBootstrapService(userRepository, passwordEncoder, properties);
    }

    @Test
    void bootstrap_existingAdmin_configIgnoredEntirely() {
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(1L);

        service(props("admin@example.com", "Passw0rd123")).bootstrap();

        // 既有管理员时连查找都不该发生，更不该写入
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void bootstrap_notConfigured_warnOnlyWithoutWrite() {
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(0L);

        service(props("", "")).bootstrap();

        verify(userRepository, never()).save(any());
    }

    @Test
    void bootstrap_emptyDb_createsAdminWithHashedPassword() {
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(0L);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Passw0rd123")).thenReturn("hashed-secret");

        // 邮箱大小写应当被归一化后再查库
        service(props(" Admin@Example.com ", "Passw0rd123")).bootstrap();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();
        assertEquals("admin@example.com", created.getEmail());
        assertEquals(User.UserRole.ADMIN, created.getRole());
        assertEquals(User.UserStatus.ACTIVE, created.getStatus());
        assertEquals("hashed-secret", created.getPasswordHash());
        assertTrue(created.isEmailVerified());
    }

    @Test
    void bootstrap_existingEmail_promotesWithoutTouchingPassword() {
        User existing = User.builder()
                .id(java.util.UUID.randomUUID())
                .email("admin@example.com")
                .passwordHash("original-hash")
                .role(User.UserRole.USER)
                .status(User.UserStatus.ACTIVE)
                .build();
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(0L);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

        service(props("admin@example.com", "Passw0rd123")).bootstrap();

        assertEquals(User.UserRole.ADMIN, existing.getRole());
        assertEquals("original-hash", existing.getPasswordHash(), "提权不得改动用户原有密码");
        verify(userRepository).save(existing);
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void bootstrap_partialConfig_rejected() {
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(0L);

        assertThrows(IllegalStateException.class,
                () -> service(props("admin@example.com", "")).bootstrap());

        verify(userRepository, never()).save(any());
    }

    @Test
    void bootstrap_weakPassword_rejectedBySharedPolicy() {
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(0L);

        // 低于 password-min-length 默认 8 位
        assertThrows(BusinessException.class,
                () -> service(props("admin@example.com", "abc12")).bootstrap());

        verify(userRepository, never()).save(any());
    }
}
