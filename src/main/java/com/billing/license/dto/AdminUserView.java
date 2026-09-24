package com.billing.license.dto;

import com.billing.license.entity.User;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 管理端用户视图（plan-7.0 / D4）。
 *
 * <p>返回管理端用户管理所需的脱敏字段——不含密码哈希、令牌等敏感信息；
 * 角色 / 状态以枚举名字符串呈现，便于前端直接展示与回显。
 */
@Value
@Builder
public class AdminUserView {

    UUID id;
    String email;
    String role;
    String status;
    boolean mfaEnabled;
    LocalDateTime createdAt;
    LocalDateTime lastLoginAt;

    public static AdminUserView from(User u) {
        return AdminUserView.builder()
            .id(u.getId())
            .email(u.getEmail())
            .role(u.getRole() != null ? u.getRole().name() : null)
            .status(u.getStatus() != null ? u.getStatus().name() : null)
            .mfaEnabled(u.isMfaEnabled())
            .createdAt(u.getCreatedAt())
            .lastLoginAt(u.getLastLoginAt())
            .build();
    }
}
