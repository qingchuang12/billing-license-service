-- ============================================================================
-- Flyway 迁移 V5（B18）—— 订阅制生命周期表（托管 Paddle/Stripe 对账）
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- B18：订阅制（Q1 托管 Paddle/Stripe Billing）
-- 订阅生命周期由渠道托管，本表仅做对账与 License 联动（首充绑定 / 续费延长 / 取消作废）

CREATE TABLE subscriptions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                    UUID,
    customer_id                 UUID NOT NULL,
    product_id                  UUID,
    provider                    VARCHAR(50)  NOT NULL,
    provider_subscription_id    VARCHAR(255) NOT NULL,
    status                      VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    current_period_start        TIMESTAMP,
    current_period_end          TIMESTAMP,
    cancel_at_period_end        BOOLEAN      NOT NULL DEFAULT FALSE,
    license_id                  UUID,
    created_at                  TIMESTAMP    NOT NULL,
    updated_at                  TIMESTAMP,
    CONSTRAINT uq_subscriptions_provider_sub UNIQUE (provider, provider_subscription_id)
);

COMMENT ON TABLE  subscriptions IS '订阅记录：关联渠道订阅、订单与已签发 License，驱动续期/作废';
COMMENT ON COLUMN subscriptions.provider_subscription_id IS '渠道侧订阅 ID（Paddle subscription_id / Stripe subscription id）';
COMMENT ON COLUMN subscriptions.license_id IS '关联已签发的 License，续期延长其 expires_at，取消时置 EXPIRED';

CREATE INDEX idx_subscriptions_order_id ON subscriptions (order_id);
CREATE INDEX idx_subscriptions_customer_id ON subscriptions (customer_id);
