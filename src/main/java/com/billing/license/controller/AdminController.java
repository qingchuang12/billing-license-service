package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.LicenseResponse;
import com.billing.license.dto.OrderResponse;
import com.billing.license.dto.PaymentChannelStatus;
import com.billing.license.dto.RedeemCodeView;
import com.billing.license.entity.Order;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.AdminService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 管理后台控制器（架构十一.6、十六；接口简化主题 I）
 *
 * <p><b>I1（2026-09-14）鉴权收敛</b>：管理端不再自校验 {@code X-Admin-API-Key}
 * （它与 {@code X-API-Key} 校验的是同一份 {@code security.admin-api-keys}，纯冗余），
 * 统一由 {@code SecurityConfig} 对 {@code /api/admin/**} 要求 {@code ROLE_ADMIN}；
 * 鉴权模型由三档收敛为两档（公开 / X-API-Key）。
 *
 * <p><b>I2/I3（2026-09-14）查询收敛</b>：订单与 License 查询各自收敛为「一个端点 + 过滤参数」，
 * 删除 {@code /orders/status/{status}}、{@code /orders/{n}/licenses} 等重复入口。
 *
 * <p><b>I4/I5/I6（2026-09-14）管理动作归口</b>：签发 License、兑换码生成/导出/撤销统一收进
 * {@code /api/admin/**}，避免同类管理动作分散在多个前缀与鉴权表述下。
 *
 * <p>所有敏感操作经 {@link Audit} 声明式审计（{@code AuditAspect} 统一落库 + AUDIT logger）。
 */
@Tag(name = "管理后台",
        description = "订单查询/签发/退款、License 查询/作废/换机重发、兑换码生成/导出/撤销（需 X-API-Key 且具 ROLE_ADMIN）")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final LicenseService licenseService;
    private final RedeemCodeService redeemCodeService;
    private final PaymentServiceFactory paymentServiceFactory;

    /** i3：批量生成兑换码数量上限（默认 1000，可通过 billing.redeem-code.max-generate 调整） */
    @Value("${billing.redeem-code.max-generate:1000}")
    private int maxGenerateCount;

    // ==================== 订单 ====================

    @Operation(summary = "订单查询",
            description = "按状态 / 订单号 / 订单 ID 过滤；全部留空返回全部订单。"
                    + "（I2：合并原「列表 + 按状态 + by-id + by-number」四个入口）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "400", description = "非法状态值 / 非法订单 ID")
    })
    @Audit(action = "LIST_ORDERS", target = "#orderNumber")
    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponse>> listOrders(
            @Parameter(description = "订单状态：PENDING/CONFIRMED/PROCESSING/COMPLETED/CANCELLED/REFUNDED/REFUND_FAILED/PAID")
            @RequestParam(required = false) String status,
            @Parameter(description = "业务订单号（精确匹配，优先于 status）")
            @RequestParam(required = false) String orderNumber,
            @Parameter(description = "订单 UUID（精确匹配，优先于 status）")
            @RequestParam(required = false) String orderId) {
        Order.OrderStatus orderStatus = null;
        if (status != null && !status.isBlank()) {
            try {
                orderStatus = Order.OrderStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // E5：非法状态值统一抛异常 → 由切面记为 FAIL，保证 success 语义一致
                throw new BusinessException("INVALID_ORDER_STATUS", "非法的订单状态值: " + status);
            }
        }
        return ResponseEntity.ok(adminService.listOrders(orderStatus, orderNumber, orderId));
    }

    /**
     * 支付渠道配置自查：返回各渠道的启用状态、配置是否齐全、缺失的配置项名。
     *
     * <p>用途：部署完成（填好密钥）后先调本接口确认「配得对不对」，再去做真实下单验证，
     * 避免带着错误配置跑真实交易（回调收不到会导致收钱不发货）。仅返回配置项名，不回显任何密钥值。
     */
    @Operation(summary = "支付渠道配置自查",
            description = "返回各渠道启用状态/配置是否齐全/缺失项名（不回显密钥值），用于部署后自检")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @Audit(action = "CHECK_PAYMENT_CHANNELS")
    @GetMapping("/payment-channels")
    public ResponseEntity<List<PaymentChannelStatus>> paymentChannels() {
        return ResponseEntity.ok(paymentServiceFactory.getChannelStatuses());
    }

    @Operation(summary = "订单退款", description = "向支付渠道发起退款；渠道失败时标记 REFUND_FAILED 并抛异常，绝不谎报成功")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "退款成功"),
            @ApiResponse(responseCode = "400", description = "订单不存在 / 已退款 / 渠道退款失败")
    })
    @Audit(action = "REFUND_ORDER", target = "#orderNumber", detail = "#reason")
    @PostMapping("/orders/{orderNumber}/refund")
    public ResponseEntity<OrderResponse> refundOrder(
            @Parameter(description = "业务订单号", required = true) @PathVariable String orderNumber,
            @Parameter(description = "退款原因（可选）") @RequestParam(required = false) String reason) {
        // 成功/失败统一由 AuditAspect 记录；退款失败（含渠道未成功）由 AdminService 如实抛出
        return ResponseEntity.ok(adminService.refundOrder(orderNumber, reason));
    }

    /**
     * I4：为已支付订单签发 License（管理端）。
     *
     * <p>用于 Webhook 丢失/渠道回调异常时的运维补偿——与自动发放复用同一服务方法
     * （{@code LicenseService.issueLicensesForOrder}），因此天然幂等（重复调用返回已签发记录）。
     */
    @Operation(summary = "为订单签发 License（管理端）",
            description = "为已支付订单签发许可证（幂等，重复调用返回已签发记录）；用于 Webhook 丢失时的补偿")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "签发成功"),
            @ApiResponse(responseCode = "400", description = "订单不存在 / 订单未支付")
    })
    @Audit(action = "ISSUE_LICENSES", target = "#orderNumber")
    @PostMapping("/orders/{orderNumber}/issue")
    public ResponseEntity<List<LicenseResponse>> issueLicenses(
            @Parameter(description = "业务订单号", required = true) @PathVariable String orderNumber) {
        Order order = adminService.getOrderByNumber(orderNumber);
        return ResponseEntity.ok(licenseService.issueLicensesForOrder(order.getId()));
    }

    // ==================== License ====================

    @Operation(summary = "License 查询（管理端）",
            description = "按客户 ID / 订单号 / 状态过滤，全部留空返回全部；返回脱敏视图（不含 signedToken，含失效件）。"
                    + "（I3：合并原「按客户查询」与「订单下 License 列表」两个入口）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "400", description = "订单不存在 / 非法状态值")
    })
    @Audit(action = "LIST_LICENSES", target = "#orderNumber")
    @GetMapping("/licenses")
    public ResponseEntity<List<LicenseResponse>> listLicenses(
            @Parameter(description = "客户 UUID") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "业务订单号（精确匹配，优先于 customerId）")
            @RequestParam(required = false) String orderNumber,
            @Parameter(description = "License 状态：ACTIVE/EXPIRED/REVOKED/REISSUED")
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(adminService.listLicenses(customerId, orderNumber, status));
    }

    /**
     * 按 License 密钥查询详情（管理端）。
     *
     * <p>与公开的 {@code GET /api/licenses/verify/{licenseKey}} 的区别：verify 对失效件
     * （EXPIRED/REVOKED/REISSUED）直接返回 400，查不到；本端点不过滤状态，供售后排查失效原因。
     */
    @Operation(summary = "查询 License 详情（管理端）",
            description = "按 License 密钥查询详情，含已失效记录（EXPIRED/REVOKED/REISSUED）；返回脱敏视图（不回显 signedToken）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "400", description = "License 不存在")
    })
    @Audit(action = "GET_LICENSE", target = "#licenseKey")
    @GetMapping("/licenses/{licenseKey}")
    public ResponseEntity<LicenseResponse> getLicense(
            @Parameter(description = "License 密钥", required = true) @PathVariable String licenseKey) {
        return ResponseEntity.ok(adminService.getLicenseDetail(licenseKey));
    }

    // D2（2026-09-14）：吊销唯一入口——客户端自吊销端点已删除（其要求客户端持有管理密钥，语义矛盾）。
    @Operation(summary = "作废 License（管理端）", description = "管理端强制作废指定 License（需 X-API-Key）")
    @ApiResponse(responseCode = "200", description = "作废成功")
    @Audit(action = "REVOKE_LICENSE", target = "#licenseKey", detail = "#reason")
    @PostMapping("/licenses/{licenseKey}/revoke")
    public ResponseEntity<Void> revokeLicense(
            @Parameter(description = "License 密钥", required = true) @PathVariable String licenseKey,
            @Parameter(description = "作废原因（可选）") @RequestParam(required = false) String reason) {
        adminService.revokeLicense(licenseKey, reason);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "换机重发 License",
            description = "绑定新的机器码并重发 License；原证置 REISSUED（与退款吊销 REVOKED 区分）")
    @ApiResponse(responseCode = "200", description = "重发成功")
    @Audit(action = "REISSUE_LICENSE", target = "#licenseKey", detail = "#newMachineId")
    @PostMapping("/licenses/{licenseKey}/reissue")
    public ResponseEntity<LicenseResponse> reissueLicense(
            @Parameter(description = "原 License 密钥", required = true) @PathVariable String licenseKey,
            @Parameter(description = "新机器码", required = true) @RequestParam String newMachineId,
            @Parameter(description = "重发原因（可选）") @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(licenseService.reissueLicense(licenseKey, newMachineId, reason));
    }

    // ==================== 兑换码（I5/I6：由 /api/redeem/** 迁入） ====================

    /**
     * 批量生成兑换码。
     *
     * <p>I5：**返回码明文列表**——原实现只返回生成数量，管理端无法通过 API 取回码，
     * 「批量生成导入发卡平台」的用法实际不可行。
     */
    @Operation(summary = "批量生成兑换码（管理端）",
            description = "按产品 SKU 批量生成兑换码，返回生成数量与**码明文列表**")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "生成成功"),
            @ApiResponse(responseCode = "400", description = "数量非正 / 超过上限 / 产品不存在")
    })
    @Audit(action = "GENERATE_REDEEM_CODES", target = "#productSku")
    @PostMapping("/redeem-codes/generate")
    public ResponseEntity<Map<String, Object>> generateRedeemCodes(
            @Parameter(description = "产品 SKU", required = true) @RequestParam String productSku,
            @Parameter(description = "生成数量（正整数，≤ 配置上限）", required = true) @RequestParam int count,
            @Parameter(description = "过期时间（可选，ISO-8601）") @RequestParam(required = false) LocalDateTime expiresAt) {
        // i3：数量边界校验——非正或超上限一律拒绝，避免无脑循环写库造成资源耗尽
        if (count <= 0) {
            throw new BusinessException("INVALID_COUNT", "生成数量必须为正整数");
        }
        if (count > maxGenerateCount) {
            throw new BusinessException("COUNT_EXCEED_LIMIT",
                "批量生成数量超过上限（上限=" + maxGenerateCount + "），请分批生成");
        }
        List<String> codes = redeemCodeService.generateCodes(productSku, count, expiresAt);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", codes.size());
        response.put("codes", codes);
        return ResponseEntity.ok(response);
    }

    /**
     * I6：兑换码导出/对账——按产品 SKU + 状态检索（均可不传），返回码明文与状态。
     */
    @Operation(summary = "兑换码导出/对账（管理端）",
            description = "按产品 SKU + 状态检索兑换码（均可不传），返回码明文、状态、使用情况，供批量导出与对账")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "400", description = "非法状态值")
    })
    @Audit(action = "LIST_REDEEM_CODES", target = "#productSku")
    @GetMapping("/redeem-codes")
    public ResponseEntity<List<RedeemCodeView>> listRedeemCodes(
            @Parameter(description = "产品 SKU（可选）") @RequestParam(required = false) String productSku,
            @Parameter(description = "状态：UNUSED/USED/EXPIRED/REVOKED（可选）")
            @RequestParam(required = false) String status) {
        List<RedeemCodeView> views = redeemCodeService.listCodes(productSku, status).stream()
            .map(RedeemCodeView::from)
            .collect(Collectors.toList());
        return ResponseEntity.ok(views);
    }

    @Operation(summary = "撤销兑换码（管理端）", description = "作废指定兑换码（需 X-API-Key）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "撤销成功"),
            @ApiResponse(responseCode = "400", description = "兑换码不存在")
    })
    @Audit(action = "REVOKE_REDEEM_CODE", target = "#code")
    @PostMapping("/redeem-codes/revoke/{code}")
    public ResponseEntity<Void> revokeRedeemCode(
            @Parameter(description = "要撤销的兑换码", required = true) @PathVariable String code) {
        redeemCodeService.revokeCode(code);
        return ResponseEntity.ok().build();
    }
}
