-- V2 migration: checkout sessions, event tables, order email column
-- Applied on top of V1 (ddl-auto=validate requires Flyway-managed schema changes)

-- 订单表增加 email 字段（用于发货通知）
ALTER TABLE orders ADD COLUMN IF NOT EXISTS email VARCHAR(255);

-- 统一收银台会话表
CREATE TABLE IF NOT EXISTS checkout_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    checkout_id VARCHAR(100) NOT NULL UNIQUE,
    order_id UUID REFERENCES orders(id) ON DELETE CASCADE,
    order_number VARCHAR(100),
    provider VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    amount DECIMAL(19,4),
    locale VARCHAR(16),
    country VARCHAR(8),
    machine_id VARCHAR(255),
    email VARCHAR(255),
    return_url TEXT,
    cancel_url TEXT,
    provider_session_id VARCHAR(255),
    pay_url TEXT,
    expires_at TIMESTAMP,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

COMMENT ON TABLE checkout_sessions IS '统一收银台会话表';
COMMENT ON COLUMN checkout_sessions.checkout_id IS '收银台会话ID（业务唯一）';
COMMENT ON COLUMN checkout_sessions.order_id IS '关联订单ID';
COMMENT ON COLUMN checkout_sessions.provider IS '支付服务商';
COMMENT ON COLUMN checkout_sessions.status IS '会话状态（CREATED/PENDING/PAID/FAILED/EXPIRED/CANCELED）';
COMMENT ON COLUMN checkout_sessions.currency IS '货币类型';
COMMENT ON COLUMN checkout_sessions.locale IS '语言区域';
COMMENT ON COLUMN checkout_sessions.machine_id IS '客户端机器码（用于直接签发绑定设备License）';
COMMENT ON COLUMN checkout_sessions.email IS '客户邮箱（用于发货通知）';

CREATE INDEX IF NOT EXISTS idx_checkout_sessions_order ON checkout_sessions(order_id);
CREATE INDEX IF NOT EXISTS idx_checkout_sessions_status ON checkout_sessions(status);

-- License 事件表（激活/兑换/重发/作废/校验失败等审计）
CREATE TABLE IF NOT EXISTS license_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_id UUID,
    license_key VARCHAR(100),
    event_type VARCHAR(50) NOT NULL,
    machine_id VARCHAR(255),
    ip VARCHAR(64),
    user_agent TEXT,
    app_version VARCHAR(64),
    detail TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE license_events IS 'License 事件/审计表';
COMMENT ON COLUMN license_events.license_id IS '关联License ID';
COMMENT ON COLUMN license_events.license_key IS 'License 密钥';
COMMENT ON COLUMN license_events.event_type IS '事件类型（ISSUED/REDEEMED/REISSUED/REVOKED/VERIFY_FAILED）';
COMMENT ON COLUMN license_events.machine_id IS '机器码';
COMMENT ON COLUMN license_events.ip IS '请求IP';

CREATE INDEX IF NOT EXISTS idx_license_events_license ON license_events(license_id);
CREATE INDEX IF NOT EXISTS idx_license_events_key ON license_events(license_key);

-- 支付事件表（Webhook 幂等/对账/审计）
CREATE TABLE IF NOT EXISTS payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(50) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(64),
    order_id VARCHAR(64),
    provider_payment_id VARCHAR(255),
    amount DECIMAL(19,4),
    currency VARCHAR(3),
    signature_valid BOOLEAN,
    processed BOOLEAN NOT NULL DEFAULT false,
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE payment_events IS '支付事件表（Webhook 幂等/对账/审计）';
COMMENT ON COLUMN payment_events.provider IS '支付服务商';
COMMENT ON COLUMN payment_events.event_id IS '服务商侧事件ID（幂等去重）';
COMMENT ON COLUMN payment_events.order_id IS '关联订单号';
COMMENT ON COLUMN payment_events.signature_valid IS '签名验证是否通过';
COMMENT ON COLUMN payment_events.processed IS '是否已处理';

CREATE INDEX IF NOT EXISTS idx_payment_events_event ON payment_events(provider, event_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_order ON payment_events(order_id);
