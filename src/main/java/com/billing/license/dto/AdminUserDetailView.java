package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/**
 * 管理端用户详情视图（plan-7.0 账户基础功能 / P1）。
 *
 * <p>用户资料沿用 {@link AdminUserView} 脱敏口径（不含密码哈希、令牌版本、MFA 密钥材料），
 * 另聚合该用户名下的许可证 / 订单数量。许可证明细不在此返回——前端点「查看许可证」时
 * 复用既有 {@code GET /api/admin/licenses?customerEmail=}，避免为详情再造第二套许可证 DTO。
 */
@Value
@Builder
@Schema(description = "管理端用户详情：脱敏用户资料 + 名下许可证/订单计数")
public class AdminUserDetailView {

    @Schema(description = "用户资料（脱敏视图）")
    AdminUserView user;

    @Schema(description = "名下许可证数量", example = "3")
    long licenseCount;

    @Schema(description = "名下订单数量", example = "5")
    long orderCount;
}
