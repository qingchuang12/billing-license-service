package com.billing.license.service;

import com.billing.license.dto.AdminUserView;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 管理端用户管理（plan-7.0 / D4，B9 = B）。
 *
 * <p>提供<b>角色变更</b>与<b>停用 / 启用</b>两类动作，满足「管理端用户管理 API」的基本诉求。
 * 两者<b>内部必须</b> {@code tokenVersion + 1}——令目标用户所有已签发令牌立即失效，配合
 * {@code JwtAuthFilter} 的每请求现查角色 / 状态，使降权、停用<b>即时生效</b>
 * （不再依赖令牌 7 天自然过期）。
 *
 * <p>安全护栏（防止管理台被锁死）：
 * <ul>
 *   <li><b>禁止操作自身</b>：管理员不能对自己做角色 / 状态变更，避免误操作把自己踢出管理台。</li>
 *   <li><b>禁止降级 / 停用最后一个管理员</b>：变更后若 {@code ADMIN} 数量将归零，直接拒绝。</li>
 * </ul>
 *
 * <p>审计由控制器层 {@code @Audit} 声明（actor 取自 {@code SecurityContext} 的 userId，与既有管理动作同口径）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;

    /** 变更角色（USER ↔ ADMIN）。 */
    @Transactional
    public AdminUserView changeRole(UUID operatorId, UUID targetUserId, User.UserRole newRole) {
        User target = requireTarget(targetUserId, operatorId);
        if (target.getRole() == newRole) {
            return AdminUserView.from(target); // 幂等：无变化直接返回，不刷新令牌
        }
        // 降级管理员：必须保证仍至少有一个 ADMIN 留存
        if (target.getRole() == User.UserRole.ADMIN && newRole == User.UserRole.USER) {
            ensureNotLastAdmin();
        }
        target.setRole(newRole);
        invalidateTokens(target);
        userRepository.save(target);
        log.info("管理端变更用户角色：operator={}, target={}, role={}", operatorId, targetUserId, newRole);
        return AdminUserView.from(target);
    }

    /** 变更状态（ACTIVE ↔ DISABLED）。 */
    @Transactional
    public AdminUserView changeStatus(UUID operatorId, UUID targetUserId, User.UserStatus newStatus) {
        User target = requireTarget(targetUserId, operatorId);
        if (target.getStatus() == newStatus) {
            return AdminUserView.from(target); // 幂等
        }
        // 停用管理员：必须保证仍至少有一个 ADMIN 留存
        if (target.getRole() == User.UserRole.ADMIN && newStatus == User.UserStatus.DISABLED) {
            ensureNotLastAdmin();
        }
        target.setStatus(newStatus);
        invalidateTokens(target);
        userRepository.save(target);
        log.info("管理端变更用户状态：operator={}, target={}, status={}", operatorId, targetUserId, newStatus);
        return AdminUserView.from(target);
    }

    private User requireTarget(UUID targetUserId, UUID operatorId) {
        // 护栏一：禁止操作自身（防误操作自锁管理台）
        if (targetUserId.equals(operatorId)) {
            throw new BusinessException("SELF_OPERATION_NOT_ALLOWED", "不能对自身执行管理操作");
        }
        return userRepository.findById(targetUserId)
            .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在: " + targetUserId));
    }

    /** 护栏二：变更前（尚未落库）若 ADMIN 总数仅 1，即视为最后一个管理员，拒绝降级 / 停用。 */
    private void ensureNotLastAdmin() {
        long adminCount = userRepository.countByRole(User.UserRole.ADMIN);
        if (adminCount <= 1) {
            throw new BusinessException("LAST_ADMIN_PROTECTED", "不能降级或停用最后一个管理员");
        }
    }

    /** 令目标用户所有已签发令牌立即失效（复用既有 tokenVersion 开关）。 */
    private void invalidateTokens(User target) {
        target.setTokenVersion(target.getTokenVersion() + 1);
    }
}
