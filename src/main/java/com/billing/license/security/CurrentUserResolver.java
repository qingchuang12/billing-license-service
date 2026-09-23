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
        UUID userId = currentUserIdOrNull();
        if (userId == null) {
            throw new BusinessException("TOKEN_INVALID", "缺少有效登录令牌");
        }
        return userId;
    }

    /**
     * 取当前登录用户 ID，**未登录返回 {@code null}**（不抛异常）。
     *
     * <p>供「可选鉴权」端点使用——端点 {@code permitAll} 但某个分支要求登录时
     * （如 {@code POST /api/licenses/activate}：兑换码分支匿名可调、许可证密钥分支必须登录），
     * 由调用方据返回值自行决定拒绝方式与错误码。
     *
     * <p>未带令牌时 Spring Security 会填入匿名主体（名称为 {@code anonymousUser}，
     * 非 UUID），故解析失败即视为未登录。
     */
    public static UUID currentUserIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
