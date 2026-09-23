package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 激活二次因子的请求（plan-7.0 / M3）。
 *
 * <p>用户把 {@code enroll} 返回的密钥录入认证器后，用认证器当前显示的一次性动态码证明
 * 「密钥已正确录入」，通过后 {@code mfa_enabled} 才置真。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "激活二次因子请求；用认证器当前显示的动态码证明密钥已正确录入")
public class MfaActivateRequest {

    @NotBlank(message = "动态码不能为空")
    @Schema(description = "认证器 App 当前显示的 6 位动态码", example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
