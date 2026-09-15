package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册 / 登录响应（A2 / A3）。
 *
 * <p><b>令牌不落 cookie</b>：服务端无会话，桌面客户端从响应体取出后自行保存，
 * 后续请求以 {@code Authorization: Bearer <accessToken>} 携带。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "注册 / 登录响应；含访问令牌与用户资料")
public class AuthResponse {

    @Schema(description = "访问令牌（JWT，HS256）；以 `Authorization: Bearer <token>` 携带",
            example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI...")
    private String accessToken;

    @Schema(description = "令牌有效期（秒）；默认 604800（7 天），由 account.token-ttl-hours 决定",
            example = "604800")
    private long expiresIn;

    @Schema(description = "当前用户资料")
    private UserProfileResponse user;
}
