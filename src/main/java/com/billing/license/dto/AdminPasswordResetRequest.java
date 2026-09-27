package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员代重置密码请求（plan-7.0 / Q1 = 管理员自设密码）。
 *
 * <p><b>只含新密码一个字段</b>：由管理员当场设定并线下告知用户本人，服务端<b>不生成、不回显</b>任何密码。
 * 故本主题没有「一次性密码响应 DTO」——响应 {@code data} 为 {@code null}，避免密码经响应体泄漏。
 *
 * <p>长度与字符类别在服务端仍过 {@code PasswordPolicy}（8–72 位、含字母与数字）；
 * 这里的注解只是让明显不合规的输入在校验阶段就以 400 返回，省一次密码策略往返。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员代重置密码请求；只提交新密码，响应不回显任何密码")
public class AdminPasswordResetRequest {

    @Schema(description = "新密码明文（8–72 位，须同时含字母与数字）；服务端编码后落库，不入日志/审计",
            example = "Passw0rd2026")
    @NotBlank(message = "请输入新密码")
    @Size(min = 8, max = 72, message = "新密码长度须为 8–72 位")
    private String newPassword;
}
