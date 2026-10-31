-- #55：支付尝试与不可变资金账。
-- 旧 payments 同时承担「尝试」与「资金流水」两种语义，导致换渠道覆盖历史、退款行与收款行混在一起。
-- 本迁移把历史数据拆到两张表后删除旧表；新代码只读写新表。

CREATE TABLE IF NOT EXISTS payment_attempts (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    order_number VARCHAR(64),
    payment_id VARCHAR(128) NOT NULL UNIQUE,
    transaction_id VARCHAR(128),
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3),
    method VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    metadata TEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    paid_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payment_attempts_order ON payment_attempts(order_id);
CREATE INDEX IF NOT EXISTS idx_payment_attempts_status ON payment_attempts(order_id, status);

CREATE TABLE IF NOT EXISTS payment_ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    order_number VARCHAR(64),
    attempt_id BIGINT,
    entry_type VARCHAR(16) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3),
    provider VARCHAR(32),
    channel_txn_id VARCHAR(128),
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    metadata TEXT
);
CREATE INDEX IF NOT EXISTS idx_ledger_order ON payment_ledger_entries(order_id);
CREATE INDEX IF NOT EXISTS idx_ledger_occurred ON payment_ledger_entries(occurred_at);
CREATE INDEX IF NOT EXISTS idx_ledger_provider ON payment_ledger_entries(provider, occurred_at);

-- 历史尝试：排除旧退款行（REFUND- 前缀 / REFUNDED 状态）
INSERT INTO payment_attempts (order_id, order_number, payment_id, transaction_id, amount, currency,
                              method, status, metadata, error_message, created_at, updated_at, paid_at)
SELECT p.order_id, o.order_number, p.payment_id, p.transaction_id, p.amount, p.currency,
       COALESCE(p.method, p.channel), p.status, p.metadata, p.error_message,
       p.created_at, p.updated_at, p.paid_at
FROM payments p
LEFT JOIN orders o ON o.id::text = p.order_id
WHERE p.payment_id NOT LIKE 'REFUND-%' AND p.status <> 'REFUNDED';

-- 历史收款分录（只记已成功资金）
INSERT INTO payment_ledger_entries (order_id, order_number, attempt_id, entry_type, amount, currency,
                                    provider, channel_txn_id, idempotency_key, occurred_at, created_at, metadata)
SELECT p.order_id, o.order_number,
       (SELECT a.id FROM payment_attempts a WHERE a.payment_id = p.payment_id),
       'CHARGE', p.amount, p.currency, COALESCE(p.channel, p.method),
       COALESCE(p.transaction_id, p.payment_id),
       'charge:' || p.payment_id, COALESCE(p.paid_at, p.created_at), COALESCE(p.paid_at, p.created_at), p.metadata
FROM payments p
LEFT JOIN orders o ON o.id::text = p.order_id
WHERE p.status = 'SUCCESS' AND p.payment_id NOT LIKE 'REFUND-%';

-- 历史退款分录（旧退款行）
INSERT INTO payment_ledger_entries (order_id, order_number, attempt_id, entry_type, amount, currency,
                                    provider, channel_txn_id, idempotency_key, occurred_at, created_at, metadata)
SELECT p.order_id, o.order_number, NULL,
       'REFUND', p.amount, p.currency, COALESCE(p.channel, p.method),
       COALESCE(p.transaction_id, p.payment_id),
       'refund:' || p.payment_id, COALESCE(p.paid_at, p.created_at), COALESCE(p.paid_at, p.created_at), p.metadata
FROM payments p
LEFT JOIN orders o ON o.id::text = p.order_id
WHERE p.status = 'REFUNDED' AND p.payment_id LIKE 'REFUND-%';

DROP TABLE payments;
