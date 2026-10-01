package com.billing.license.service;

import com.billing.license.config.AccountProperties;
import com.billing.license.dto.AdminUserDetailView;
import com.billing.license.dto.AdminUserView;
import com.billing.license.entity.User;
import com.billing.license.exception.BusinessException;
import com.billing.license.repository.LicenseRepository;
import com.billing.license.repository.OrderRepository;
import com.billing.license.repository.UserRepository;
import com.billing.license.security.AdminStepUpService;
import com.billing.license.security.PasswordPolicy;
import com.billing.license.service.notification.EmailNotificationService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 管理端用户管理（plan-7.0 / D4，B9 = B；账户基础功能扩展：列表查询 + 管理员代重置）。
 *
 * <p>提供<b>分页查询</b>、<b>用户详情</b>、<b>角色变更</b>、<b>停用 / 启用</b>与<b>管理员代重置密码</b>五类动作。
 * 变更类动作<b>内部必须</b> {@code tokenVersion + 1}——令目标用户所有已签发令牌立即失效，配合
 * {@code JwtAuthFilter} 的每请求现查角色 / 状态，使降权、停用<b>即时生效</b>
 * （不再依赖令牌 7 天自然过期）。
 *
 * <p>安全护栏（防止管理台被锁死）：
 * <ul>
 *   <li><b>禁止操作自身</b>：管理员不能对自己做角色 / 状态变更与代重置，避免误操作把自己踢出管理台。</li>
 *   <li><b>禁止降级 / 停用最后一个可登录管理员</b>：以 {@code ACTIVE + ADMIN} 计数为准
 *       （已停用的管理员不算存活），且在事务内用悲观锁串行化，堵住并发竞态。</li>
 * </ul>
 *
 * <p>审计由控制器层 {@code @Audit} 声明（actor 取自 {@code SecurityContext} 的 userId，与既有管理动作同口径）。
 * 代重置成功后向目标用户发送不含密码的安全提醒（旁路，失败不影响重置结果）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** 单页上限（Q6 默认口径：20 条/页、上限 200）——防止一次拉全表把内存打满 */
    private static final int MAX_PAGE_SIZE = 200;

    /** LIKE 通配符转义符：邮箱里出现 % / _ 时不能被当成通配符 */
    private static final char LIKE_ESCAPE = '\\';

    /** 导出行数上限：导出是运营动作而非对账报表，超限请用更窄的过滤条件 */
    private static final int EXPORT_MAX_ROWS = 10000;

    private final UserRepository userRepository;
    private final LicenseRepository licenseRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;
    private final AdminStepUpService stepUpService;
    private final AccountProperties properties;

    // ==================== 列表查询 ====================

    /**
     * 分页查询用户（组合 email / role / status 条件，按注册时间倒序稳定分页）。
     *
     * <p><b>不下拉全量到内存过滤</b>：条件组合成 {@link Specification} 交 JPA 下推到 SQL，
     * 排序固定追加 {@code id DESC}——同时间戳（批量导入）时仍有稳定次序，翻页不会重复/漏行。
     */
    @Transactional(readOnly = true)
    public Page<AdminUserView> listUsers(String email, User.UserRole role, User.UserStatus status, Pageable pageable) {
        PageRequest sorted = PageRequest.of(
            Math.max(0, pageable.getPageNumber()),
            Math.max(1, Math.min(pageable.getPageSize(), MAX_PAGE_SIZE)),
            Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return userRepository.findAll(specification(email, role, status), sorted).map(AdminUserView::from);
    }

    /** 组合过滤条件；空条件归一为不过滤（email 做 trim + lowercase 后的包含匹配）。 */
    private Specification<User> specification(String email, User.UserRole role, User.UserStatus status) {
        String term = normalizeEmail(email);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (term != null) {
                predicates.add(cb.like(root.get("email"), "%" + escapeLike(term) + "%", LIKE_ESCAPE));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** 空串归一为 null；其余 trim + lowercase（与写入口径一致，避免大小写漏查）。 */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    /** 转义 LIKE 通配符，使用户输入的 {@code %} / {@code _} 只当普通字符匹配。 */
    private String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ==================== 角色 / 状态 ====================

    /**
     * 变更角色（USER ↔ ADMIN）。
     *
     * <p>操作者已开启 MFA 时须携带与 {@code CHANGE_USER_ROLE} 绑定的二次确认令牌（P2 step-up）。
     */
    @Transactional
    public AdminUserView changeRole(UUID operatorId, UUID targetUserId, User.UserRole newRole, String stepUpToken) {
        User target = requireTarget(targetUserId, operatorId);
        requireStepUp(operatorId, AdminStepUpService.ACTION_CHANGE_USER_ROLE, stepUpToken);
        if (target.getRole() == newRole) {
            return AdminUserView.from(target); // 幂等：无变化直接返回，不刷新令牌
        }
        // 降级管理员：必须保证仍至少有一个「可登录」管理员留存
        if (target.getRole() == User.UserRole.ADMIN && newRole == User.UserRole.USER) {
            ensureNotLastActiveAdmin();
        }
        target.setRole(newRole);
        invalidateTokens(target);
        userRepository.save(target);
        log.info("管理端变更用户角色：operator={}, target={}, role={}", operatorId, targetUserId, newRole);
        return AdminUserView.from(target);
    }

    /**
     * 变更状态（ACTIVE ↔ DISABLED）。
     *
     * <p>操作者已开启 MFA 时须携带与 {@code CHANGE_USER_STATUS} 绑定的二次确认令牌（P2 step-up）。
     */
    @Transactional
    public AdminUserView changeStatus(UUID operatorId, UUID targetUserId, User.UserStatus newStatus, String stepUpToken) {
        User target = requireTarget(targetUserId, operatorId);
        requireStepUp(operatorId, AdminStepUpService.ACTION_CHANGE_USER_STATUS, stepUpToken);
        if (target.getStatus() == newStatus) {
            return AdminUserView.from(target); // 幂等
        }
        // 停用管理员：必须保证仍至少有一个「可登录」管理员留存
        if (target.getRole() == User.UserRole.ADMIN && newStatus == User.UserStatus.DISABLED) {
            ensureNotLastActiveAdmin();
        }
        target.setStatus(newStatus);
        invalidateTokens(target);
        userRepository.save(target);
        log.info("管理端变更用户状态：operator={}, target={}, status={}", operatorId, targetUserId, newStatus);
        return AdminUserView.from(target);
    }

    // ==================== 管理员代重置密码 ====================

    /**
     * 管理员代用户重置密码（Q1 = 管理员自设密码）。
     *
     * <p>同一事务内完成三件事：写新密码哈希、{@code tokenVersion + 1}（旧会话立即失效）、
     * 置 {@code mustChangePassword = true}（用户下次登录必须先改密）。
     * <b>不能先返回成功再失效旧会话</b>——那中间的窗口里旧令牌仍可用。
     *
     * <p>操作者已开启 MFA 时须携带与 {@code ADMIN_RESET_USER_PASSWORD} 绑定的二次确认令牌
     * （P2 step-up）。新密码只过 {@link PasswordPolicy} 校验与 {@link PasswordEncoder} 编码，
     * 不写日志、不进审计 detail、不回显给响应体。
     */
    @Transactional
    public void resetPassword(UUID operatorId, UUID targetUserId, String newPassword, String stepUpToken) {
        User target = requireTarget(targetUserId, operatorId);
        requireStepUp(operatorId, AdminStepUpService.ACTION_RESET_USER_PASSWORD, stepUpToken);
        PasswordPolicy.validate(newPassword, properties);
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setMustChangePassword(true);
        invalidateTokens(target);
        userRepository.save(target);
        // 只记谁对谁做了什么：新密码不落日志
        log.info("管理端重置用户密码：operator={}, target={}", operatorId, targetUserId);
        // P1 安全提醒（旁路，@Async）：告知本人密码已被管理员重置（正文不含密码）；失败不回滚重置
        // F7：管理员代重置——无买家 locale 上下文，按回落 en
        emailNotificationService.sendPasswordChangedEmail(target.getEmail(), "ADMIN_RESET", null);
    }

    // ==================== 用户详情（P1） ====================

    /**
     * 用户详情：脱敏资料 + 名下许可证 / 订单计数。
     *
     * <p>许可证明细<b>不在此返回</b>——前端点「查看许可证」复用既有
     * {@code GET /api/admin/licenses?customerEmail=}，避免为详情再造第二套许可证 DTO。
     */
    @Transactional(readOnly = true)
    public AdminUserDetailView getUserDetail(UUID targetUserId) {
        User target = userRepository.findById(targetUserId)
            .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在: " + targetUserId));
        return AdminUserDetailView.builder()
            .user(AdminUserView.from(target))
            .licenseCount(licenseRepository.countByCustomerId(targetUserId))
            .orderCount(orderRepository.countByCustomerId(targetUserId))
            .build();
    }

    // ==================== 用户导出（P2） ====================

    /**
     * 按当前过滤条件导出用户 CSV（UTF-8，带 BOM 便于 Excel 识别）。
     *
     * <p><b>防公式注入（CSV Injection）</b>：以 {@code = + - @} 或制表符开头的单元格
     * 会被 Excel/WPS 当作公式求值——攻击者可借注册邮箱植入 {@code =HYPERLINK(...)}、
     * {@code =cmd|...} 之类载荷，管理员导出后一打开即触发。故此类单元格统一前置
     * 单引号使其按文本处理。
     *
     * <p>不含密码哈希 / 令牌版本 / MFA 密钥材料；行数截断于 {@link #EXPORT_MAX_ROWS}。
     */
    @Transactional(readOnly = true)
    public String exportUsersCsv(String email, User.UserRole role, User.UserStatus status) {
        PageRequest limit = PageRequest.of(0, EXPORT_MAX_ROWS, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        List<User> users = userRepository.findAll(specification(email, role, status), limit).getContent();

        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("email,role,status,mfaEnabled,createdAt,lastLoginAt\r\n");
        for (User u : users) {
            csv.append(csvCell(u.getEmail())).append(',')
                .append(csvCell(u.getRole() != null ? u.getRole().name() : "")).append(',')
                .append(csvCell(u.getStatus() != null ? u.getStatus().name() : "")).append(',')
                .append(u.isMfaEnabled()).append(',')
                .append(csvCell(u.getCreatedAt() != null ? u.getCreatedAt().toString() : "")).append(',')
                .append(csvCell(u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : ""))
                .append("\r\n");
        }
        return csv.toString();
    }

    /**
     * CSV 单元格转义：含逗号 / 引号 / 换行时整体加引号（引号翻倍）；
     * 以公式触发字符开头时前置单引号防公式注入。
     */
    private static String csvCell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    // ==================== 内部护栏 ====================

    /**
     * 敏感动作二次确认（P2 step-up）：操作者开启 MFA 时，三类敏感动作
     * （代重置 / 改角色 / 启停）必须携带与动作绑定的短时效一次性确认令牌。
     *
     * <p>未开启 MFA 的操作者直接放行——MFA 默认关（B8），没有可验证的第二因子，
     * 不因增强项改变默认口径。令牌缺失 / 无效 / 已用 / 动作不符均拒绝。
     */
    private void requireStepUp(UUID operatorId, String action, String stepUpToken) {
        User operator = userRepository.findById(operatorId)
            .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "操作者不存在: " + operatorId));
        if (!operator.isMfaEnabled()) {
            return;
        }
        stepUpService.consume(operatorId, operator.getTokenVersion(), action, stepUpToken);
    }

    private User requireTarget(UUID targetUserId, UUID operatorId) {
        // 护栏一：禁止操作自身（防误操作自锁管理台）
        if (targetUserId.equals(operatorId)) {
            throw new BusinessException("SELF_OPERATION_NOT_ALLOWED", "不能对自身执行管理操作");
        }
        return userRepository.findById(targetUserId)
            .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "用户不存在: " + targetUserId));
    }

    /**
     * 护栏二：变更前若「可登录管理员（{@code ACTIVE + ADMIN}）」仅剩 1 个，拒绝降级 / 停用。
     *
     * <p><b>为什么不用 {@code countByRole(ADMIN)}</b>：已 {@code DISABLED} 的管理员同样带 ADMIN 角色，
     * 会被算作存活管理员，于是最后一个真正能登录的管理员也能被降权/停用，管理台从此无人可进。
     *
     * <p><b>为什么先加锁</b>：单纯 count → save 有并发竞态（两个管理员并发互相停用时可能双双通过）。
     * 事务内先 {@code SELECT ... FOR UPDATE} 锁定存活管理员这批行，把并发操作串行化。
     */
    private void ensureNotLastActiveAdmin() {
        userRepository.findActiveAdminsForUpdate(User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        long activeAdminCount = userRepository.countByRoleAndStatus(User.UserRole.ADMIN, User.UserStatus.ACTIVE);
        if (activeAdminCount <= 1) {
            throw new BusinessException("LAST_ADMIN_PROTECTED", "不能降级或停用最后一个可登录管理员");
        }
    }

    /** 令目标用户所有已签发令牌立即失效（复用既有 tokenVersion 开关）。 */
    private void invalidateTokens(User target) {
        target.setTokenVersion(target.getTokenVersion() + 1);
    }
}
