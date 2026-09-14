package com.billing.license.dto;

import com.billing.license.entity.Currency;
import com.billing.license.entity.Product;
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
public class ProductDto {
    
    /** 产品 SKU（库存量单位），唯一标识产品 */
    private String sku;
    
    /** 产品名称 */
    private String name;
    
    /** 产品描述 */
    private String description;
    
    /** 产品价格 */
    private BigDecimal price;
    
    /** 货币类型，如 USD、CNY */
    private Currency currency;
    
    /** 计费周期：ONE_TIME, MONTHLY, QUARTERLY, YEARLY, LIFETIME */
    private Product.BillingCycle billingCycle;
    
    /** License 有效期天数 */
    private Integer licenseDurationDays;
}
