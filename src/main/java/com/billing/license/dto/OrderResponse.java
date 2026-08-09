package com.billing.license.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单响应 DTO
 * 用于返回订单详细信息给客户端
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    
    /** 订单唯一标识 */
    private UUID id;
    
    /** 订单编号，业务系统中的订单号 */
    private String orderNumber;
    
    /** 客户唯一标识 */
    private UUID customerId;
    
    /** 订单总金额 */
    private BigDecimal totalAmount;
    
    /** 货币类型，如 USD、CNY */
    private String currency;
    
    /** 订单状态：PENDING, CONFIRMED, PROCESSING, COMPLETED, CANCELLED, REFUNDED, PAID */
    private String status;
    
    /** 支付状态：UNPAID, PAID, PARTIALLY_REFUNDED, REFUNDED, FAILED */
    private String paymentStatus;
    
    /** 订单创建时间 */
    private LocalDateTime createdAt;
}
