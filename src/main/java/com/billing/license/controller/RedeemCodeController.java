package com.billing.license.controller;

import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.dto.RedeemResponse;
import com.billing.license.entity.License;
import com.billing.license.service.RedeemCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 兑换码控制器：仅保留**公开兑换**端点。
 *
 * <p>I5（2026-09-14）接口简化：兑换码的生成与撤销属管理动作，已收敛到管理端
 * （{@code POST /api/admin/redeem-codes/generate}、{@code POST /api/admin/redeem-codes/revoke/{code}}），
 * 避免同类管理动作分散在两个前缀、两套鉴权表述下。
 * 本控制器只负责客户端公开兑换（{@code POST /api/redeem/redeem}）。
 */
@Tag(name = "兑换码", description = "兑换码兑换（公开端点）")
@RestController
@RequestMapping("/api/redeem")
@RequiredArgsConstructor
public class RedeemCodeController {
    
    private final RedeemCodeService redeemCodeService;
    private final ClientIpResolver clientIpResolver;

    /**
     * 兑换兑换码获取 License
     * @param request 兑换请求，包含兑换码、客户 ID 与机器码
     * @return 兑换成功后返回 License 信息
     */
    @Operation(summary = "兑换兑换码（公开）",
            description = "使用兑换码换取 License；服务端按真实客户端 IP 做频控（忽略请求体伪造的 clientIp）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "兑换成功"),
            @ApiResponse(responseCode = "400", description = "兑换码无效 / 已使用 / 已过期 / 触发频控")
    })
    @PostMapping("/redeem")
    public ResponseEntity<RedeemResponse> redeemCode(
            @RequestBody RedeemCodeRequest request,
            HttpServletRequest httpRequest) {
        // C10：无条件使用服务端解析的真实 IP，忽略请求体可能伪造的 clientIp（@JsonIgnore 已禁止反序列化）
        request.setClientIp(clientIpResolver.resolve(httpRequest));
        License license = redeemCodeService.redeemCode(request);
        return ResponseEntity.ok(RedeemResponse.from(license));
    }
}
