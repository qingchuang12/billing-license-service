package com.billing.license.controller;

import com.billing.license.dto.CheckoutRequest;
import com.billing.license.dto.CheckoutResponse;
import com.billing.license.dto.SelectProviderRequest;
import com.billing.license.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 统一收银台控制器（架构十一）
 */
@Tag(name = "收银台", description = "创建收银台会话、选择支付方式、查询支付状态（公开端点，无需鉴权）")
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    /**
     * 创建收银台会话
     */
    @Operation(summary = "创建收银台会话",
            description = "按币种/语言自动判定国内或国际区域，返回可选支付方式，并创建订单与收银台会话")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "参数非法 / 商品不存在 / 商品未上架 / 无可用支付方式")
    })
    @PostMapping("/create")
    public ResponseEntity<CheckoutResponse> create(@Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(checkoutService.createCheckout(request));
    }

    /**
     * 选择支付方式
     */
    @Operation(summary = "选择支付方式",
            description = "选定渠道并创建真实支付会话，返回二维码或跳转链接；渠道创建失败时抛 PAYMENT_CREATE_FAILED")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "收银台不存在 / 渠道不支持 / 渠道创建失败")
    })
    @PostMapping("/{checkoutId}/select-provider")
    public ResponseEntity<CheckoutResponse> selectProvider(
            @Parameter(description = "收银台会话 ID", required = true) @PathVariable String checkoutId,
            @Valid @RequestBody SelectProviderRequest request) {
        return ResponseEntity.ok(checkoutService.selectProvider(checkoutId, request));
    }

    /**
     * 查询收银台/支付状态
     */
    @Operation(summary = "查询收银台/支付状态",
            description = "轮询支付状态；渠道确认已支付时返回 License 或兑换码（含 Webhook 丢失时的主动对账补偿）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "400", description = "收银台不存在")
    })
    @GetMapping("/{checkoutId}/status")
    public ResponseEntity<CheckoutResponse> status(
            @Parameter(description = "收银台会话 ID", required = true) @PathVariable String checkoutId) {
        return ResponseEntity.ok(checkoutService.getStatus(checkoutId));
    }
}
