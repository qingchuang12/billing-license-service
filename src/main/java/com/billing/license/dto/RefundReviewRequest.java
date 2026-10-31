package com.billing.license.dto;

import lombok.Data;

@Data
public class RefundReviewRequest {
    /** 审核备注/拒绝原因 */
    private String note;
}
