-- ============================================================================
-- 支付账实一致性只读诊断（plan-8.0 #30）
--
-- 适用：V19 及之后（payment_attempts / payment_ledger_entries）。
-- 用法：psql "$DB_URL" -f scripts/db/diagnose_payment_integrity.sql
-- 纪律：全部为 SELECT，只读；不补 SUCCESS、不删退款行、不改任何数据。
--       结论必须逐笔回渠道取证（Alipay trade.query / Stripe Session.retrieve 等）后才可进入修复。
-- ============================================================================

-- 1) PAID 订单的资金凭证缺口：有 PAID 但无成功尝试 / 无 CHARGE 分录（收入被低估）
SELECT o.order_number,
       o.status,
       o.payment_status,
       o.total_amount,
       o.currency,
       (SELECT count(*) FROM payment_attempts a
         WHERE a.order_id = o.id::text AND a.status = 'SUCCESS') AS success_attempts,
       (SELECT count(*) FROM payment_attempts a
         WHERE a.order_id = o.id::text) AS total_attempts,
       (SELECT count(*) FROM payment_ledger_entries l
         WHERE l.order_id = o.id::text AND l.entry_type = 'CHARGE') AS charge_entries,
       (SELECT count(*) FROM payment_ledger_entries l
         WHERE l.order_id = o.id::text AND l.entry_type = 'REFUND') AS refund_entries
FROM orders o
WHERE o.payment_status IN ('PAID', 'PARTIALLY_REFUNDED')
  AND NOT EXISTS (SELECT 1 FROM payment_ledger_entries l
                   WHERE l.order_id = o.id::text AND l.entry_type = 'CHARGE')
ORDER BY o.created_at;

-- 2) 同一订单多次尝试 / 重复成功（重复收款或换渠道历史）
SELECT a.order_id,
       o.order_number,
       count(*) AS attempts,
       count(*) FILTER (WHERE a.status = 'SUCCESS') AS success_rows,
       string_agg(a.payment_id || ':' || a.status, ', ' ORDER BY a.created_at) AS history
FROM payment_attempts a
LEFT JOIN orders o ON o.id::text = a.order_id
GROUP BY a.order_id, o.order_number
HAVING count(*) > 1
ORDER BY attempts DESC;

-- 3) 尝试金额/币种与订单不符（发货前必须拦下的那一类）
SELECT o.order_number, o.total_amount, o.currency,
       a.payment_id, a.amount AS attempt_amount, a.currency AS attempt_currency, a.status
FROM payment_attempts a
JOIN orders o ON o.id::text = a.order_id
WHERE abs(a.amount - o.total_amount) > 0.01
   OR a.currency IS DISTINCT FROM o.currency;

-- 4) 孤儿尝试：order_id 找不到订单
SELECT a.order_id, a.payment_id, a.status, a.created_at
FROM payment_attempts a
LEFT JOIN orders o ON o.id::text = a.order_id
WHERE o.id IS NULL;

-- 5) 退款超过收款（按订单汇总，跨币种不混合）
SELECT l.order_id, o.order_number, l.currency,
       sum(l.amount) FILTER (WHERE l.entry_type = 'CHARGE') AS charges,
       sum(l.amount) FILTER (WHERE l.entry_type = 'REFUND') AS refunds
FROM payment_ledger_entries l
LEFT JOIN orders o ON o.id::text = l.order_id
GROUP BY l.order_id, o.order_number, l.currency
HAVING COALESCE(sum(l.amount) FILTER (WHERE l.entry_type = 'REFUND'), 0)
     > COALESCE(sum(l.amount) FILTER (WHERE l.entry_type = 'CHARGE'), 0);

-- 6) 已退款订单仍有 ACTIVE 授权（撤权失败）
SELECT o.order_number, o.payment_status, l.license_key, l.status, l.expires_at
FROM orders o
JOIN licenses l ON l.order_id = o.id
WHERE o.payment_status IN ('REFUNDED', 'PARTIALLY_REFUNDED')
  AND l.status = 'ACTIVE';

-- 7) 订阅周期入账：每个已绑定订阅是否每期都有 CHARGE（漏记账）
SELECT s.provider_subscription_id, s.status, s.current_period_end,
       (SELECT count(*) FROM payment_ledger_entries l
         WHERE l.idempotency_key LIKE 'subcharge:' || s.provider_subscription_id || ':%') AS period_charges
FROM subscriptions s
ORDER BY s.created_at DESC;
