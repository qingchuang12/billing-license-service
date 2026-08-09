package com.billing.license.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 兑换码请求 DTO
 * 用于接收客户端提交的兑换码兑换请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemCodeRequest {
    
    /** 兑换码字符串 */
    private String code;
    
    /** 客户唯一标识 */
    private String customerId;
}
