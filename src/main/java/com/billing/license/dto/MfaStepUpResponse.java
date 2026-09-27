package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * step-up 二次确认响应（plan-7.0 / P2 · T05）。
 *
 * <p>确认令牌只保存在前端内存中发起对应动作的这一次请求，不得写入任何持久存储。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "敏感动作二次确认结果：一次性确认令牌与有效期")
public class MfaStepUpResponse {

    @Schema(description = "一次性确认令牌（短时效、单次使用、与动作绑定）；随 X-Step-Up-Token 头携带",
            example = "eyJhbGciOiJIUzI1NiJ9...")
    private String stepUpToken;

    @Schema(description = "确认令牌有效期（秒）", example = "120")
    private int expiresInSeconds;
}
