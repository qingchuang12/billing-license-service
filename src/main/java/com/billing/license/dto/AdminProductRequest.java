package com.billing.license.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品套餐新增 / 编辑请求（POST / PATCH {@code /api/admin/products}）。
 *
 * <ul>
 *   <li>{@code sku} 仅新增时生效：创建后锁定，编辑请求可留空，携带不同值将被服务端拒绝；</li>
 *   <li>{@code productCode} 必须指向已存在的产品档案（权益与产品级名称/描述在档案上维护）；</li>
 *   <li>{@code active} 仅新增时生效：默认 true，后续上下架走独立端点；</li>
 *   <li>双币价均为必填：缺任一区域价会让该区域下单被拒（C6，不做跨币种回退）。</li>
 * </ul>
 */
@Data
public class AdminProductRequest {

    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{1,63}$", message = "SKU 须为 2–64 位字母/数字/短横线")
    private String sku;

    @NotBlank(message = "请选择产品档案")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,63}$", message = "产品码须为 1–64 位字母/数字/短横线")
    private String productCode;

    @NotBlank(message = "产品名称不能为空")
    @Size(max = 255, message = "产品名称不得超过 255 字")
    private String name;

    @Size(max = 255, message = "英文名称不得超过 255 字")
    private String nameEn;

    private String description;

    private String descriptionEn;

    @NotNull(message = "人民币价格不能为空")
    @DecimalMin(value = "0.01", message = "人民币价格须大于 0")
    @Digits(integer = 10, fraction = 2, message = "人民币价格最多 10 位整数、2 位小数")
    private BigDecimal priceCny;

    @NotNull(message = "美元价格不能为空")
    @DecimalMin(value = "0.01", message = "美元价格须大于 0")
    @Digits(integer = 10, fraction = 2, message = "美元价格最多 10 位整数、2 位小数")
    private BigDecimal priceUsd;

    @NotBlank(message = "计费周期不能为空")
    private String billingCycle;

    @NotBlank(message = "产品档位不能为空")
    private String tier;

    @NotNull(message = "授权有效天数不能为空")
    @Min(value = 1, message = "授权有效天数须大于 0")
    @Max(value = 36500, message = "授权有效天数不得超过 36500")
    private Integer licenseDurationDays;

    @Min(value = 1, message = "更新权益天数须大于 0，留空表示不限制")
    @Max(value = 36500, message = "更新权益天数不得超过 36500")
    private Integer updateUntilDays;

    @Min(value = 1, message = "大版本上限须大于 0，留空表示不限制")
    @Max(value = 1000, message = "大版本上限不得超过 1000")
    private Integer maxMajorVersion;

    /** Stripe 订阅价目 ID（price_ 前缀）：订阅周期走 Stripe 用；留空 = 按周期内联 recurring price */
    @Size(max = 255, message = "Stripe 价目 ID 不得超过 255 字")
    private String stripePriceId;

    /** Paddle 订阅价目 ID（pri_ 前缀）：订阅周期走 Paddle 必填（Paddle 只认目录价目） */
    @Size(max = 255, message = "Paddle 价目 ID 不得超过 255 字")
    private String paddlePriceId;

    private Boolean active;

    /** 收银台「推荐」标记；编辑时也生效（纯展示属性） */
    private Boolean recommended;
}
