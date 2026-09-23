package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生成二次因子密钥的响应（plan-7.0 / M3）。
 *
 * <p><b>本响应只在 {@code enroll} 时出现一次</b>，之后服务端不再回显密钥——
 * 这也是为什么账号页/管理台需要用户当场把密钥录入认证器或抄存。
 *
 * <p>响应不回显二维码图片：本项目静态页<b>零 CDN、无构建链</b>，无法引入二维码库；
 * 认证器 App 均支持手动输入密钥，故给出 Base32 密钥文本与标准 {@code otpauth://} URI。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "生成二次因子密钥响应；密钥仅此一次回显，请立即录入认证器")
public class MfaEnrollResponse {

    @Schema(description = "TOTP 密钥（Base32，20 字节）；手动录入认证器用",
            example = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
    private String secret;

    @Schema(description = "标准 otpauth:// URI（Google Authenticator Key URI Format）；"
            + "若客户端有二维码能力可直接渲染",
            example = "otpauth://totp/BillingLicenseService:admin%40example.com?secret=...&issuer=BillingLicenseService")
    private String otpauthUri;

    @Schema(description = "是否已生效。恒为 false——须再用认证器动态码调用 activate 才启用",
            example = "false")
    private boolean activated;
}
