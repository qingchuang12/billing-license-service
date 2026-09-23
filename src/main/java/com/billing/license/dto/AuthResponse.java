package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 注册 / 登录响应（A2 / A3）。
 *
 * <p><b>令牌不落 cookie</b>：服务端无会话，桌面客户端从响应体取出后自行保存，
 * 后续请求以 {@code Authorization: Bearer <accessToken>} 携带。
 *
 * <p><b>两种形态</b>（plan-7.0 / M3，管理员 MFA）：
 * <ul>
 *   <li><b>登录完成</b>：{@code accessToken} 非空、{@code mfaRequired=false}——注册与本形态同构；</li>
 *   <li><b>待第二因子</b>：{@code accessToken} 为 {@code null}、{@code mfaRequired=true}，
 *       并携带短时效 {@code mfaTicket}。此时 {@code user} 也<b>刻意返回 null</b>——
 *       未过第二因子前不泄漏任何账号信息。客户端须用票据调
 *       {@code POST /api/account/mfa/verify} 换取真正的令牌。</li>
 * </ul>
 *
 * <p>复用同一响应模型而非另立 {@code LoginResponse}：与 {@link UserProfileResponse} 同思路，
 * 避免「同一次登录两种视图」在文档与客户端侧分叉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "注册 / 登录响应。待第二因子时 accessToken 与 user 均为 null，需用 mfaTicket 换取令牌")
public class AuthResponse {

    @Schema(description = "访问令牌（JWT，HS256）；以 `Authorization: Bearer <token>` 携带。"
            + "待第二因子（mfaRequired=true）时为 null",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI...")
    private String accessToken;

    @Schema(description = "令牌有效期（秒）；默认 604800（7 天），由 account.token-ttl-hours 决定",
            example = "604800")
    private long expiresIn;

    @Schema(description = "当前用户资料；待第二因子时为 null")
    private UserProfileResponse user;

    @Schema(description = "是否需要第二因子校验（该账号已启用 MFA）。为 true 时须走 "
            + "POST /api/account/mfa/verify", example = "false")
    private boolean mfaRequired;

    @Schema(description = "一次性登录票据（仅 mfaRequired=true 时非空）；有效期由 "
            + "account.mfa.ticket-ttl-seconds 决定，改密 / 登出即失效",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJ0eXAiOiJtZmEifQ...")
    private String mfaTicket;

    @Schema(description = "可用的第二因子方式；顺序即推荐优先级。TOTP=认证器动态码，"
            + "EMAIL=邮箱兜底码（仅在服务端开启邮箱兜底时出现）",
            example = "[\"TOTP\",\"EMAIL\"]")
    private List<String> mfaMethods;

    /**
     * 构造「待第二因子」响应。
     *
     * <p>刻意让 {@code accessToken} 与 {@code user} 保持 null：登录尚未完成，
     * 此时返回用户资料会让「只拿到密码的攻击者」提前获得账号信息。
     */
    public static AuthResponse pendingSecondFactor(String mfaTicket, List<String> mfaMethods) {
        return AuthResponse.builder()
            .mfaRequired(true)
            .mfaTicket(mfaTicket)
            .mfaMethods(mfaMethods)
            .build();
    }
}
