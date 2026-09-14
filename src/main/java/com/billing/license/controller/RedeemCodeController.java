package com.billing.license.controller;

import com.billing.license.dto.RedeemCodeRequest;
import com.billing.license.entity.License;
import com.billing.license.service.RedeemCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

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

    /**
     * H8：是否信任反向代理注入的 X-Forwarded-For / X-Real-IP。
     * 默认 false——这些头可被客户端伪造，在未确认反代可信前不得用于频控/审计，
     * 否则攻击者可伪造 IP 绕过 RateLimitService 的兑换频控。
     */
    @Value("${billing.trust-x-forwarded-for:false}")
    private boolean trustXForwardedFor;

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
    public ResponseEntity<Map<String, Object>> redeemCode(
            @RequestBody RedeemCodeRequest request,
            HttpServletRequest httpRequest) {
        // C10：无条件使用服务端解析的真实 IP，忽略请求体可能伪造的 clientIp（@JsonIgnore 已禁止反序列化）
        String clientIp = parseClientIp(httpRequest);
        request.setClientIp(clientIp);
        License license = redeemCodeService.redeemCode(request);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("licenseKey", license.getLicenseKey());
        response.put("signedToken", license.getSignedToken());
        response.put("expiresAt", license.getExpiresAt());
        return ResponseEntity.ok(response);
    }

    /**
     * 解析客户端真实 IP。
     * H8：未显式信任反代（trustXForwardedFor=false）时，绝不信任可伪造的
     * X-Forwarded-For / X-Real-IP，直接采用直连接 peer 地址；
     * 仅在确认反代可信后才解析转发头，防止频控被伪造 IP 绕过。
     */
    private String parseClientIp(HttpServletRequest request) {
        if (!trustXForwardedFor) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            // 取第一个（最原始客户端）
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
