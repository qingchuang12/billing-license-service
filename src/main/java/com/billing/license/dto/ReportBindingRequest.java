package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端「自动上报绑定」请求（plan-7.0 / D2）。
 *
 * <p><b>场景</b>：客户网购后拿到兑换码，在客户端激活时若未携带机器码，该 License 落成
 * 「未绑定」态。客户端随后在<b>程序启动时</b>把本机机器码上报一次，服务端据此把这张未绑定的
 * 授权补绑到该设备（客户端本地只上报一次，成功后不再触发）。
 *
 * <p><b>归属凭证</b>：{@code signedToken}——授权文件本体，客户端在兑换/激活时即持有
 * （{@code LicenseService#bindToMachine} 对未绑定件也**无条件**签发）。服务端验签通过即认定归属，
 * 故本端点<b>不需要登录</b>（E1 = ① 的直接结果）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "客户端自动上报绑定请求（公开端点，凭 signedToken 验签证明归属）")
public class ReportBindingRequest {

    @Schema(description = "客户端持有的 License 签名令牌（JWS Compact 三段式）；服务端验签通过即完成归属认定",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "eyJhbGciOiJFZERTQSJ9.eyJsaWMiOi...")
    private String signedToken;

    @Schema(description = "本机机器码；仅在授权「尚未绑定」时用于补绑，已绑同机为幂等返回",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "MACHINE-FP-8823a1")
    private String machineId;
}
