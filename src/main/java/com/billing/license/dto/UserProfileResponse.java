package com.billing.license.dto;

import com.billing.license.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户资料视图（A5 me；A2 注册 / A3 登录响应中的 {@code user} 字段同结构）。
 *
 * <p>三个端点共用一个模型，避免「同一实体两种视图」的文档分叉。
 * 注册响应里 {@code lastLoginAt} 为 {@code null}、{@code status} 恒为 {@code ACTIVE}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户资料；注册/登录响应中的 user 字段与 A5 me 返回结构一致")
public class UserProfileResponse {

    @Schema(description = "用户 ID，**同时用作 Order.customerId**（决策 1）",
            example = "8b2c4d6e-1f3a-4b5c-9d7e-0a1b2c3d4e5f")
    private UUID id;

    @Schema(description = "登录邮箱（已归一化为小写）", example = "user@example.com")
    private String email;

    @Schema(description = "邮箱是否已完成验证", example = "true")
    private boolean emailVerified;

    @Schema(description = "账号状态：ACTIVE=正常，DISABLED=已停用",
            example = "ACTIVE", allowableValues = {"ACTIVE", "DISABLED"})
    private String status;

    @Schema(description = "角色：USER=普通消费者 / ADMIN=管理员（plan-6.0 统一登录）",
            example = "USER", allowableValues = {"USER", "ADMIN"})
    private String role;

    @Schema(description = "注册时间（ISO-8601，无时区）", example = "2026-09-15T01:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "最近登录时间；从未登录过为 null", example = "2026-09-15T09:12:44")
    private LocalDateTime lastLoginAt;

    public static UserProfileResponse from(User user) {
        return UserProfileResponse.builder()
            .id(user.getId())
            .email(user.getEmail())
            .emailVerified(user.isEmailVerified())
            .status(user.getStatus() != null ? user.getStatus().name() : null)
            .role(user.getRole() != null ? user.getRole().name() : null)
            .createdAt(user.getCreatedAt())
            .lastLoginAt(user.getLastLoginAt())
            .build();
    }
}
