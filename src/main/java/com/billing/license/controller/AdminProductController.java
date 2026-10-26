package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.AdminProductMetaView;
import com.billing.license.dto.AdminProductRequest;
import com.billing.license.dto.AdminProductView;
import com.billing.license.service.AdminProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 管理端产品配置（目录维护）。
 *
 * <p>SKU 创建后锁定、不提供物理删除（订单明细 / License / 兑换码均以外键引用产品），
 * 停售走上下架。数据修改只在前端做一次确认弹窗；MFA 仅用于登录，不再对单个动作二次索要动态码。
 * 所有端点沿用 {@code /api/admin/**} 的 ROLE_ADMIN 鉴权，写操作全部经 {@link Audit} 留痕。
 */
@Tag(name = "产品配置", description = "管理端产品目录维护：列表、表单元数据、新增、编辑、上下架（需管理员 JWT）")
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final AdminProductService adminProductService;

    @Operation(summary = "产品列表（管理端）",
            description = "返回全部产品（含已下架）；可按关键字（SKU / 名称 / 产品码，包含匹配）与上下架状态过滤，按 SKU 升序")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功")
    })
    @Audit(action = "LIST_PRODUCTS", target = "-")
    @GetMapping
    public ResponseEntity<List<AdminProductView>> list(
            @Parameter(description = "关键字（SKU / 名称 / 产品码，包含匹配，忽略大小写）")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "上下架状态：true 仅上架 / false 仅下架 / 缺省全部")
            @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(adminProductService.list(keyword, active));
    }

    @Operation(summary = "产品表单元数据",
            description = "返回计费周期 / 产品档位枚举与权益标签（来自 billing.feature-labels），供管理端渲染表单")
    @ApiResponse(responseCode = "200", description = "查询成功")
    @Audit(action = "GET_PRODUCT_META", target = "-")
    @GetMapping("/meta")
    public ResponseEntity<AdminProductMetaView> meta() {
        return ResponseEntity.ok(adminProductService.meta());
    }

    @Operation(summary = "新增产品",
            description = "创建产品目录条目；SKU 唯一且创建后不可修改，双币价均为必填。新增默认上架，可用 active=false 创建草稿")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "参数校验失败 / SKU 已存在")
    })
    @Audit(action = "CREATE_PRODUCT", target = "#request.sku")
    @PostMapping
    public ResponseEntity<AdminProductView> create(@Valid @RequestBody AdminProductRequest request) {
        return ResponseEntity.ok(adminProductService.create(request));
    }

    @Operation(summary = "编辑产品",
            description = "更新全部售卖方字段（SKU 不可改、上下架走独立端点）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "产品不存在 / SKU 不可改 / 参数校验失败")
    })
    @Audit(action = "UPDATE_PRODUCT", target = "#productId")
    @PatchMapping("/{productId}")
    public ResponseEntity<AdminProductView> update(
            @Parameter(description = "产品 ID", required = true) @PathVariable UUID productId,
            @Valid @RequestBody AdminProductRequest request) {
        return ResponseEntity.ok(adminProductService.update(productId, request));
    }

    @Operation(summary = "产品上下架",
            description = "上架 / 下架产品；下架只影响新订单，历史订单与已签发许可证不受影响")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "变更成功"),
            @ApiResponse(responseCode = "400", description = "产品不存在")
    })
    @Audit(action = "CHANGE_PRODUCT_STATUS", target = "#productId", detail = "#active")
    @PatchMapping("/{productId}/active")
    public ResponseEntity<AdminProductView> changeActive(
            @Parameter(description = "产品 ID", required = true) @PathVariable UUID productId,
            @Parameter(description = "目标状态：true 上架 / false 下架", required = true) @RequestParam boolean active) {
        return ResponseEntity.ok(adminProductService.changeActive(productId, active));
    }
}
