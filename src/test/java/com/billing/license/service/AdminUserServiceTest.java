package com.billing.license.service;

import com.billing.license.dto.AdminUserView;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * AdminUserService 测试（plan-7.0 / D4，B9 = B）。
 *
 * <p>覆盖两类动作（角色 / 状态）的三道关键语义：
 * ① 变更后必须 {@code tokenVersion + 1}（令旧令牌失效）；
 * ② 护栏——禁止操作自身、禁止降级 / 停用最后一个管理员；
 * ③ 用户不存在与同值幂等。
 * 审计由控制器层 {@code @Audit} 声明，此处不重复验证切面。
 */
class AdminUserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminUserService service = new AdminUserService(userRepository);

    private User user(UUID id, User.UserRole role, User.UserStatus status, int tokenVersion) {
        return User.builder()
            .id(id).email("u@example.com").passwordHash("x")
            .role(role).status(status).tokenVersion(tokenVersion)
            .build();
    }

    @Test
    void changeRole_shouldPromoteAndInvalidateTokens() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(2L);

        AdminUserView view = service.changeRole(operator, targetId, User.UserRole.ADMIN);

        assertEquals("ADMIN", view.getRole());
        assertEquals(User.UserRole.ADMIN, target.getRole());
        assertEquals(1, target.getTokenVersion());
        verify(userRepository).save(target);
    }

    @Test
    void changeRole_shouldDemoteNonLastAdmin() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 3);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(2L);

        service.changeRole(operator, targetId, User.UserRole.USER);

        assertEquals(User.UserRole.USER, target.getRole());
        assertEquals(4, target.getTokenVersion());
    }

    @Test
    void changeRole_shouldRejectLastAdminDemotion() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operator, targetId, User.UserRole.USER));
        assertEquals("LAST_ADMIN_PROTECTED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeRole_shouldRejectSelfOperation() {
        UUID operator = UUID.randomUUID();
        User target = user(operator, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(operator)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operator, operator, User.UserRole.ADMIN));
        assertEquals("SELF_OPERATION_NOT_ALLOWED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeRole_shouldRejectUnknownUser() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operator, targetId, User.UserRole.ADMIN));
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void changeRole_shouldBeIdempotentWhenSameRole() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 5);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        AdminUserView view = service.changeRole(operator, targetId, User.UserRole.ADMIN);

        assertEquals("ADMIN", view.getRole());
        assertEquals(5, target.getTokenVersion());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeStatus_shouldDisableAndInvalidateTokens() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        service.changeStatus(operator, targetId, User.UserStatus.DISABLED);

        assertEquals(User.UserStatus.DISABLED, target.getStatus());
        assertEquals(1, target.getTokenVersion());
        verify(userRepository).save(target);
    }

    @Test
    void changeStatus_shouldRejectDisablingLastAdmin() {
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeStatus(operator, targetId, User.UserStatus.DISABLED));
        assertEquals("LAST_ADMIN_PROTECTED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeStatus_shouldRejectSelfOperation() {
        UUID operator = UUID.randomUUID();
        User target = user(operator, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(operator)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeStatus(operator, operator, User.UserStatus.DISABLED));
        assertEquals("SELF_OPERATION_NOT_ALLOWED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }
}
