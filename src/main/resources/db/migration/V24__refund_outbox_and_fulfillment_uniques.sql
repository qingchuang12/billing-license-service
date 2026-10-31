-- ============================================================================
-- Flyway 迁移 V24 —— 退款 outbox + 发货幂等约束
--
-- 背景（2026-10-31 上线审计）：
--   1) 渠道退款 HTTP 原先在退款事务内，渠道成功但本地结算失败时钱已退、权益仍在，
--      且无补偿。新增 refund_requests 作为持久化退款意图/结果，后台任务负责收口。
--   2) 履约链路没有订单级锁与唯一约束，Webhook / 轮询补偿 / 定时重试并发时可能签出
--      两张 ACTIVE License 或两个兑换码。以下部分唯一索引把「同一订单同一产品只有一张
--      生效证」「同一订单只有一个兑换码」下沉到数据库。
-- ============================================================================

CREATE TABLE IF NOT EXISTS refund_requests (
    id                   UUID PRIMARY KEY,
    order_id             UUID NOT NULL,
    order_number         VARCHAR(64) NOT NULL,
    provider             VARCHAR(32) NOT NULL,
    channel_payment_id   VARCHAR(128),
    channel_refund_id    VARCHAR(128),
    refund_request_id    VARCHAR(128) NOT NULL,
    channel_request_id   VARCHAR(128),
    requested_amount     NUMERIC(12, 2) NOT NULL,
    amount               NUMERIC(12, 2) NOT NULL,
    currency             VARCHAR(8) NOT NULL,
    status               VARCHAR(24) NOT NULL,
    attempts             INTEGER NOT NULL DEFAULT 0,
    degraded             BOOLEAN NOT NULL DEFAULT FALSE,
    reason               VARCHAR(255),
    last_error           TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP,
    channel_succeeded_at TIMESTAMP,
    settled_at           TIMESTAMP,
    CONSTRAINT uk_refund_requests_order UNIQUE (order_id),
    CONSTRAINT uk_refund_requests_request_id UNIQUE (refund_request_id)
);

CREATE INDEX IF NOT EXISTS idx_refund_requests_status
    ON refund_requests (status, updated_at);

-- 同一订单同一产品只允许一张 ACTIVE 证；换机重发时旧证先置 REISSUED，再插新 ACTIVE。
-- 退款吊销会把证置 REVOKED，不占用该唯一槽位。
CREATE UNIQUE INDEX IF NOT EXISTS uk_licenses_active_order_product
    ON licenses (order_id, product_id)
    WHERE order_id IS NOT NULL AND status = 'ACTIVE';

-- 订单发货只允许一个兑换码；批量生成（无订单）的 order_id 为 NULL，不受约束。
CREATE UNIQUE INDEX IF NOT EXISTS uk_redeem_codes_order
    ON redeem_codes (order_id)
    WHERE order_id IS NOT NULL;

COMMENT ON TABLE refund_requests IS '退款意图/结果 outbox：渠道调用在事务外，状态机 PENDING→CHANNEL_SUCCEEDED→SETTLED，失败/未知可重试';
COMMENT ON INDEX uk_licenses_active_order_product IS '同一订单同一产品仅一张 ACTIVE License（并发发货兜底）';
COMMENT ON INDEX uk_redeem_codes_order IS '同一订单仅一个兑换码（并发发货兜底）';
