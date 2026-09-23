package com.billing.license.dto;

import com.billing.license.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 二次因子绑定状态（plan-7.0 / M3）。
 *
 * <p><b>不回显任何密钥材料</b>：只暴露「是否启用」与「启用时间」，密钥明文仅在
 * {@code enroll} 的一次性响应中出现。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "二次因子绑定状态；不含任何密钥材料")
public class MfaStatusResponse {

    @Schema(description = "是否已启用二次因子；登录时按此判定是否要求第二因子", example = "false")
    private boolean enabled;

    @Schema(description = "启用时刻；未启用为 null", example = "2026-09-23T11:20:00")
    private LocalDateTime enrolledAt;

    @Schema(description = "是否允许邮箱验证码作为兜底（服务端 account.mfa.email-fallback-enabled）",
            example = "true")
    private boolean emailFallbackEnabled;

    public static MfaStatusResponse from(User user, boolean emailFallbackEnabled) {
        return MfaStatusResponse.builder()
            .enabled(user.isMfaEnabled())
            .enrolledAt(user.getMfaEnrolledAt())
            .emailFallbackEnabled(emailFallbackEnabled)
            .build();
    }
}
