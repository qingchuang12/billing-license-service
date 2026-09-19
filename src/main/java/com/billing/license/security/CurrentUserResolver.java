package com.billing.license.security;

import com.billing.license.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * 当前登录用户上下文解析（U1 提取自 AccountController，供 /api/account/** 下多个控制器复用）。
 *
 * <p>principal 由 {@link JwtAuthFilter} 写入（userId 字符串）。调用方已通过
 * {@code ROLE_USER} 授权，取不到只可能是令牌被并发登出等极端情况。
 */
public final class CurrentUserResolver {

    private CurrentUserResolver() {
    }

    /**
     * 取当前登录用户 ID。
     *
     * @throws BusinessException 上下文缺失或 principal 非法（{@code TOKEN_INVALID}）
     */
    public static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("TOKEN_INVALID", "缺少有效登录令牌");
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("TOKEN_INVALID", "登录令牌非法");
        }
    }
}
