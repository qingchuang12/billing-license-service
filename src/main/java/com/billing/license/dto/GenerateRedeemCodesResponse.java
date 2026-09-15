package com.billing.license.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量生成兑换码结果（J3，2026-09-14）——替换 {@code POST /api/admin/redeem-codes/generate}
 * 原先返回的 {@code Map<String, Object>} 匿名结构。
 *
 * <p>字段与改造前的 Map 完全一一对应（success / count / codes），对外契约不变。
 * 码明文仅在本响应中出现一次，管理端需自行留存；事后对账用
 * {@link RedeemCodeView}（{@code GET /api/admin/redeem-codes}）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量生成兑换码结果；返回生成数量与码明文列表（明文仅此一次返回，请即时留存）")
public class GenerateRedeemCodesResponse {

    /** 与响应壳 {@code ApiResponse.success} 同义，为兼容既有客户端保留。 */
    @Schema(description = "是否生成成功；与响应壳 success 同义，为兼容既有客户端保留", example = "true")
    private boolean success;

    @Schema(description = "实际生成的兑换码数量", example = "100")
    private int count;

    @Schema(description = "兑换码明文列表（顺序即生成顺序）",
            example = "[\"RC-8F3C-1D2E-9A4B\", \"RC-5A7E-2B9C-3D1F\"]")
    private List<String> codes;

    public static GenerateRedeemCodesResponse of(List<String> codes) {
        return GenerateRedeemCodesResponse.builder()
                .success(true)
                .count(codes.size())
                .codes(codes)
                .build();
    }
}
