package com.billing.license.dto;

import lombok.Data;

@Data
public class RefundTransferRequest {
    /** BANK / ALIPAY / OTHER */
    private String transferChannel;
    /** 转账流水号/回单号 */
    private String transferReference;
    private String note;
}
