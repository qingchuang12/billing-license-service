package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 解绑二次因子的请求（plan-7.0 / M3）。
 *
 * <p><b>为什么同时要求口令与验证码</b>：解绑等于<b>关闭一道安全防线</b>，属高影响操作。
 * 只要求令牌即可解绑的话，令牌泄漏 = 攻击者可悄悄关掉 MFA；只要求口令则无法证明
 * 「持有认证器者本人」的意愿。两者齐备才放行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "解绑二次因子请求；须同时提供当前密码与动态码（或邮箱兜底码）")
public class MfaUnbindRequest {

    @NotBlank(message = "请输入当前密码")
    @Schema(description = "当前登录账号的密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "6 位动态码（认证器）或邮箱验证码", example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
