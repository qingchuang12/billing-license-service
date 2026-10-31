package com.billing.license.service.fulfillment;

import com.billing.license.entity.Order;

/**
 * 权益交付适配器（#56）。支付核心只产出履约事件，具体交付由实现类完成。
 */
public interface FulfillmentHandler {

    /** 事件类型，如 LICENSE。 */
    String type();

    /** 执行交付；必须幂等（同一订单重复调用不得重复发放）。 */
    void fulfill(Order order);
}
