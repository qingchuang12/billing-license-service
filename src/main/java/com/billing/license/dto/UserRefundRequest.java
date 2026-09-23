package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户端退款申请请求体（plan-4.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "退款申请")
public class UserRefundRequest {

    /** 退款原因（可选，用于审计与退款通知文案） */
    @Schema(description = "退款原因（可选，≤200 字）", example = "买错了版本")
    private String reason;
}
