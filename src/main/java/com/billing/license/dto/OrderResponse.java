package com.billing.license.dto;

import com.billing.license.entity.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Schema(description = "订单详情")
public class OrderResponse {
    
    /** 订单唯一标识 */
    @Schema(description = "订单唯一标识", example = "3f1a8c92-5b7e-4d21-9a03-6c8f2d4e5b1a")
    private UUID id;
    
    /** 订单编号，业务系统中的订单号 */
    @Schema(description = "业务订单号", example = "ORD20260914224937123")
    private String orderNumber;
    
    /** 客户唯一标识 */
    @Schema(description = "客户唯一标识", example = "8b2c4d6e-1f3a-4b5c-9d7e-0a1b2c3d4e5f")
    private UUID customerId;
    
    /** 订单总金额 */
    @Schema(description = "订单总金额；精度与币种一致", example = "299.00")
    private BigDecimal totalAmount;
    
    /** 货币类型，如 USD、CNY */
    @Schema(description = "结算货币", example = "CNY", allowableValues = {"CNY", "USD"})
    private Currency currency;
    
    /** 订单状态：PENDING, CONFIRMED, PROCESSING, COMPLETED, CANCELLED, REFUNDED, PAID */
    @Schema(description = "订单状态：PENDING=待支付，CONFIRMED=已确认，PROCESSING=处理中，"
            + "COMPLETED=已完成，CANCELLED=已取消，REFUNDED=已退款，REFUND_FAILED=退款失败，PAID=已支付",
            example = "PAID",
            allowableValues = {"PENDING", "CONFIRMED", "PROCESSING", "COMPLETED", "CANCELLED", "REFUNDED", "REFUND_FAILED", "PAID"})
    private String status;
    
    /** 支付状态：UNPAID, PAID, PARTIALLY_REFUNDED, REFUNDED, FAILED */
    @Schema(description = "支付状态：UNPAID=未支付，PAID=已支付，PARTIALLY_REFUNDED=部分退款，"
            + "REFUNDED=已退款，FAILED=支付失败",
            example = "PAID",
            allowableValues = {"UNPAID", "PAID", "PARTIALLY_REFUNDED", "REFUNDED", "FAILED"})
    private String paymentStatus;
    
    /** 订单创建时间 */
    @Schema(description = "订单创建时间（ISO-8601，无时区）", example = "2026-09-14T22:49:37")
    private LocalDateTime createdAt;
}
