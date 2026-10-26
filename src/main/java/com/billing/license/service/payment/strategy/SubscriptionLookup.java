package com.billing.license.service.payment.strategy;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * #26：渠道侧订阅查询结果（用于首充回调丢失后的自动重修）。
 *
 * <p>只在「本地查不到订阅记录、事件也没带订单号」的补救路径上使用：由渠道 API 反查
 * 订阅对象，取回 {@link #orderId} 后即可走正常首充流程补建订阅记录并绑定 License。
 *
 * <p>{@link #orderId} 是重修的关键：它来自下单时写入订阅对象的 metadata / custom_data
 * （Stripe 见 {@code SubscriptionData.metadata}，Paddle 见交易 {@code custom_data}），
 * 缺失即无法定位订单，只能转人工。
 */
@Data
@Builder
public class SubscriptionLookup {

    /** 渠道侧订阅 ID（Stripe {@code sub_} / Paddle {@code sub_}） */
    private String subscriptionId;

    /** 回查到的本站订单号；为空表示重修失败 */
    private String orderId;

    /** 渠道侧订阅是否处于有效态（active / trialing） */
    private boolean active;

    /** 渠道侧当前计费周期起始（可为 null，由事件里的周期覆盖） */
    private LocalDateTime currentPeriodStart;

    /** 渠道侧当前计费周期结束（可为 null） */
    private LocalDateTime currentPeriodEnd;
}
