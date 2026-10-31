-- #56：支付核心与权益交付解耦。支付事务只写 PENDING 事件，适配器成功后置 DONE。
CREATE TABLE IF NOT EXISTS fulfillment_events (
    id UUID PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    order_number VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    processed_at TIMESTAMP,
    CONSTRAINT uk_fulfillment_order_type UNIQUE (order_id, event_type)
);
CREATE INDEX IF NOT EXISTS idx_fulfillment_status ON fulfillment_events(status, attempts, created_at);
