package com.billing.license.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 产品档案新增 / 编辑请求。
 *
 * <p>产品码创建后锁定（套餐以它为关联键）；权益为产品级定义，同一产品的所有支付周期共享。
 */
@Data
public class AdminProductProfileRequest {

    @NotBlank(message = "产品码不能为空")
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,63}$", message = "产品码须为 1–64 位字母/数字/短横线")
    private String productCode;

    @NotBlank(message = "产品名称不能为空")
    @Size(max = 255, message = "产品名称不得超过 255 字")
    private String name;

    @Size(max = 255, message = "英文名称不得超过 255 字")
    private String nameEn;

    private String description;

    private String descriptionEn;

    private List<String> features;
}
