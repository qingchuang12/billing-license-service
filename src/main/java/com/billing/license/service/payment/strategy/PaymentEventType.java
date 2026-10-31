package com.billing.license.service.payment.strategy;

/**
 * 渠道回调的**业务语义**（与 {@code WebhookPayload.status} 的资金状态分开表达）。
 *
 * <p>为什么必须分开：Creem 的 {@code subscription.expired} 事件里 {@code status} 仍是
 * {@code active}、{@code subscription.active} 只是同步事件而 {@code subscription.paid}
 * 才是收款——只看 status 会把到期当续费、把同步当收款。回调分发一律按本枚举，
 * 资金状态只用于更新 payment 流水。
 */
public enum PaymentEventType {

    /** 一次性收款已确认（可发货） */
    PAYMENT_SUCCEEDED,

    /** 未付款的收银台被关闭/取消（只关单，不是退款，不得吊销权益） */
    PAYMENT_CANCELLED,

    /** 渠道确认退款成功（全/部分由金额区分） */
    REFUND_SUCCEEDED,

    /** 订阅首充或续期收款已确认（可发货/续期） */
    SUBSCRIPTION_PAYMENT,

    /** 订阅状态同步（active/update 等，不代表新收款） */
    SUBSCRIPTION_ACTIVE,

    /** 试用中（不产生收款） */
    SUBSCRIPTION_TRIALING,

    /** 欠费宽限（不发货、不吊销） */
    SUBSCRIPTION_PAST_DUE,

    /** 已暂停（不发货、不吊销） */
    SUBSCRIPTION_PAUSED,

    /** 已安排期末取消（期末前仍有效，可恢复） */
    SUBSCRIPTION_SCHEDULED_CANCEL,

    /** 订阅已取消（权益保留到已付周期结束） */
    SUBSCRIPTION_CANCELED,

    /** 订阅已到期（立即失效） */
    SUBSCRIPTION_EXPIRED,

    /** 未知/仅审计事件 */
    OTHER;

    /** 是否代表一笔**新确认**的收款（只有这类事件允许发货/续期）。 */
    public boolean isConfirmedPayment() {
        return this == PAYMENT_SUCCEEDED || this == SUBSCRIPTION_PAYMENT;
    }

    /** 是否订阅类事件（进入订阅生命周期处理）。 */
    public boolean isSubscription() {
        return name().startsWith("SUBSCRIPTION_");
    }
}
