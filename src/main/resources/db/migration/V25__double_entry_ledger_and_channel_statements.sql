-- ============================================================================
-- Flyway 迁移 V25 —— 复式记账科目 + 渠道账单对账
--
-- 1) payment_ledger_entries 每行代表一笔平衡分录：借（debit_account）/贷（credit_account）。
--    CHARGE：借 CHANNEL_CLEARING / 贷 REVENUE
--    REFUND：借 REVENUE_REFUND / 贷 CHANNEL_CLEARING
--    FEE   ：借 FEE_EXPENSE / 贷 CHANNEL_CLEARING
--    REVERSAL：交换原分录的借贷方向（reversal_of 指向原分录）
-- 2) channel_statement_lines 保存渠道账单行，用于与 ledger 按 channel_txn_id 对账。
-- ============================================================================

ALTER TABLE payment_ledger_entries ADD COLUMN IF NOT EXISTS debit_account VARCHAR(64);
ALTER TABLE payment_ledger_entries ADD COLUMN IF NOT EXISTS credit_account VARCHAR(64);
ALTER TABLE payment_ledger_entries ADD COLUMN IF NOT EXISTS reversal_of BIGINT;

UPDATE payment_ledger_entries
SET debit_account = 'CHANNEL_CLEARING', credit_account = 'REVENUE'
WHERE entry_type = 'CHARGE' AND debit_account IS NULL;

UPDATE payment_ledger_entries
SET debit_account = 'REVENUE_REFUND', credit_account = 'CHANNEL_CLEARING'
WHERE entry_type = 'REFUND' AND debit_account IS NULL;

CREATE INDEX IF NOT EXISTS idx_ledger_reversal_of ON payment_ledger_entries(reversal_of);

CREATE TABLE IF NOT EXISTS channel_statement_lines (
    id                BIGSERIAL PRIMARY KEY,
    provider          VARCHAR(32) NOT NULL,
    statement_date    DATE NOT NULL,
    channel_txn_id    VARCHAR(128) NOT NULL,
    entry_type        VARCHAR(16) NOT NULL,
    amount            NUMERIC(10, 2) NOT NULL,
    fee               NUMERIC(10, 2) NOT NULL DEFAULT 0,
    currency          VARCHAR(3) NOT NULL,
    order_number      VARCHAR(64),
    raw_line          TEXT,
    match_status      VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED',
    matched_ledger_id BIGINT,
    imported_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP,
    CONSTRAINT uk_channel_statement_line UNIQUE (provider, channel_txn_id, entry_type)
);

CREATE INDEX IF NOT EXISTS idx_channel_statement_date
    ON channel_statement_lines (provider, statement_date);
CREATE INDEX IF NOT EXISTS idx_channel_statement_status
    ON channel_statement_lines (provider, match_status);

COMMENT ON COLUMN payment_ledger_entries.debit_account IS '借方科目（复式记账）';
COMMENT ON COLUMN payment_ledger_entries.credit_account IS '贷方科目（复式记账）';
COMMENT ON COLUMN payment_ledger_entries.reversal_of IS '冲正分录指向的原分录 ID';
COMMENT ON TABLE channel_statement_lines IS '渠道账单行：与 payment_ledger_entries 按 channel_txn_id 对账';
