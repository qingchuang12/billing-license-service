package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 第二因子校验请求（plan-7.0 / M3）。
 *
 * <p>{@code code} 可为认证器 App 的 6 位动态码，也可为邮箱兜底验证码——服务端先试动态码、
 * 失败再试邮箱码，客户端无需声明用的是哪一种。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "第二因子校验请求；动态码或邮箱兜底码均可")
public class MfaVerifyRequest {

    @NotBlank(message = "登录票据不能为空")
    @Schema(description = "一次性登录票据（mfaTicket）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ticket;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "6 位动态码（认证器）或邮箱验证码", example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
