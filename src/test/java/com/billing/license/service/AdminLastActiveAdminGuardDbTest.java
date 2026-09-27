package com.billing.license.service;

import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 末位管理员护栏的<b>真实库证伪</b>（QA 独立复核，plan-7.0 P0 / T02 缺陷修复）。
 *
 * <p>工程师的 {@code AdminUserServiceTest} 用 Mockito 把 {@code countByRoleAndStatus} 的返回值
 * <b>钉死</b>了（想让它返回几就返回几），因此它证明的是「代码读了这个返回值」，
 * 而不是「这个数在真实 SQL 下算对了」。旧缺陷恰恰是<b>计数口径</b>错了：
 * {@code countByRole(ADMIN)} 把 DISABLED 管理员也算成活人。
 *
 * <p>故这里全部走真实 H2 + 真实 JPA 派生查询，用「1 个 ACTIVE ADMIN + N 个 DISABLED ADMIN」
 * 这组能击穿旧实现的夹具，断言降级与停用都必须被拒。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminLastActiveAdminGuardDbTest {

    @Autowired private AdminUserService adminUserService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User activeAdmin;
    private User otherActiveAdmin;
    /** 真实存在的操作者（P2 step-up 护栏会按操作者 ID 现查 MFA 开关，查无此人会先报 USER_NOT_FOUND） */
    private User operatorUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        operatorUser = save("qa-guard-operator@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    private User save(String email, User.UserRole role, User.UserStatus status) {
        return userRepository.save(User.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode("Passw0rd1"))
            .role(role).status(status)
            .build());
    }

    private long activeAdminCount() {
        return userRepository.countByRoleAndStatus(User.UserRole.ADMIN, User.UserStatus.ACTIVE);
    }

    private void buildOneActivePlusDisabledAdmins(int disabledCount) {
        activeAdmin = save("qa-guard-active@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        for (int i = 0; i < disabledCount; i++) {
            save("qa-guard-disabled-" + i + "@example.com", User.UserRole.ADMIN, User.UserStatus.DISABLED);
        }
    }

    /** 操作者取一个与「存活管理员」无关的真实普通用户，排除「禁止操作自身」的干扰。 */
    private UUID operator() {
        return operatorUser.getId();
    }

    // ==================== 1. 击穿旧缺陷的夹具 ====================

    @Test
    @DisplayName("1 ACTIVE ADMIN + 2 DISABLED ADMIN：停用该 ACTIVE ADMIN 必须被拒（旧实现的 bug 正是把 DISABLED 算活人）")
    void cannotDisableLastActiveAdminWhenDisabledAdminsExist() {
        buildOneActivePlusDisabledAdmins(2);
        assertEquals(1, activeAdminCount(), "夹具前提：存活管理员只有 1 个");
        assertEquals(3, userRepository.countByRole(User.UserRole.ADMIN), "夹具前提：ADMIN 角色共 3 个");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> adminUserService.changeStatus(operator(), activeAdmin.getId(), User.UserStatus.DISABLED, null));
        assertEquals("LAST_ADMIN_PROTECTED", ex.getErrorCode());

        User after = userRepository.findById(activeAdmin.getId()).orElseThrow();
        assertEquals(User.UserStatus.ACTIVE, after.getStatus(), "拒绝后库中状态不得变化");
        assertEquals(0, after.getTokenVersion(), "拒绝后不得递增令牌版本");
        assertEquals(1, activeAdminCount());
    }

    @Test
    @DisplayName("1 ACTIVE ADMIN + 2 DISABLED ADMIN：降级该 ACTIVE ADMIN 必须被拒")
    void cannotDemoteLastActiveAdminWhenDisabledAdminsExist() {
        buildOneActivePlusDisabledAdmins(2);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> adminUserService.changeRole(operator(), activeAdmin.getId(), User.UserRole.USER, null));
        assertEquals("LAST_ADMIN_PROTECTED", ex.getErrorCode());

        User after = userRepository.findById(activeAdmin.getId()).orElseThrow();
        assertEquals(User.UserRole.ADMIN, after.getRole());
        assertEquals(1, activeAdminCount());
    }

    @Test
    @DisplayName("1 ACTIVE ADMIN + 0 DISABLED ADMIN：同样必须被拒（护栏不是靠 DISABLED 凑数才生效）")
    void cannotDisableSoleAdmin() {
        buildOneActivePlusDisabledAdmins(0);
        assertThrows(BusinessException.class,
            () -> adminUserService.changeStatus(operator(), activeAdmin.getId(), User.UserStatus.DISABLED, null));
    }

    // ==================== 2. 反向：2 个 ACTIVE ADMIN 时允许动其中一个 ====================

    @Test
    @DisplayName("2 ACTIVE ADMIN：允许停用其中一个")
    void canDisableOneOfTwoActiveAdmins() {
        activeAdmin = save("qa-guard-a1@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        otherActiveAdmin = save("qa-guard-a2@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        assertEquals(2, activeAdminCount());

        adminUserService.changeStatus(operator(), activeAdmin.getId(), User.UserStatus.DISABLED, null);

        User after = userRepository.findById(activeAdmin.getId()).orElseThrow();
        assertEquals(User.UserStatus.DISABLED, after.getStatus());
        assertEquals(1, after.getTokenVersion(), "停用须令其令牌立即失效");
        assertEquals(1, activeAdminCount(), "仍须保留 1 名可登录管理员");
    }

    @Test
    @DisplayName("2 ACTIVE ADMIN：允许降级其中一个；之后再动最后一个即被拒")
    void canDemoteOneOfTwoActiveAdminsThenLastOneIsProtected() {
        activeAdmin = save("qa-guard-b1@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        otherActiveAdmin = save("qa-guard-b2@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);

        adminUserService.changeRole(operator(), activeAdmin.getId(), User.UserRole.USER, null);
        assertEquals(User.UserRole.USER,
            userRepository.findById(activeAdmin.getId()).orElseThrow().getRole());
        assertEquals(1, activeAdminCount());

        // 此时只剩 1 名存活管理员，再动它必须被拒
        assertThrows(BusinessException.class,
            () -> adminUserService.changeRole(operator(), otherActiveAdmin.getId(), User.UserRole.USER, null));
        assertThrows(BusinessException.class,
            () -> adminUserService.changeStatus(operator(), otherActiveAdmin.getId(), User.UserStatus.DISABLED, null));
        assertEquals(1, activeAdminCount());
    }

    // ==================== 3. 护栏不得误伤正常动作 ====================

    @Test
    @DisplayName("提权 USER→ADMIN 不受护栏约束（护栏只管减少存活管理员）")
    void promotingUserIsNeverBlocked() {
        buildOneActivePlusDisabledAdmins(1);
        User plain = save("qa-guard-plain@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        adminUserService.changeRole(operator(), plain.getId(), User.UserRole.ADMIN, null);

        assertEquals(User.UserRole.ADMIN, userRepository.findById(plain.getId()).orElseThrow().getRole());
        assertEquals(2, activeAdminCount());
    }

    @Test
    @DisplayName("停用普通用户不受护栏约束")
    void disablingPlainUserIsNeverBlocked() {
        buildOneActivePlusDisabledAdmins(1);
        User plain = save("qa-guard-plain2@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);

        adminUserService.changeStatus(operator(), plain.getId(), User.UserStatus.DISABLED, null);

        assertEquals(User.UserStatus.DISABLED, userRepository.findById(plain.getId()).orElseThrow().getStatus());
        assertEquals(1, activeAdminCount());
    }

    @Test
    @DisplayName("禁止操作自身优先于护栏（不能自己停用自己）")
    void selfOperationIsRejectedFirst() {
        activeAdmin = save("qa-guard-self@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        otherActiveAdmin = save("qa-guard-self2@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> adminUserService.changeStatus(activeAdmin.getId(), activeAdmin.getId(),
                User.UserStatus.DISABLED, null));
        assertEquals("SELF_OPERATION_NOT_ALLOWED", ex.getErrorCode());
        assertEquals(User.UserStatus.ACTIVE,
            userRepository.findById(activeAdmin.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("护栏计数只看 ACTIVE+ADMIN：已停用的 ADMIN 不计入，ACTIVE 的 USER 也不计入")
    void guardCountsOnlyActiveAdmins() {
        save("qa-guard-c-admin-disabled@example.com", User.UserRole.ADMIN, User.UserStatus.DISABLED);
        save("qa-guard-c-admin-disabled2@example.com", User.UserRole.ADMIN, User.UserStatus.DISABLED);
        save("qa-guard-c-user-active@example.com", User.UserRole.USER, User.UserStatus.ACTIVE);
        activeAdmin = save("qa-guard-c-admin-active@example.com", User.UserRole.ADMIN, User.UserStatus.ACTIVE);

        assertEquals(1, activeAdminCount());
        assertNotEquals(userRepository.countByRole(User.UserRole.ADMIN), activeAdminCount(),
            "旧口径 countByRole(ADMIN) 与正确口径在此夹具下必须不同，否则本用例失去证伪能力");
        assertTrue(userRepository.countByRole(User.UserRole.ADMIN) > 1,
            "夹具必须让旧口径得出 >1，才能真正击穿旧实现");

        assertThrows(BusinessException.class,
            () -> adminUserService.changeStatus(operator(), activeAdmin.getId(), User.UserStatus.DISABLED, null));
    }
}
