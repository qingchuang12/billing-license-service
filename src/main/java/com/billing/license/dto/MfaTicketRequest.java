package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 二次因子挑战/校验共用的一次性登录票据（plan-7.0 / M3）。
 *
 * <p>票据由登录第一步（密码通过）签发，仅证明「密码已通过」，<b>不是访问令牌</b>。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录票据请求；票据由 POST /api/account/login 在 mfaRequired=true 时返回")
public class MfaTicketRequest {

    @NotBlank(message = "登录票据不能为空")
    @Schema(description = "一次性登录票据（mfaTicket）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ticket;
}
