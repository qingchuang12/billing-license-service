package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.dto.AuthResponse;
import com.billing.license.dto.MfaTicketRequest;
import com.billing.license.dto.MfaVerifyRequest;
import com.billing.license.service.MfaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 二次因子登录校验控制器（plan-7.0 / M3）。
 *
 * <p><b>为什么不并入 {@link AccountController}</b>：本控制器下的两个端点是<b>半认证</b>状态——
 * 调用者尚未持有访问令牌，只持有一枚「密码已通过」的一次性票据。鉴权模型与
 * {@code /api/account/**} 的 {@code ROLE_USER} 完全不同，分开放可让
 * {@code SecurityConfig} 的规则一眼对应。
 *
 * <p><b>鉴权</b>：两个端点均 {@code permitAll}，真正的把关是方法内的票据校验
 * （独立密钥签名 + {@code ver} 比对 + 有效期）。必须是 {@code permitAll}——
 * 此时用户还没有令牌，若要求 {@code ROLE_USER} 会形成死锁（永远拿不到令牌）。
 * {@code SecurityConfig} 中还须把它们<b>声明在 {@code /api/account/**} 规则之前</b>。
 */
@Tag(name = "账号", description = "二次因子登录校验（凭一次性登录票据，无需访问令牌）")
@RestController
@RequestMapping("/api/account/mfa")
@RequiredArgsConstructor
public class AccountMfaController {

    private final MfaService mfaService;
    private final ClientIpResolver clientIpResolver;

    /**
     * 请求发送邮箱兜底验证码（票据有效且服务端开启邮箱兜底时才可发）。
     *
     * <p>用途码为 {@code LOGIN_MFA}，只能由本端点签发——公开的
     * {@code POST /api/account/verification-code} 不接受该用途。
     */
    @Operation(summary = "发送邮箱兜底验证码（凭票据）",
            description = "TOTP 不可用时的恢复路径；受邮箱冷却与窗口次数限流")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已发送（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "MFA_TICKET_INVALID / MFA_NOT_ENABLED / "
                    + "MFA_EMAIL_FALLBACK_DISABLED / CODE_SEND_TOO_FREQUENT")
    })
    @Audit(action = "MFA_CHALLENGE")
    @PostMapping("/challenge")
    public ResponseEntity<Void> challenge(@Valid @RequestBody MfaTicketRequest request,
                                         HttpServletRequest httpRequest) {
        mfaService.challenge(request.getTicket(), clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok().build();
    }

    /**
     * 校验第二因子并换取正式访问令牌。
     *
     * <p>校验顺序为「认证器动态码 → 邮箱兜底码」，两者都失败才算一次失败。
     */
    @Operation(summary = "校验第二因子并换取令牌（凭票据）",
            description = "动态码或邮箱验证码任一通过即返回访问令牌；失败达上限临时拒绝")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "校验通过，返回令牌与用户资料"),
            @ApiResponse(responseCode = "400", description = "MFA_TICKET_INVALID / MFA_NOT_ENABLED / "
                    + "MFA_CODE_INVALID / MFA_VERIFY_LIMIT / MFA_SECRET_UNREADABLE / ACCOUNT_DISABLED")
    })
    @Audit(action = "MFA_VERIFY")
    @PostMapping("/verify")
    public ResponseEntity<AuthResponse> verify(@Valid @RequestBody MfaVerifyRequest request) {
        return ResponseEntity.ok(mfaService.verify(request.getTicket(), request.getCode()));
    }
}
