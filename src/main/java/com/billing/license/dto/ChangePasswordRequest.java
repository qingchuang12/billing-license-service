package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 改密请求（A6，需登录）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "改密请求；成功后 tokenVersion +1，该用户所有已签发令牌立即失效（需重新登录）")
public class ChangePasswordRequest {

    @NotBlank(message = "旧密码不能为空")
    @Schema(description = "当前密码", example = "Passw0rd2026",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度须为 8–72 位（BCrypt 上限 72 字节）")
    @Schema(description = "新密码；不得与旧密码相同", example = "NewPassw0rd2026",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
