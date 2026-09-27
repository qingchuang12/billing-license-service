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
 * <p><b>二维码</b>由 {@code TotpQrCodeService} 在本地用 ZXing 渲染为 PNG data URI
 * （不是引前端库、更不是调在线二维码服务——后者等于把密钥明文发给第三方）。
 * 它与 {@code secret} <b>同一次回显、之后不再出现</b>，故服务端不提供「重新获取二维码」端点。
 * 渲染失败时该字段为 {@code null}，此时仍可靠 {@code secret} 手动录入认证器。
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

    @Schema(description = "otpauth:// URI 的二维码（PNG data URI，可直接赋给 <img src>）；"
            + "渲染失败时为 null，此时改用 secret 手动录入",
            example = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg...")
    private String qrCodeDataUri;

    @Schema(description = "是否已生效。恒为 false——须再用认证器动态码调用 activate 才启用",
            example = "false")
    private boolean activated;
}
