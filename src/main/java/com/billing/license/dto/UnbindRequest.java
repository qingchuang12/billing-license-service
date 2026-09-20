package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 释放本机绑定请求（公开端点，需持有旧授权 token 证明归属）
 *
 * <p>与「吊销」语义不同：本请求只清空 {@code licenses.machine_code}，释放该授权在当前设备的绑定，
 * 不取消授权本身、不动 {@code REVOKED} 状态。仅用于换绑新授权时让旧授权可在本机之外复用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "释放本机绑定请求（公开端点，需持有旧 token 证明归属）")
public class UnbindRequest {

    /** 旧授权的签名 token（证明持有该授权）；服务端验签后取出 licenseKey 与绑定机器码 */
    @Schema(description = "旧授权的签名 token；服务端验签后取出 licenseKey 与绑定机器码",
            example = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ...")
    private String signedToken;

    /** 发起解绑的客户端机器码；须与 token 绑定机器码及服务端记录一致，防止远端解绑他人授权 */
    @Schema(description = "客户端机器码；须与 token 绑定机器码及服务端记录一致，防止远端解绑他人授权",
            example = "5E01-7EB8-3661-E06A")
    private String machineId;
}
