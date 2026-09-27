package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * step-up 邮箱兜底码发送请求（plan-7.0 / P2 · T05）。
 *
 * <p>{@code action} 声明即将执行的敏感动作：服务端校验其为受保护动作之一，
 * 防止拼错动作名签出与实际操作不匹配的确认令牌。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "敏感动作二次确认：请求发送邮箱兜底码")
public class MfaStepUpChallengeRequest {

    @Schema(description = "即将执行的敏感动作：ADMIN_RESET_USER_PASSWORD / CHANGE_USER_ROLE / CHANGE_USER_STATUS",
            example = "CHANGE_USER_STATUS")
    @NotBlank(message = "请指定敏感动作")
    private String action;
}
