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

    /** 客户端机器码（兑换时绑定到 License，架构十一.4） */
    private String machineId;

    /** 客户端 IP（风控：高频兑换/暴力猜测限流，架构十七） */
    private String clientIp;
}
