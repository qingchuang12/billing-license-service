package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 凭证激活请求（方案 A：单一入口，服务端按凭证格式自动分流）。
 *
 * <p>{@code credential} 以 {@code RC-} 开头视为**兑换码**（沿用「持码即持有人」，匿名可调）；
 * 其余视为**许可证密钥**（要求已登录，归属判定由服务端完成——见
 * {@code CredentialBindingService}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "凭证激活请求（公开端点，授权强度按凭证类型分流）")
public class ActivateRequest {

    /** 凭证明文：兑换码（RC- 前缀）或许可证密钥 */
    @Schema(description = "凭证：兑换码（RC- 前缀）或许可证密钥；服务端按格式自动识别",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "XXXX-XXXX-XXXX-XXXX")
    private String credential;

    /** 待绑定的客户端机器码 */
    @Schema(description = "客户端机器码；须与客户端本地指纹一致，激活后该授权即绑定此设备",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "MACHINE-FP-8823a1")
    private String machineId;

    /** 客户邮箱（仅兑换码分支且未登录时需要，用于识别兑换人） */
    @Schema(description = "客户邮箱；仅在「兑换码 + 未登录」时需要，用于识别兑换人（未注册自动建访客账户）",
            example = "buyer@example.com")
    private String customerEmail;

    /**
     * 客户端 IP（风控：高频激活 / 暴力猜测限流）。
     * C10 同口径：禁止从请求体反序列化，避免攻击者伪造 IP 绕过限流；由 Controller 无条件用服务端解析值覆盖。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    private String clientIp;
}
