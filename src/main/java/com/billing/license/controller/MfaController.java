package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.dto.*;
import com.billing.license.security.CurrentUserResolver;
import com.billing.license.service.MfaService;
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
 * 二次因子绑定管理控制器（plan-7.0 / M3）。
 *
 * <p>挂 {@code /api/admin/mfa/**} 而非 {@code /api/account/mfa/**}，有两重用意：
 * <ol>
 *   <li>{@code SecurityConfig} 按路径前缀授权，{@code ROLE_ADMIN} 由框架统一保证，
 *       服务层不必再判角色；</li>
 *   <li>绑定入口与业务角色绑定——B8 的需求就是「<b>管理员</b>可开启 MFA」，
 *       普通用户不开放此入口（挑战条件只看 {@code mfa_enabled}，故逻辑本身可扩展，
 *       但入口不开放）。</li>
 * </ol>
 */
@Tag(name = "管理端 · 二次因子", description = "管理员自助开启 / 解绑二次因子（TOTP + 邮箱兜底）")
@RestController
@RequestMapping("/api/admin/mfa")
@RequiredArgsConstructor
public class MfaController {

    private final MfaService mfaService;
    private final ClientIpResolver clientIpResolver;

    /** 查询绑定状态（不回显任何密钥材料）。 */
    @Operation(summary = "查询二次因子状态",
            description = "返回是否启用、启用时间与邮箱兜底是否开放；不回显密钥")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌"),
            @ApiResponse(responseCode = "403", description = "非管理员")
    })
    @GetMapping("/status")
    public ResponseEntity<MfaStatusResponse> status() {
        return ResponseEntity.ok(mfaService.status(currentUserId()));
    }

    /**
     * 生成密钥（尚未启用）。须重新输入当前密码。
     *
     * <p>响应中的 {@code secret} 与 {@code otpauthUri} <b>仅此一次回显</b>，请当场录入认证器。
     */
    @Operation(summary = "生成二次因子密钥（须当前密码）",
            description = "返回 Base32 密钥与 otpauth:// URI；须再调 activate 才生效")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已生成，等待激活"),
            @ApiResponse(responseCode = "400", description = "OLD_PASSWORD_MISMATCH / MFA_ALREADY_ENABLED / MFA_VERIFY_LIMIT"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌"),
            @ApiResponse(responseCode = "403", description = "非管理员")
    })
    @Audit(action = "MFA_ENROLL")
    @PostMapping("/enroll")
    public ResponseEntity<MfaEnrollResponse> enroll(@Valid @RequestBody MfaEnrollRequest request) {
        return ResponseEntity.ok(mfaService.enroll(currentUserId(), request.getPassword()));
    }

    /** 用认证器当前动态码激活。 */
    @Operation(summary = "激活二次因子",
            description = "校验认证器动态码，通过后 mfa_enabled 置真，下次登录即要求第二因子")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已启用（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "MFA_NOT_ENROLLED / MFA_CODE_INVALID / MFA_VERIFY_LIMIT"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌"),
            @ApiResponse(responseCode = "403", description = "非管理员")
    })
    @Audit(action = "MFA_ACTIVATE")
    @PostMapping("/activate")
    public ResponseEntity<Void> activate(@Valid @RequestBody MfaActivateRequest request) {
        mfaService.activate(currentUserId(), request.getCode());
        return ResponseEntity.ok().build();
    }

    /**
     * 解绑。须同时提供当前密码与动态码（或邮箱兜底码）。
     *
     * <p>认证器与邮箱都不可用时，只能由运维执行 {@code scripts/db/reset-admin-mfa.sql} 重置。
     */
    @Operation(summary = "解绑二次因子（须密码 + 验证码）",
            description = "关闭二次因子；须同时提供当前密码与认证器动态码 / 邮箱兜底码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已解绑（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "MFA_NOT_ENABLED / OLD_PASSWORD_MISMATCH / "
                    + "MFA_CODE_INVALID / MFA_VERIFY_LIMIT"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌"),
            @ApiResponse(responseCode = "403", description = "非管理员")
    })
    @Audit(action = "MFA_UNBIND")
    @PostMapping("/unbind")
    public ResponseEntity<Void> unbind(@Valid @RequestBody MfaUnbindRequest request) {
        mfaService.unbind(currentUserId(), request.getPassword(), request.getCode());
        return ResponseEntity.ok().build();
    }

    /** 取当前登录管理员 ID（principal 由 {@code JwtAuthFilter} 写入）。 */
    private UUID currentUserId() {
        return CurrentUserResolver.currentUserId();
    }

    // ==================== 敏感动作二次确认（step-up，plan-7.0 / P2） ====================

    /**
     * 为即将执行的敏感动作发送邮箱兜底码。
     *
     * <p>仅邮箱兜底场景需要：TOTP 用户的动态码本就在认证器上，直接调 verify。
     */
    @Operation(summary = "敏感动作二次确认：发送邮箱兜底码",
            description = "为受保护的敏感动作（代重置 / 改角色 / 启停）发送邮箱验证码；"
                    + "仅在邮箱兜底开放时可用，TOTP 用户直接调 /step-up/verify")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已发送（data 为 null）"),
            @ApiResponse(responseCode = "400", description = "MFA_NOT_ENABLED / MFA_EMAIL_FALLBACK_DISABLED / INVALID_STEP_UP_ACTION"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌"),
            @ApiResponse(responseCode = "403", description = "非管理员")
    })
    @Audit(action = "MFA_STEP_UP_CHALLENGE", target = "#request.action")
    @PostMapping("/step-up/challenge")
    public ResponseEntity<Void> stepUpChallenge(@Valid @RequestBody MfaStepUpChallengeRequest request,
                                                HttpServletRequest httpRequest) {
        // F7：管理员 step-up 兜底码——收件人是管理员，按回落 en（不取买家 locale）
        mfaService.stepUpChallenge(currentUserId(), request.getAction(),
            clientIpResolver.resolve(httpRequest), null);
        return ResponseEntity.ok().build();
    }

    /** 校验动态码，换取与动作绑定的短时效一次性确认令牌。 */
    @Operation(summary = "敏感动作二次确认：校验动态码",
            description = "通过后返回一次性确认令牌（默认 120 秒、单次使用、与动作绑定）；"
                    + "随后的敏感动作请求须以 X-Step-Up-Token 头携带")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "校验通过，返回确认令牌"),
            @ApiResponse(responseCode = "400", description = "MFA_NOT_ENABLED / MFA_CODE_INVALID / MFA_VERIFY_LIMIT / INVALID_STEP_UP_ACTION"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌"),
            @ApiResponse(responseCode = "403", description = "非管理员")
    })
    @Audit(action = "MFA_STEP_UP_VERIFY", target = "#request.action")
    @PostMapping("/step-up/verify")
    public ResponseEntity<MfaStepUpResponse> stepUpVerify(@Valid @RequestBody MfaStepUpVerifyRequest request) {
        return ResponseEntity.ok(mfaService.stepUpVerify(currentUserId(), request.getAction(), request.getCode()));
    }
}
