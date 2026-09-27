package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AdminUserDetailView;
import com.billing.license.dto.AdminUserView;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.service.notification.EmailNotificationService;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminUserService 测试（plan-7.0 / D4，B9 = B；账户基础功能扩展）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>角色 / 状态变更后必须 {@code tokenVersion + 1}（令旧令牌失效）；</li>
 *   <li>护栏——禁止操作自身、禁止降级 / 停用最后一个<b>可登录</b>管理员；</li>
 *   <li>用户不存在与同值幂等；</li>
 *   <li>管理员代重置：写新哈希 + {@code tokenVersion + 1} + 置强制改密标记（同一次 save）；</li>
 *   <li>列表查询：过滤条件下推、稳定排序与页大小上限。</li>
 * </ol>
 * 审计由控制器层 {@code @Audit} 声明，此处不重复验证切面。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private LicenseRepository licenseRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private EmailNotificationService emailNotificationService;
    @Mock private com.billing.license.security.AdminStepUpService stepUpService;
    @Mock private PasswordEncoder passwordEncoder;

    private final AccountProperties properties = new AccountProperties();

    private AdminUserService service() {
        return new AdminUserService(userRepository, licenseRepository, orderRepository,
            passwordEncoder, emailNotificationService, stepUpService, properties);
    }

    /** 用真实 AdminStepUpService（真实 JWT 行为）验证 step-up 护栏本身，而非 mock 的调用序列。 */
    private AdminUserService serviceWithRealStepUp() {
        AccountProperties props = new AccountProperties();
        props.getMfa().setKey("mfa-master-key-for-unit-tests-0123456789abcdef");
        return new AdminUserService(userRepository, licenseRepository, orderRepository,
            passwordEncoder, emailNotificationService,
            new com.billing.license.security.AdminStepUpService(props, new com.billing.license.security.MfaKeyDeriver(props)),
            props);
    }

    private User user(UUID id, User.UserRole role, User.UserStatus status, int tokenVersion) {
        return User.builder()
            .id(id).email("u@example.com").passwordHash("x")
            .role(role).status(status).tokenVersion(tokenVersion)
            .build();
    }

    /**
     * P2 step-up 护栏会按<b>操作者</b> ID 现查 MFA 开关（不查就放行）。默认桩：任意 ID
     * 一律返回「未开启 MFA 的普通用户」，具体用例再对目标 ID 覆盖更精确的返回——
     * 后写的 stub 优先，既有用例无需逐一补操作者桩。
     */
    @BeforeEach
    void stubOperatorLookupDefaultsToNonMfaUser() {
        when(userRepository.findById(any(UUID.class))).thenAnswer(inv ->
            Optional.of(user(inv.getArgument(0, UUID.class), User.UserRole.USER, User.UserStatus.ACTIVE, 0)));
    }

    private User mfaOperator(UUID id) {
        User operator = user(id, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        operator.setMfaEnabled(true);
        return operator;
    }

    @Test
    void changeRole_shouldPromoteAndInvalidateTokens() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(User.UserRole.ADMIN, User.UserStatus.ACTIVE)).thenReturn(2L);

        AdminUserView view = service.changeRole(operator, targetId, User.UserRole.ADMIN, null);

        assertEquals("ADMIN", view.getRole());
        assertEquals(User.UserRole.ADMIN, target.getRole());
        assertEquals(1, target.getTokenVersion());
        verify(userRepository).save(target);
    }

    @Test
    void changeRole_shouldDemoteNonLastAdmin() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 3);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(User.UserRole.ADMIN, User.UserStatus.ACTIVE)).thenReturn(2L);

        service.changeRole(operator, targetId, User.UserRole.USER, null);

        assertEquals(User.UserRole.USER, target.getRole());
        assertEquals(4, target.getTokenVersion());
    }

    /** 只剩一个 ACTIVE ADMIN 时不得降级；且不得再用 countByRole（它会把 DISABLED 管理员算成活人）。 */
    @Test
    void changeRole_shouldRejectLastActiveAdminDemotion() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndStatus(User.UserRole.ADMIN, User.UserStatus.ACTIVE)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operator, targetId, User.UserRole.USER, null));
        assertEquals("LAST_ADMIN_PROTECTED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).countByRole(User.UserRole.ADMIN);
        // 并发竞态护栏：计数前必须先取悲观锁，把并发的降权/停用串行化
        verify(userRepository).findActiveAdminsForUpdate(User.UserRole.ADMIN, User.UserStatus.ACTIVE);
    }

    @Test
    void changeRole_shouldRejectSelfOperation() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        User target = user(operator, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(operator)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operator, operator, User.UserRole.ADMIN, null));
        assertEquals("SELF_OPERATION_NOT_ALLOWED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeRole_shouldRejectUnknownUser() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operator, targetId, User.UserRole.ADMIN, null));
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void changeRole_shouldBeIdempotentWhenSameRole() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 5);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        AdminUserView view = service.changeRole(operator, targetId, User.UserRole.ADMIN, null);

        assertEquals("ADMIN", view.getRole());
        assertEquals(5, target.getTokenVersion());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changeStatus_shouldDisableAndInvalidateTokens() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        service.changeStatus(operator, targetId, User.UserStatus.DISABLED, null);

        assertEquals(User.UserStatus.DISABLED, target.getStatus());
        assertEquals(1, target.getTokenVersion());
        verify(userRepository).save(target);
    }

    /**
     * 本次修的缺陷：只剩一个 ACTIVE ADMIN、另有若干 DISABLED ADMIN 时，
     * 旧口径 {@code countByRole(ADMIN)} 会数出 &gt;1 而放行停用，把管理台彻底锁死。
     */
    @Test
    void changeStatus_shouldRejectDisablingLastActiveAdmin_evenWhenDisabledAdminExists() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        // 口径本身即答案：只有「ACTIVE + ADMIN」被计入，DISABLED 管理员不算存活管理员
        when(userRepository.countByRoleAndStatus(User.UserRole.ADMIN, User.UserStatus.ACTIVE)).thenReturn(1L);
        when(userRepository.countByRole(User.UserRole.ADMIN)).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeStatus(operator, targetId, User.UserStatus.DISABLED, null));
        assertEquals("LAST_ADMIN_PROTECTED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).countByRole(User.UserRole.ADMIN);
    }

    @Test
    void changeStatus_shouldRejectSelfOperation() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        User target = user(operator, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(operator)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeStatus(operator, operator, User.UserStatus.DISABLED, null));
        assertEquals("SELF_OPERATION_NOT_ALLOWED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    // ==================== 管理员代重置密码 ====================

    /** 同一事务内完成：写新哈希 + tokenVersion+1 + 置强制改密标记（表现为一次 save）。 */
    @Test
    void resetPassword_shouldUpdateHashBumpTokenVersionAndForceChange() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 2);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode("NewPassw0rd")).thenReturn("NEW_HASH");

        service.resetPassword(operator, targetId, "NewPassw0rd", null);

        assertEquals("NEW_HASH", target.getPasswordHash());
        assertEquals(3, target.getTokenVersion(), "旧会话须立即失效");
        assertTrue(target.isMustChangePassword(), "代重置后须强制用户下次登录改密");
        verify(userRepository).save(target);
    }

    @Test
    void resetPassword_shouldRejectSelfOperation() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        User target = user(operator, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(operator)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.resetPassword(operator, operator, "NewPassw0rd", null));
        assertEquals("SELF_OPERATION_NOT_ALLOWED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPassword_shouldRejectWeakPassword() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.resetPassword(operator, targetId, "short", null));
        assertEquals("PASSWORD_POLICY_VIOLATION", ex.getErrorCode());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_shouldRejectPasswordWithoutDigit() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.resetPassword(operator, targetId, "PasswordOnly", null));
        assertEquals("PASSWORD_POLICY_VIOLATION", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPassword_shouldRejectUnknownUser() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.resetPassword(operator, targetId, "NewPassw0rd", null));
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    /** P1：代重置成功后须向目标用户发送安全提醒（场景 ADMIN_RESET）；密码本身不在任何参数里。 */
    @Test
    void resetPassword_sendsPasswordChangedNotificationToTarget() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(passwordEncoder.encode("NewPassw0rd")).thenReturn("NEW_HASH");

        service.resetPassword(operator, targetId, "NewPassw0rd", null);

        verify(emailNotificationService).sendPasswordChangedEmail("u@example.com", "ADMIN_RESET");
    }

    /** 校验失败 / 护栏拦截时不得发通知：动作并未发生。 */
    @Test
    void resetPassword_rejected_sendsNoNotification() {
        AdminUserService service = service();
        UUID operator = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        assertThrows(BusinessException.class,
            () -> service.resetPassword(operator, targetId, "short", null));

        verify(emailNotificationService, never()).sendPasswordChangedEmail(anyString(), anyString());
    }

    // ==================== 用户详情（P1） ====================

    /** 详情 = 脱敏资料 + 名下许可证/订单计数聚合；沿用 AdminUserView 脱敏口径。 */
    @Test
    void getUserDetail_shouldAggregateCountsAndSafeView() {
        AdminUserService service = service();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 4);
        target.setMfaEnabled(true);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(licenseRepository.countByCustomerId(targetId)).thenReturn(3L);
        when(orderRepository.countByCustomerId(targetId)).thenReturn(5L);

        AdminUserDetailView detail = service.getUserDetail(targetId);

        assertEquals(targetId, detail.getUser().getId());
        assertEquals("u@example.com", detail.getUser().getEmail());
        assertTrue(detail.getUser().isMfaEnabled());
        assertEquals(3L, detail.getLicenseCount());
        assertEquals(5L, detail.getOrderCount());
    }

    @Test
    void getUserDetail_shouldRejectUnknownUser() {
        AdminUserService service = service();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.getUserDetail(targetId));
        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
    }

    // ==================== 敏感动作二次确认（P2 step-up） ====================

    /** 操作者已开启 MFA：缺令牌必须在动作发生前被拒（不得已写哈希/改状态再补查）。 */
    @Test
    void resetPassword_mfaOperator_withoutToken_shouldRequireStepUp() {
        AdminUserService service = serviceWithRealStepUp();
        UUID operatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(operatorId)).thenReturn(Optional.of(mfaOperator(operatorId)));
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
            user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0)));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.resetPassword(operatorId, targetId, "NewPassw0rd", null));
        assertEquals("MFA_STEP_UP_REQUIRED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    /** 操作者已开启 MFA：令牌有效（单测以 mock 消费成功表示）→ 动作放行。 */
    @Test
    void resetPassword_mfaOperator_withToken_shouldConsumeAndProceed() {
        AdminUserService service = service();
        UUID operatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(operatorId)).thenReturn(Optional.of(mfaOperator(operatorId)));
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
            user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 2)));
        when(passwordEncoder.encode("NewPassw0rd")).thenReturn("NEW_HASH");

        service.resetPassword(operatorId, targetId, "NewPassw0rd", "step-up-token");

        verify(stepUpService).consume(operatorId, 0, "ADMIN_RESET_USER_PASSWORD", "step-up-token");
        verify(userRepository).save(any(User.class));
    }

    /** 未开启 MFA 的操作者：没有第二因子可验证，不要求 step-up（B8 默认口径）。 */
    @Test
    void resetPassword_nonMfaOperator_shouldSkipStepUp() {
        AdminUserService service = service();
        UUID operatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
            user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0)));
        when(passwordEncoder.encode("NewPassw0rd")).thenReturn("NEW_HASH");

        service.resetPassword(operatorId, targetId, "NewPassw0rd", null);

        verify(stepUpService, never()).consume(any(), org.mockito.ArgumentMatchers.anyInt(),
            anyString(), anyString());
    }

    /** 改角色同样受 step-up 保护，且动作绑定正确（改角色的确认不能挪用给别处）。 */
    @Test
    void changeRole_mfaOperator_withoutToken_shouldRequireStepUp() {
        AdminUserService service = serviceWithRealStepUp();
        UUID operatorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.findById(operatorId)).thenReturn(Optional.of(mfaOperator(operatorId)));
        when(userRepository.findById(targetId)).thenReturn(Optional.of(
            user(targetId, User.UserRole.USER, User.UserStatus.ACTIVE, 0)));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.changeRole(operatorId, targetId, User.UserRole.ADMIN, null));
        assertEquals("MFA_STEP_UP_REQUIRED", ex.getErrorCode());
        verify(userRepository, never()).save(any());
    }

    // ==================== 列表查询 ====================

    @Test
    void listUsers_shouldMapToSafeViewWithoutSensitiveFields() {
        AdminUserService service = service();
        UUID targetId = UUID.randomUUID();
        User target = user(targetId, User.UserRole.ADMIN, User.UserStatus.ACTIVE, 7);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(target)));

        Page<AdminUserView> page = service.listUsers(null, null, null, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        AdminUserView view = page.getContent().get(0);
        assertEquals(targetId, view.getId());
        assertEquals("ADMIN", view.getRole());
        assertEquals("ACTIVE", view.getStatus());
        assertFalse(view.isMfaEnabled());
    }

    /** 排序必须 createdAt DESC + id DESC：同时间戳（批量导入）时翻页才不会重复 / 漏行。 */
    @Test
    void listUsers_shouldSortByCreatedAtDescThenIdDesc() {
        AdminUserService service = service();
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listUsers(null, null, null, PageRequest.of(0, 20));

        Pageable passed = capturePageable();
        Sort sort = passed.getSort();
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("createdAt").getDirection());
        assertEquals(Sort.Direction.DESC, sort.getOrderFor("id").getDirection());
        assertEquals(20, passed.getPageSize());
    }

    /** Q6 默认口径：每页上限 200，超限静默收敛，防一次拉全表。 */
    @Test
    void listUsers_shouldClampPageSizeToMax() {
        AdminUserService service = service();
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listUsers(null, null, null, PageRequest.of(3, 5000));

        Pageable passed = capturePageable();
        assertEquals(200, passed.getPageSize());
        assertEquals(3, passed.getPageNumber());
    }

    /**
     * 过滤条件下推：email 走 trim + lowercase 后的包含匹配，role / status 走等值——
     * 断言 JPA  criterion 的生成，确保「不把全量用户拉到内存过滤」。
     */
    @Test
    void listUsers_shouldBuildEmailRoleStatusCriteria() {
        AdminUserService service = service();
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listUsers("  Alice@Example.COM ", User.UserRole.ADMIN, User.UserStatus.DISABLED,
            PageRequest.of(0, 20));

        Specification<User> spec = captureSpecification();
        Root<User> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<String> emailPath = mock(Path.class);
        Path<User.UserRole> rolePath = mock(Path.class);
        Path<User.UserStatus> statusPath = mock(Path.class);
        when(root.<String>get("email")).thenReturn(emailPath);
        when(root.<User.UserRole>get("role")).thenReturn(rolePath);
        when(root.<User.UserStatus>get("status")).thenReturn(statusPath);

        spec.toPredicate(root, query, cb);

        verify(cb).like(eq(emailPath), eq("%alice@example.com%"), eq('\\'));
        verify(cb).equal(rolePath, User.UserRole.ADMIN);
        verify(cb).equal(statusPath, User.UserStatus.DISABLED);
    }

    /** 空筛选值归一为「不过滤」，避免拼出恒假条件导致列表恒空。 */
    @Test
    void listUsers_shouldSkipBlankFilters() {
        AdminUserService service = service();
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listUsers("   ", null, null, PageRequest.of(0, 20));

        Specification<User> spec = captureSpecification();
        Root<User> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        spec.toPredicate(root, query, cb);

        verify(cb, never()).like(any(Expression.class), anyString(), anyChar());
        verify(cb, never()).equal(any(Expression.class), any(Object.class));
    }

    // ==================== 用户导出（P2） ====================

    /** 防公式注入：以 = + - @ 开头的单元格必须前置单引号，Excel 打开才不会当公式求值。 */
    @Test
    void exportUsersCsv_shouldNeutralizeFormulaInjection() {
        AdminUserService service = service();
        User evil = user(UUID.randomUUID(), User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        evil.setEmail("=HYPERLINK(\"http://evil\")@example.com");
        User plus = user(UUID.randomUUID(), User.UserRole.USER, User.UserStatus.ACTIVE, 0);
        plus.setEmail("+cmd@example.com");
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(evil, plus)));

        String csv = service.exportUsersCsv(null, null, null);

        assertTrue(csv.contains("'=HYPERLINK"), "以 = 开头的单元格须前置单引号");
        assertTrue(csv.contains("'+cmd@example.com"), "以 + 开头的单元格须前置单引号");
    }

    /** 导出内容只含脱敏字段：无密码哈希 / 令牌版本 / MFA 密钥材料；表头齐全。 */
    @Test
    void exportUsersCsv_shouldContainOnlySafeColumns() {
        AdminUserService service = service();
        User target = user(UUID.randomUUID(), User.UserRole.ADMIN, User.UserStatus.ACTIVE, 7);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(target)));

        String csv = service.exportUsersCsv(null, null, null);

        assertTrue(csv.startsWith("﻿"));
        assertTrue(csv.contains("email,role,status,mfaEnabled,createdAt,lastLoginAt"));
        assertTrue(csv.contains("u@example.com"));
        assertTrue(csv.contains("ADMIN"));
        assertFalse(csv.contains("passwordHash"));
        assertFalse(csv.contains("tokenVersion"));
        assertFalse(csv.contains("mfaSecret"));
    }

    @SuppressWarnings("unchecked")
    private Specification<User> captureSpecification() {
        ArgumentCaptor<Specification<User>> captor = ArgumentCaptor.forClass(Specification.class);
        verify(userRepository).findAll(captor.capture(), any(Pageable.class));
        return captor.getValue();
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(any(Specification.class), captor.capture());
        return captor.getValue();
    }
}
