package com.billing.license.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * 创建订单请求 DTO
 * 用于接收客户端提交的订单创建请求数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {
    
    /** 客户唯一标识 */
    private UUID customerId;
    
    /** 订单项列表，包含购买的商品信息 */
    private List<OrderItemDto> items;
    
    /**
     * 订单项数据传输对象
     * 用于封装单个商品的 SKU 和数量信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemDto {
        
        /** 商品 SKU（库存量单位），用于唯一标识商品 */
        private String sku;
        
        /** 购买数量 */
        private Integer quantity;
    }
}
