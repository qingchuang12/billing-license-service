package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 兑换码请求 DTO
 * 用于接收客户端提交的兑换码兑换请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "兑换码兑换请求（公开端点）")
public class RedeemCodeRequest {
    
    /** 兑换码字符串 */
    @Schema(description = "兑换码明文（必填）；无效/已使用/已过期返回 400", example = "RC-8F3C-1D2E-9A4B")
    private String code;
    
    /** 客户邮箱（对外客户标识；未注册则自动建访客账户） */
    @Schema(description = "客户邮箱，作为对外客户标识；未注册邮箱将自动创建访客账户",
            example = "buyer@example.com")
    private String customerEmail;

    /** 客户端机器码（兑换时绑定到 License，架构十一.4） */
    @Schema(description = "客户端机器码；传入则该 License 绑定此设备，为空则签发不绑定设备的 License",
            example = "MACHINE-FP-8823a1")
    private String machineId;

    /** 客户端 IP（风控：高频兑换/暴力猜测限流，架构十七）。
     *  C10：禁止从请求体反序列化，避免攻击者伪造 IP 绕过限流；由 Controller 无条件用服务端解析值覆盖。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Schema(hidden = true)
    private String clientIp;
}
