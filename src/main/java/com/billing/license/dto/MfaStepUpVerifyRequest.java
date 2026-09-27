package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * step-up 二次确认校验请求（plan-7.0 / P2 · T05）。
 *
 * <p>动态码（TOTP 或邮箱兜底码）通过后签发与 {@code action} 绑定的短时效确认令牌。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "敏感动作二次确认：校验动态码换取一次性确认令牌")
public class MfaStepUpVerifyRequest {

    @Schema(description = "即将执行的敏感动作：ADMIN_RESET_USER_PASSWORD / CHANGE_USER_ROLE / CHANGE_USER_STATUS",
            example = "CHANGE_USER_STATUS")
    @NotBlank(message = "请指定敏感动作")
    private String action;

    @Schema(description = "认证器动态码或邮箱兜底码（6 位数字）", example = "123456")
    @NotBlank(message = "请输入动态码")
    private String code;
}
