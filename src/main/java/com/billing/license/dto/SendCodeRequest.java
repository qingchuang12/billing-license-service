package com.billing.license.dto;

import com.billing.license.entity.VerificationCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送邮箱验证码请求（A1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "发送邮箱验证码请求；同一邮箱受「冷却 + 窗口内次数」双重限流")
public class SendCodeRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "接收验证码的邮箱（大小写不敏感，服务端统一转小写）",
            example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @NotNull(message = "验证码用途不能为空")
    @Schema(description = "用途：REGISTER=注册验证，RESET_PASSWORD=找回密码",
            example = "REGISTER", allowableValues = {"REGISTER", "RESET_PASSWORD"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private VerificationCode.CodePurpose purpose;
}
