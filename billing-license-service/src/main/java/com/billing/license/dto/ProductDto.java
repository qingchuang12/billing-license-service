package com.billing.license.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDto {
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private String currency;
    private String billingCycle;
    private Integer licenseDurationDays;
}
