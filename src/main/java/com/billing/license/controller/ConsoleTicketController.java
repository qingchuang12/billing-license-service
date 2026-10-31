package com.billing.license.controller;

import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.config.BillingProperties;
import com.billing.license.dto.ApiResponse;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.ConsoleTicketService;
import com.billing.license.service.risk.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-Tools 运营台活跃统计取票端点（公开，但只返回 HMAC 摘要 + 一次性安装票据）。
 *
 * <p>客户端未登录/试用也要上报，因此本端点不要求登录；signedToken 可选、由服务内验签决定
 * 是否使用账号摘要。票据密钥/摘要盐未配置返回 503，客户端静默退避。
 */
@Slf4j
@Tag(name = "Console", description = "运营台活跃统计取票（公开）")
@RestController
@RequestMapping("/api/licenses/console")
@RequiredArgsConstructor
public class ConsoleTicketController {

    private final ConsoleTicketService consoleTicketService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final BillingProperties properties;

    public record ConsoleTicketRequest(
        String signedToken,
        String machineCode,
        String productCode,
        String terminalVersion
    ) {
    }

    @Operation(summary = "签发运营台安装票据与设备/账号摘要（公开）")
    @PostMapping("/ticket")
    public ResponseEntity<?> ticket(@RequestBody(required = false) ConsoleTicketRequest request,
                                    HttpServletRequest httpRequest) {
        if (!consoleTicketService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("TICKET_SECRET_NOT_CONFIGURED", "运营台票据密钥/摘要盐未配置"));
        }
        if (request == null) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.fail("INVALID_REQUEST_BODY", "请求体不能为空"));
        }
        int max = properties.getConsole().getTicketRateLimitPerHour();
        if (max > 0) {
            try {
                rateLimitService.checkAndCount("console-ticket-ip", clientIpResolver.resolve(httpRequest), max, 60);
            } catch (RateLimitService.RateLimitExceededException e) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.fail("RATE_LIMITED", "请求过于频繁，请稍后再试"));
            }
        }
        try {
            return ResponseEntity.ok(consoleTicketService.issue(
                request.signedToken(), request.machineCode(), request.productCode()));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
        }
    }
}
