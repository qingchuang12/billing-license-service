package com.billing.license.controller;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.service.LicenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * License 控制器：仅保留**公开在线校验**端点。
 *
 * <p>I3/I4（2026-09-14）接口简化：
 * <ul>
 *   <li>按客户查询 License 收敛到 {@code GET /api/admin/licenses?customerId=}（管理端）；</li>
 *   <li>手动签发收敛到 {@code POST /api/admin/orders/{orderNumber}/issue}（管理端）；</li>
 *   <li>客户端自吊销端点已删除（吊销唯一入口为管理端）；</li>
 *   <li>本控制器只保留客户端可公开调用的在线校验（离线验签的在线兜底）。</li>
 * </ul>
 */
@Tag(name = "License", description = "许可证在线校验（公开端点）")
@RestController
@RequestMapping("/api/licenses")
@RequiredArgsConstructor
public class LicenseController {
    
    private final LicenseService licenseService;
    
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
            @Parameter(description = "License 密钥", required = true) @PathVariable String licenseKey) {
        return ResponseEntity.ok(licenseService.verifyLicense(licenseKey));
    }
}
