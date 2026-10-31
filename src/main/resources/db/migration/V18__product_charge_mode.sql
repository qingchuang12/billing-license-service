-- #57：把「收款方式」从 billing_cycle 中拆出。
-- 历史数据按周期回填：MONTHLY/QUARTERLY/HALF_YEAR/YEARLY 为自动续扣，其余为一次性。
ALTER TABLE products ADD COLUMN IF NOT EXISTS charge_mode VARCHAR(24);
UPDATE products
SET charge_mode = CASE
    WHEN billing_cycle IN ('MONTHLY', 'QUARTERLY', 'HALF_YEAR', 'YEARLY') THEN 'AUTO_RECURRING'
    ELSE 'ONE_TIME'
END
WHERE charge_mode IS NULL;
ALTER TABLE products ALTER COLUMN charge_mode SET DEFAULT 'ONE_TIME';
ALTER TABLE products ALTER COLUMN charge_mode SET NOT NULL;
