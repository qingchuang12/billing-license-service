package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.*;
import com.billing.license.security.CurrentUserResolver;
import com.billing.license.service.AccountAssetService;
import com.billing.license.service.AccountRefundService;
import com.billing.license.service.LicenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 用户资产控制器（U1「用户密钥简单管理」）：当前登录用户自助查看本人名下的
 * License（证书/密钥）、订阅与订单，并自助发起退款、释放设备绑定。
 *
 * <p><b>鉴权</b>：默认只读查询；写操作为 {@code POST /orders/{orderNumber}/refund}（自助退款）
 * 与 {@code POST /licenses/{licenseKey}/unbind}（释放设备绑定）。全部挂在 {@code /api/account/**}
 * 的 {@code ROLE_USER} 保护区（{@code Authorization: Bearer <JWT>}），{@code SecurityConfig} 零新增规则。
 * 管理端按邮箱/订单维度查询的入口仍在 {@code /api/admin/**}，二者互不重叠。
 *
 * <p><b>访客认领</b>：购买/兑换时按邮箱自动建立的访客账户，先经
 * {@code POST /api/account/password/reset} 设密码，再用该邮箱登录即可查看。
 */
@Tag(name = "我的资产", description = "当前登录用户自助查看本人 License / 订阅 / 订单，并自助退款、释放设备绑定")
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountAssetController {

    private final AccountAssetService accountAssetService;
    /** plan-4.1：用户端自助退款（归属校验 + 折算 + 复用 AdminService 退款链路） */
    private final AccountRefundService accountRefundService;
    /** plan-7.0 决策 B6：授权已绑他机时，用户登录账号页手动解绑的恢复路径 */
    private final LicenseService licenseService;

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

    /**
     * 订单申请退款（plan-4.1）：按 License 剩余有效期线性折算可退金额（可能构成部分退款），
     * 直接退款、不设审核；退款成功后该订单全部 License 立即作废。
     *
     * <p>越权（他人订单）按 {@code ORDER_NOT_FOUND} 语义返回，不泄露订单存在性；
     * 同用户退款申请频控 5 次/小时（{@code billing.refund.user-refund-max}）。
     */
    @Operation(summary = "订单申请退款（需登录）",
            description = "对本人已支付订单发起退款：可退额按 License 剩余有效期线性折算（部分退款），"
                    + "退款成功后该订单全部 License 立即作废。越权订单按 404 语义返回。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "退款成功，返回实退金额与退款后支付状态"),
            @ApiResponse(responseCode = "400",
                    description = "订单不存在/无权访问、不满足退款条件、已退款、渠道退款失败或申请过于频繁"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @Audit(action = "USER_REFUND_REQUEST", target = "#orderNumber", detail = "#body.reason")
    @PostMapping("/orders/{orderNumber}/refund")
    public ResponseEntity<UserRefundResponse> refundOrder(
            @Parameter(description = "业务订单号", required = true) @PathVariable String orderNumber,
            @Parameter(description = "退款原因（可选）") @RequestBody(required = false) UserRefundRequest body) {
        String reason = body != null ? body.getReason() : null;
        return ResponseEntity.ok(accountRefundService.requestRefund(currentUserId(), orderNumber, reason));
    }

    /**
     * 释放本人 License 的设备绑定（plan-7.0 决策 B6）。
     *
     * <p><b>为什么需要它</b>：{@code POST /api/licenses/activate} 在「该授权已绑定到另一台机器」时
     * 返回 {@code MACHINE_MISMATCH} 而**不自动改绑**。这条件若无出口就是死路——本端点即那个出口：
     * 用户在账号页手动解绑后，即可把该授权重新激活到新设备。
     *
     * <p>归属凭证是「登录态 + 归属」（{@code License.customerId == 登录 userId}），
     * 与客户端凭「旧授权签名令牌」解绑的 {@code POST /api/licenses/unbind} 互补：
     * 后者适用于拿得到 token 的同机换绑，本端点适用于换机后令牌落在旧机器上的场景。
     *
     * <p>只释放设备绑定，<b>不吊销授权本身</b>；越权（他人 License）按 {@code LICENSE_NOT_FOUND}
     * 语义返回，不泄露其存在性；本就未绑定时幂等返回成功。
     */
    @Operation(summary = "释放设备绑定（需登录）",
            description = "释放本人 License 当前绑定的设备，以便把该授权重新激活到新设备。"
                    + "只解绑不吊销；他人 License 按 404 语义返回；未绑定时幂等成功。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "释放成功（或本就未绑定）"),
            @ApiResponse(responseCode = "400", description = "License 不存在/无权访问、已吊销"),
            @ApiResponse(responseCode = "401", description = "缺少或非法令牌")
    })
    @Audit(action = "USER_LICENSE_UNBIND", target = "#licenseKey")
    @PostMapping("/licenses/{licenseKey}/unbind")
    public ResponseEntity<Void> unbindMyLicense(
            @Parameter(description = "License 密钥", required = true) @PathVariable String licenseKey) {
        licenseService.unbindByOwner(licenseKey, currentUserId());
        return ResponseEntity.ok().build();
    }
}
