package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生成二次因子密钥的请求（plan-7.0 / M3）。
 *
 * <p><b>为什么要求重新输入当前密码</b>：本端点只凭「已登录的管理员令牌」即可调用。
 * 若不再校验口令，攻击者一旦窃取到令牌（XSS、终端失窃、令牌泄漏）就能<b>静默绑定自己的
 * 认证器</b>，把合法管理员锁在门外。要求口令使「绑定」仍需第二份凭据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "生成二次因子密钥请求；须重新输入当前密码以防令牌泄漏后被静默绑定")
public class MfaEnrollRequest {

    @NotBlank(message = "请输入当前密码")
    @Schema(description = "当前登录账号的密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
