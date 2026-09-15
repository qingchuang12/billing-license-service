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
 * 注册请求（A2）。
 *
 * <p>{@code emailCode} 为<b>必填</b>（决策 3：注册强制邮箱验证）；
 * 联调期可用 {@code account.require-email-verification=false} 临时关闭服务端校验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "注册请求；必须先用 A1 发送 purpose=REGISTER 的验证码并回填 emailCode")
public class RegisterRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "登录邮箱（大小写不敏感，服务端统一转小写）",
            example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度须为 8–72 位（BCrypt 上限 72 字节）")
    @Schema(description = "登录密码；8–72 位，且至少含 1 个字母与 1 个数字（可配置放宽）",
            example = "Passw0rd2026", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Schema(description = "邮箱验证码（6 位数字）；purpose 须为 REGISTER，一次性消费",
            example = "483920", requiredMode = Schema.RequiredMode.REQUIRED)
    private String emailCode;
}
