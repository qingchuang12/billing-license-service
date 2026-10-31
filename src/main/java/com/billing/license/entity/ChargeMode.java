package com.billing.license.entity;

/**
 * 收款方式（#57）：与「权益期限」「支付呈现形态」解耦。
 *
 * <p>{@code ONE_TIME} 表示只扣一次款，权益可有限期（如 30 天）也可永久；
 * {@code AUTO_RECURRING} 表示渠道托管自动续扣，权益按周期续期。二者不再由
 * {@code billingCycle} 一个字段同时表达，避免把「一次性买 30 天」误当订阅。
 */
public enum ChargeMode {
    ONE_TIME,
    AUTO_RECURRING
}
