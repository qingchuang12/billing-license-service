-- ============================================================================
-- Flyway 迁移 V23 —— Repository 查询键的索引补齐
--
-- 背景：V12 只覆盖了四条最热的外键/状态列，其余查询键仍走顺序扫描。本迁移按
--       「迁移里的索引必须覆盖 Repository 的查询键」逐条比对后补齐；数据量增长后
--       这些查询会从毫秒级退化为全表扫描，其中 ①③④ 位于发货、退款与渠道对账链路。
--
-- 逐条依据：
--   ① checkout_sessions(order_number) —— CheckoutSessionRepository#findByOrderNumber /
--      findByOrderNumberIn（发货定位会话、收银台 locale、轮询与补偿）；同时是
--      findExpiredUnlinkedIds 子查询里 PaymentAttempt.orderNumber 的对照列。
--   ② redeem_codes(order_id) —— RedeemCodeRepository#findByOrderId / findByOrderIdForUpdate
--      （退款撤码按订单号取码并加写锁）。
--   ③ subscriptions(license_id) —— SubscriptionRepository#findByLicenseId
--      （续期/作废按 License 反查订阅）。
--   ④ payment_attempts(order_number) —— findExpiredUnlinkedIds 的 NOT EXISTS 子查询
--      （判断会话是否已触达渠道；无索引时每次清理都扫全表）。
--   ⑤ payment_attempts(transaction_id) —— PaymentAttemptRepository#findByTransactionIdOrderByCreatedAtDesc
--      （渠道交易号反查尝试，对账与客诉定位）。
--   ⑥ payment_ledger_entries(channel_txn_id) —— PaymentLedgerRepository#findByChannelTxnIdOrderByOccurredAtDesc
--      （渠道账单号反查资金分录）。
--   ⑦⑧ verification_codes(expires_at) / (consumed_at) —— VerificationCodeRepository#purgeBefore
--      的 `expires_at < :cutoff OR consumed_at < :cutoff`；两个独立 B-tree 让规划器走 BitmapOr。
--   ⑨ orders(customer_id, created_at DESC) —— OrderRepository#findByCustomerIdOrderByCreatedAtDesc
--      （账号页订单列表：原单列索引仍需排序）；前缀覆盖 countByCustomerId。
--   ⑩ licenses(machine_code, status) —— LicenseRepository#findClaimableByBoundMachine
--      （客户端每 60s 轮询一次待领证，原单列索引仍需回表过滤状态）；前缀覆盖 machine_code 等值查询。
--
-- 实现：⑨⑩ 以复合索引**替换**同前缀单列索引（前缀查询能力不减，省一份写放大）；
--      其余为纯增量。全部 IF NOT EXISTS / IF EXISTS 保证幂等，不动数据、不改查询语义。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_checkout_sessions_order_number ON checkout_sessions (order_number);
CREATE INDEX IF NOT EXISTS idx_redeem_codes_order ON redeem_codes (order_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_license ON subscriptions (license_id);
CREATE INDEX IF NOT EXISTS idx_payment_attempts_order_number ON payment_attempts (order_number);
CREATE INDEX IF NOT EXISTS idx_payment_attempts_transaction ON payment_attempts (transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_channel_txn ON payment_ledger_entries (channel_txn_id);
CREATE INDEX IF NOT EXISTS idx_vcode_expires ON verification_codes (expires_at);
CREATE INDEX IF NOT EXISTS idx_vcode_consumed ON verification_codes (consumed_at);

CREATE INDEX IF NOT EXISTS idx_orders_customer_created ON orders (customer_id, created_at DESC);
DROP INDEX IF EXISTS idx_orders_customer;

CREATE INDEX IF NOT EXISTS idx_licenses_machine_status ON licenses (machine_code, status);
DROP INDEX IF EXISTS idx_licenses_machine_code;

COMMENT ON INDEX idx_checkout_sessions_order_number IS '发货/轮询按订单号定位收银台会话';
COMMENT ON INDEX idx_redeem_codes_order IS '退款撤码按订单取码（含悲观写锁路径）';
COMMENT ON INDEX idx_subscriptions_license IS '续期/作废按 License 反查订阅';
COMMENT ON INDEX idx_payment_attempts_order_number IS '清理任务判断会话是否已触达渠道';
COMMENT ON INDEX idx_payment_attempts_transaction IS '渠道交易号反查支付尝试';
COMMENT ON INDEX idx_ledger_channel_txn IS '渠道账单号反查资金分录';
COMMENT ON INDEX idx_vcode_expires IS '验证码按过期时间清理';
COMMENT ON INDEX idx_vcode_consumed IS '验证码按消费时间清理';
COMMENT ON INDEX idx_orders_customer_created IS '账号页订单列表：客户 + 下单时间倒序';
COMMENT ON INDEX idx_licenses_machine_status IS '待领证轮询：机器码 + 状态';
