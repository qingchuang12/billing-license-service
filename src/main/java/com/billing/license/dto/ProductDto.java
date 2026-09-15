package com.billing.license.dto;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 产品 DTO
 * 用于传输产品信息数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "产品信息")
public class ProductDto {
    
    /** 产品 SKU（库存量单位），唯一标识产品 */
    @Schema(description = "产品 SKU（库存量单位），全局唯一", example = "PRO_LIFETIME")
    private String sku;
    
    /** 产品名称 */
    @Schema(description = "产品名称", example = "Pro 永久授权")
    private String name;
    
    /** 产品描述 */
    @Schema(description = "产品描述", example = "单机永久授权，含 1 年更新服务")
    private String description;
    
    /** 产品价格 */
    @Schema(description = "产品价格；币种由 currency 字段标明", example = "299.00")
    private BigDecimal price;
    
    /** 货币类型，如 USD、CNY */
    @Schema(description = "价格币种", example = "CNY", allowableValues = {"CNY", "USD"})
    private Currency currency;
    
    /** 计费周期：ONE_TIME, MONTHLY, QUARTERLY, YEARLY, LIFETIME */
    @Schema(description = "计费周期：ONE_TIME=一次性，MONTHLY=按月，QUARTERLY=按季，YEARLY=按年，LIFETIME=永久",
            example = "LIFETIME",
            allowableValues = {"ONE_TIME", "MONTHLY", "QUARTERLY", "YEARLY", "LIFETIME"})
    private Product.BillingCycle billingCycle;
    
    /** License 有效期天数 */
    @Schema(description = "License 有效期天数；永久授权（LIFETIME）时为 null", example = "365")
    private Integer licenseDurationDays;
}
