package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 找回密码请求（A7，公开）。
 *
 * <p>与「改密」的区别：本端点凭邮箱验证码重置，<b>不需要旧密码</b>，用于用户忘记密码的场景。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "找回密码请求；须先用 A1 发送 purpose=RESET_PASSWORD 的验证码并回填 code")
public class ResetPasswordRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "注册邮箱", example = "user@example.com",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "邮箱验证码（6 位数字）；purpose 须为 RESET_PASSWORD，一次性消费",
            example = "483920", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度须为 8–72 位（BCrypt 上限 72 字节）")
    @Schema(description = "新密码", example = "NewPassw0rd2026",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
