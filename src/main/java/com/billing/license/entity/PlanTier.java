package com.billing.license.entity;

/**
 * 产品档位/等级。
 *
 * 与 {@link Product#billingCycle} 组合表达三种付费模式：
 * <ul>
 *   <li>买断（Pro / Pro Plus）= tier × ONE_TIME / LIFETIME</li>
 *   <li>订阅制 = tier × MONTHLY / QUARTERLY / YEARLY</li>
 * </ul>
 */
public enum PlanTier {
    /** 基础版 */
    PRO,
    /** 高级版（权益更全，如 API 访问、优先支持） */
    PRO_PLUS
}
