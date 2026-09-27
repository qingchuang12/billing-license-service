package com.billing.license.security;

import com.billing.license.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 从数据库按邮箱加载账号的 {@link UserDetailsService}，把既有账号体系接入 Spring Security 标准认证链。
 *
 * <p>提供此 bean 有两重作用：
 * <ol>
 *   <li>登录认证改由 {@code DaoAuthenticationProvider} 驱动（密码比对 + 锁定/停用预检），
 *       而限流、失败计数、两阶段 MFA 仍在 {@code AccountService} 外层保留；</li>
 *   <li>使 Spring Boot 的 {@code UserDetailsServiceAutoConfiguration} 退避
 *       （其 {@code @ConditionalOnMissingBean} 含 {@code UserDetailsService}），
 *       不再打印「Using generated security password」——那本就是框架在「以为你没配认证」时的兜底。</li>
 * </ol>
 *
 * <p>邮箱入库前已归一化为小写，此处同样归一化后查询，避免大小写差异导致误判「用户不存在」。
 * 查无账号抛 {@link UsernameNotFoundException}；因 {@code hideUserNotFoundExceptions} 默认为 true，
 * 认证链会将其收敛为 {@code BadCredentialsException}，与「密码错误」同文案，保住防账号枚举。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DbUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        return userRepository.findByEmail(normalized)
            .map(AuthUserPrincipal::new)
            .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
    }
}
