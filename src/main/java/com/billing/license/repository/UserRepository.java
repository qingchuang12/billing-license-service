package com.billing.license.repository;

import com.billing.license.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户仓储。
 *
 * <p>查询入参一律为**归一化后的小写邮箱**（服务层经 {@code User#normalizeEmail()} 处理），
 * 避免同一邮箱因大小写不同查不到。
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** plan-7.0 / D4：统计指定角色的用户数（用于「库中是否已存在管理员」的 bootstrap 判据）。 */
    long countByRole(User.UserRole role);

    /**
     * 统计「指定角色 + 指定状态」的用户数（plan-7.0 账户基础功能）。
     *
     * <p>末位管理员护栏必须用本方法而非 {@link #countByRole}：后者会把已 {@code DISABLED}
     * 的管理员也算作存活管理员，于是「最后一个可登录管理员」可能被降权/停用，管理台就此锁死。
     */
    long countByRoleAndStatus(User.UserRole role, User.UserStatus status);

    /**
     * 悲观锁锁定指定角色 + 状态的全部用户（管理员降权 / 停用的串行化用）。
     *
     * <p><b>为什么需要它</b>：先 count 再 save 存在竞态——两个管理员并发互相停用时，
     * 两次 count 都可能看到「还剩 2 个管理员」而双双放行，最终一个管理员都不剩。
     * 在事务内先 {@code SELECT ... FOR UPDATE} 锁定这批行，可把并发操作串行化；
     * 由于被降权/停用的目标本身就在锁定集合内，后到的事务会看到前一个事务提交后的结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.status = :status")
    List<User> findActiveAdminsForUpdate(@Param("role") User.UserRole role,
                                         @Param("status") User.UserStatus status);
}
