package com.billing.license.repository;

import com.billing.license.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户仓储。
 *
 * <p>查询入参一律为**归一化后的小写邮箱**（服务层经 {@code User#normalizeEmail()} 处理），
 * 避免同一邮箱因大小写不同查不到。
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** plan-7.0 / D4：统计指定角色的用户数（用于「最后一个管理员」护栏）。 */
    long countByRole(User.UserRole role);
}
