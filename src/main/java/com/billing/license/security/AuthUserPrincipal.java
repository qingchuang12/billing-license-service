package com.billing.license.security;

import com.billing.license.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 的 {@link UserDetails} 适配层：把领域实体 {@link User} 包成框架认证链
 * （{@code DaoAuthenticationProvider}）能消费的形式。
 *
 * <p>认证链依赖的三个状态位由实体派生，语义与既有登录逻辑逐条对齐：
 * <ul>
 *   <li>{@link #isEnabled()} ← {@code status == ACTIVE}（停用账号触发 {@code DisabledException}）；</li>
 *   <li>{@link #isAccountNonLocked()} ← {@code !user.isLocked()}（锁定账号触发 {@code LockedException}）；</li>
 *   <li>{@link #getAuthorities()} ← 按 {@code role} 授予 {@code ROLE_USER}（管理员额外 {@code ROLE_ADMIN}）。</li>
 * </ul>
 * 账号/凭据过期无对应业务概念，沿用 {@link UserDetails} 的 default 实现（恒为 true）。
 *
 * <p>{@link #getUser()} 暴露被包装的<b>受管实体</b>：登录成功后 {@code AccountService} 直接在其上
 * 清零失败计数、更新最近登录时间并落库，避免二次查库。
 */
public class AuthUserPrincipal implements UserDetails {

    private final User user;
    private final List<GrantedAuthority> authorities;

    public AuthUserPrincipal(User user) {
        this.user = user;
        this.authorities = user.getRole() == User.UserRole.ADMIN
            ? AuthorityUtils.createAuthorityList("ROLE_USER", "ROLE_ADMIN")
            : AuthorityUtils.createAuthorityList("ROLE_USER");
    }

    /** 被包装的领域实体（认证链加载时即为受管实例） */
    public User getUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == User.UserStatus.ACTIVE;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !user.isLocked();
    }
}
