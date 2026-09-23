package com.billing.license.controller;

import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.dto.ActivateRequest;
import com.billing.license.dto.ActivateResponse;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.ReportBindingRequest;
import com.billing.license.security.CurrentUserResolver;
import com.billing.license.service.CredentialBindingService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.risk.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * License 控制器：公开的**在线校验**与**凭证激活**端点。
 *
 * <p>I3/I4（2026-09-14）接口简化：
 * <ul>
 *   <li>按客户查询 License 收敛到 {@code GET /api/admin/licenses?customerEmail=}（管理端）；</li>
 *   <li>手动签发收敛到 {@code POST /api/admin/orders/{orderNumber}/issue}（管理端）；</li>
 *   <li>客户端自吊销端点已删除（吊销唯一入口为管理端）；</li>
 *   <li>本控制器只保留客户端可公开调用的在线校验（离线验签的在线兜底）。</li>
 * </ul>
 *
 * <p>plan-7.0 方案 A：新增 {@code POST /api/licenses/activate}，把「四处凭证入口」收敛为
 * 单一激活入口（兑换码 / 许可证密钥由服务端自动识别）。该端点为 <b>可选鉴权</b>——
 * 兑换码分支匿名可调，许可证密钥分支要求登录，判定在 {@link CredentialBindingService} 内完成。
 */
@Tag(name = "License", description = "许可证在线校验与凭证激活（公开端点）")
@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenseController {
    
    private final LicenseService licenseService;
    private final RateLimitService rateLimitService;
    private final CredentialBindingService credentialBindingService;
    private final ClientIpResolver clientIpResolver;
    
    /**
     * 验证 License 有效性
     * @param licenseKey License 密钥
     * @return License 详细信息（含 signedToken，供客户端离线校验）
     */
    @Operation(summary = "验证 License（公开）", description = "校验许可证有效性并返回详情（客户端离线校验的在线兜底）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "校验成功"),
            @ApiResponse(responseCode = "400", description = "License 不存在 / 已过期 / 已吊销 / 已换机重发")
    })
    @GetMapping("/verify/{licenseKey}")
    public ResponseEntity<LicenseResponse> verifyLicense(
            @Parameter(description = "License 密钥", required = true) @PathVariable String licenseKey,
            HttpServletRequest request) {
        // C4：公开端点频控——防止高频遍历 licenseKey（探测存在性 / license_event 表无界增长）
        try {
            rateLimitService.checkLicenseVerify(request.getRemoteAddr());
        } catch (RateLimitService.RateLimitExceededException e) {
            return ResponseEntity.status(429).<LicenseResponse>build();
        }
        return ResponseEntity.ok(licenseService.verifyLicense(licenseKey));
    }

    /**
     * 凭证激活（统一入口，plan-7.0 方案 A）。
     *
     * <p>{@code credential} 以 {@code RC-} 开头按兑换码处理（匿名可调，身份取登录用户或请求体邮箱）；
     * 其余按许可证密钥处理——<b>必须登录</b>，且归属须为当前用户。
     * 一条已被绑定到同一机器的授权重复激活是幂等的（返回既有签名令牌，不二次签发）。
     *
     * @param request     激活请求（凭证 + 机器码）
     * @param httpRequest 用于解析真实客户端 IP（限流依据，忽略请求体伪造值）
     */
    @Operation(summary = "凭证激活（公开）",
            description = "统一激活入口：凭证为兑换码（RC- 前缀）或许可证密钥，服务端自动识别。"
                    + "许可证密钥要求登录且归属为本人；已绑定同一机器时幂等返回既有签名令牌。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "激活成功（或同机重试幂等命中）"),
            @ApiResponse(responseCode = "400",
                    description = "凭证无效（凭证不认识 / 非本人，统一 CREDENTIAL_NOT_FOUND，不泄露存在性）/ "
                            + "授权非 ACTIVE / "
                            + "已绑定他机（MACHINE_MISMATCH）/ 未登录（LOGIN_REQUIRED）/ 触发限流")
    })
    @PostMapping("/activate")
    public ResponseEntity<ActivateResponse> activate(
            @RequestBody ActivateRequest request,
            HttpServletRequest httpRequest) {
        // C10 同口径：无条件用服务端解析的真实 IP 做限流，忽略请求体可能伪造的 clientIp
        request.setClientIp(clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(
            credentialBindingService.activate(request, CurrentUserResolver.currentUserIdOrNull()));
    }

    /**
     * 客户端「自动上报绑定」（plan-7.0 / D2）。
     *
     * <p>客户网购后拿到兑换码，在客户端激活时若未携带机器码，该 License 落成「未绑定」态；
     * 客户端随后在<b>程序启动时</b>上报一次本机机器码，本端点把该授权补绑到本机
     * （客户端本地只上报一次，成功后不再触发）。
     *
     * <p>归属凭证为 {@code signedToken}（授权本体，客户端兑换/激活时即持有），**无需登录**；
     * 已绑同机为幂等返回，已绑他机按 {@code MACHINE_MISMATCH} 拒绝（不自动改绑）。
     *
     * @param request 上报请求（签名令牌 + 机器码）
     */
    @Operation(summary = "自动上报绑定（公开）",
            description = "客户端兑换/激活后启动时上报机器码，把「未绑定」的授权补绑到本机。"
                    + "凭 signedToken 验签证明归属，无需登录；已绑同机幂等返回，已绑他机拒绝。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "补绑成功（或同机幂等命中）"),
            @ApiResponse(responseCode = "400",
                    description = "signedToken 缺失/验签失败或授权不存在（统一 CREDENTIAL_NOT_FOUND）/ "
                            + "授权非 ACTIVE / 已绑定他机（MACHINE_MISMATCH）/ 触发限流")
    })
    @PostMapping("/report-binding")
    public ResponseEntity<ActivateResponse> reportBinding(@RequestBody ReportBindingRequest request) {
        return ResponseEntity.ok(
            licenseService.reportBinding(request.getSignedToken(), request.getMachineId()));
    }
}
