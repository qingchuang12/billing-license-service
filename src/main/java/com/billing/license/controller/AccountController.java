package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.dto.*;
import com.billing.license.security.CurrentUserResolver;
import com.billing.license.service.AccountService;
import com.billing.license.service.VerificationCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 账号控制器（plan v2.10）：注册 / 登录 / 登出 / 当前用户 / 改密 / 找回密码 / 验证码。
 *
 * <p><b>鉴权</b>：A1 发码、A2 注册、A3 登录、A7 找回密码为公开端点（在 {@code SecurityConfig} 中逐条
 * permitAll）；A4 登出、A5 me、A6 改密需 {@code Authorization: Bearer <JWT>}。
 *
 * <p><b>敏感操作不回显凭据</b>：请求体中的密码与验证码一律不进入日志、审计 detail 与响应。
 */
@Tag(name = "账号", description = "注册 / 登录 / 登出 / 当前用户 / 改密 / 找回密码 / 邮箱验证码")
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final VerificationCodeService verificationCodeService;
    private final ClientIpResolver clientIpResolver;

    /**
     * A1：发送邮箱验证码（公开）。
     *
     * <p>受三层限流：邮箱冷却（秒级）、邮箱窗口次数、IP 窗口次数。
     * SMTP 未配置且未开启 {@code account.code-log-only} 时，邮件会被静默跳过——验证码实际不可达。
     */
    @Operation(summary = "发送邮箱验证码（公开）",
            description = "生成并发送 6 位数字验证码；联调可开启 account.code-log-only 从日志取码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已发送（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "邮箱格式错误 / 用途非法 / 触发发送限流")
    })
    @Audit(action = "SEND_VERIFICATION_CODE", target = "#request.email")
    @PostMapping("/verification-code")
    public ResponseEntity<Void> sendVerificationCode(@Valid @RequestBody SendCodeRequest request,
                                                     HttpServletRequest httpRequest) {
        verificationCodeService.send(request.getEmail(), request.getPurpose(),
            clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok().build();
    }

    /** A2：注册（公开）。成功即返回令牌，无需再调登录。 */
    @Operation(summary = "注册（公开）",
            description = "邮箱 + 密码注册；默认强制校验邮箱验证码（account.require-email-verification）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册成功，返回令牌与用户资料"),
            @ApiResponse(responseCode = "400", description = "邮箱已注册 / 密码不合规 / 验证码无效 / 触发限流")
    })
    @Audit(action = "REGISTER", target = "#request.email")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(accountService.register(
            request.getEmail(), request.getPassword(), request.getEmailCode(),
            clientIpResolver.resolve(httpRequest)));
    }

    /** A3：登录（公开）。允许多端并存；失败计数达阈值会临时锁定账号。 */
    @Operation(summary = "登录（公开）",
            description = "邮箱 + 密码登录；失败 5 次锁定账号 15 分钟（可配置），返回令牌与用户资料")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "400", description = "INVALID_CREDENTIALS / ACCOUNT_LOCKED / ACCOUNT_DISABLED / LOGIN_IP_LIMIT")
    })
    @Audit(action = "LOGIN", target = "#request.email")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(accountService.login(
            request.getEmail(), request.getPassword(), clientIpResolver.resolve(httpRequest)));
    }

    /** A4：登出（需登录）。tokenVersion +1，该用户所有已签发令牌立即失效。 */
    @Operation(summary = "登出（需登录）", description = "使当前用户的所有已签发令牌立即失效")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已登出（data 为 null）"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @Audit(action = "LOGOUT")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        accountService.logout(currentUserId());
        return ResponseEntity.ok().build();
    }

    /** A5：当前用户（需登录）。 */
    @Operation(summary = "当前用户（需登录）", description = "返回当前登录用户资料")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me() {
        return ResponseEntity.ok(accountService.me(currentUserId()));
    }

    /** A6：改密（需登录）。成功后旧令牌失效，需重新登录。 */
    @Operation(summary = "改密（需登录）", description = "校验旧密码后设置新密码；成功后所有已签发令牌失效")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已改密（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "OLD_PASSWORD_MISMATCH / PASSWORD_POLICY_VIOLATION / 触发限流"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @Audit(action = "CHANGE_PASSWORD")
    @PostMapping("/password/change")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(currentUserId(), request.getOldPassword(), request.getNewPassword());
        return ResponseEntity.ok().build();
    }

    /** A7：找回密码（公开）。凭邮箱验证码重置，不需要旧密码。 */
    @Operation(summary = "找回密码（公开）", description = "邮箱 + 验证码 + 新密码；成功后该用户所有令牌失效")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已重置（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "CODE_INVALID / CODE_EXPIRED / CODE_TOO_MANY_ATTEMPTS / PASSWORD_POLICY_VIOLATION / 触发限流")
    })
    @Audit(action = "RESET_PASSWORD", target = "#request.email")
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        accountService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword(),
            clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok().build();
    }

    /**
     * 取当前登录用户 ID（由 {@code JwtAuthFilter} 写入 principal，解析逻辑见
     * {@link com.billing.license.security.CurrentUserResolver}）。
     */
    private UUID currentUserId() {
        return CurrentUserResolver.currentUserId();
    }
}
