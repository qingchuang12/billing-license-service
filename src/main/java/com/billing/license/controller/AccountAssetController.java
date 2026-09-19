package com.billing.license.controller;

import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.SubscriptionView;
import com.billing.license.security.CurrentUserResolver;
import com.billing.license.service.AccountAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 用户资产控制器（U1「用户密钥简单管理」）：当前登录用户自助查看本人名下的
 * License（证书/密钥）、订阅与订单。
 *
 * <p><b>鉴权</b>：全部为只读端点，挂在 {@code /api/account/**} 的 {@code ROLE_USER}
 * 保护区（{@code Authorization: Bearer <JWT>}），{@code SecurityConfig} 零新增规则。
 * 管理端按邮箱/订单维度查询的入口仍在 {@code /api/admin/**}，二者互不重叠。
 *
 * <p><b>访客认领</b>：购买/兑换时按邮箱自动建立的访客账户，先经
 * {@code POST /api/account/password/reset} 设密码，再用该邮箱登录即可查看。
 */
@Tag(name = "我的资产", description = "当前登录用户自助查看本人的 License / 订阅 / 订单（只读）")
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountAssetController {

    private final AccountAssetService accountAssetService;

    /** 我的 License（证书/密钥）列表，签发时间倒序；licenseKey 完整回显，不含 signedToken。 */
    @Operation(summary = "我的 License 列表（需登录）",
            description = "本人名下全部 License：密钥、产品、状态、有效期、绑定机器码；签发时间倒序")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功（无资产时为空数组）"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @GetMapping("/licenses")
    public ResponseEntity<List<LicenseResponse>> myLicenses() {
        return ResponseEntity.ok(accountAssetService.listMyLicenses(currentUserId()));
    }

    /** 我的订阅列表，创建时间倒序；含产品 SKU/名称与当前计费周期。 */
    @Operation(summary = "我的订阅列表（需登录）",
            description = "本人名下全部订阅：渠道、状态、产品、当前周期起止、是否到期取消；创建时间倒序")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功（无资产时为空数组）"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @GetMapping("/subscriptions")
    public ResponseEntity<List<SubscriptionView>> mySubscriptions() {
        return ResponseEntity.ok(accountAssetService.listMySubscriptions(currentUserId()));
    }

    /** 我的订单列表，下单时间倒序。 */
    @Operation(summary = "我的订单列表（需登录）",
            description = "本人名下全部订单：单号、金额、币种、订单/支付状态、下单时间；下单时间倒序")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功（无资产时为空数组）"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponse>> myOrders() {
        return ResponseEntity.ok(accountAssetService.listMyOrders(currentUserId()));
    }

    /** 取当前登录用户 ID（解析逻辑见 {@link CurrentUserResolver}）。 */
    private UUID currentUserId() {
        return CurrentUserResolver.currentUserId();
    }
}
