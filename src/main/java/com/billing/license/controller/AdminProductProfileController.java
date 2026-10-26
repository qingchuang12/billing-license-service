package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.AdminProductProfileRequest;
import com.billing.license.dto.AdminProductProfileView;
import com.billing.license.service.AdminProductProfileService;
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
 * 管理端产品档案（产品 → 套餐两层中的产品层）。
 *
 * <p>名称 / 描述 / 权益按产品码定义一次，该产品下的月付、半年、年付、永久套餐共享；
 * 产品码创建后锁定，不提供删除。数据修改只在前端做一次确认弹窗（MFA 仅用于登录）。
 */
@Tag(name = "产品档案", description = "管理端产品档案：按产品码定义名称/描述/权益（需管理员 JWT）")
@RestController
@RequestMapping("/api/admin/product-profiles")
@RequiredArgsConstructor
public class AdminProductProfileController {

    private final AdminProductProfileService adminProductProfileService;

    @Operation(summary = "产品档案列表",
            description = "返回全部产品档案（含挂载套餐数）；可按产品码 / 名称包含匹配过滤，按产品码升序")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "查询成功")})
    @Audit(action = "LIST_PRODUCT_PROFILES", target = "-")
    @GetMapping
    public ResponseEntity<List<AdminProductProfileView>> list(
            @Parameter(description = "关键字（产品码 / 名称，包含匹配，忽略大小写）")
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(adminProductProfileService.list(keyword));
    }

    @Operation(summary = "新增产品档案",
            description = "创建产品档案；产品码唯一且创建后不可修改，权益为产品级定义")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "参数校验失败 / 产品码已存在")
    })
    @Audit(action = "CREATE_PRODUCT_PROFILE", target = "#request.productCode")
    @PostMapping
    public ResponseEntity<AdminProductProfileView> create(@Valid @RequestBody AdminProductProfileRequest request) {
        return ResponseEntity.ok(adminProductProfileService.create(request));
    }

    @Operation(summary = "编辑产品档案",
            description = "更新名称 / 描述 / 权益；产品码不可修改。权益变更对该产品下全部套餐立即生效")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "档案不存在 / 产品码不可改 / 参数校验失败")
    })
    @Audit(action = "UPDATE_PRODUCT_PROFILE", target = "#profileId")
    @PatchMapping("/{profileId}")
    public ResponseEntity<AdminProductProfileView> update(
            @Parameter(description = "产品档案 ID", required = true) @PathVariable UUID profileId,
            @Valid @RequestBody AdminProductProfileRequest request) {
        return ResponseEntity.ok(adminProductProfileService.update(profileId, request));
    }
}
