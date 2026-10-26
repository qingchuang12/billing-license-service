-- ============================================================================
-- Flyway 迁移 V15 —— 只增不减三张流水/审计表的保留期清理索引
--
-- 背景：`audit_logs` / `payment_events` / `license_events` 三张表只增不减
--       （审计日志无任何查询端点、支付事件与领证事件也没有清理任务），
--       长期膨胀直接恶化备份窗口与按条件查询的耗时。保留期任务（#13）
--       按 `created_at < now() - N 天` 分批删除，缺索引时每批都是全表扫描。
--
-- 实现：给三张表的 `created_at` 各建一条 B-tree 索引（IF NOT EXISTS 幂等）。
--       `audit_logs` 另补 `action` 前缀的复合索引——事后追溯的典型形态是
--       「某个动作在一段时间内的全部记录」（如查谁退过款），单列时间索引帮不上。
--       纯增量：不动数据、不改既有查询语义、不删任何历史行。
--
-- ⚠️ 清理只删「超出保留期」的行，且 `payment_events` 的保留期（默认 365 天）
--    远大于任何渠道的 webhook 重投窗口（Stripe 重试约 3 天、Paddle 同量级），
--    不会削弱 webhook 幂等去重；即便极端情况下被重投，`fulfillOrder` 对已支付
--    订单幂等，不会二次发货。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created_at ON audit_logs (action, created_at);
CREATE INDEX IF NOT EXISTS idx_payment_events_created_at ON payment_events (created_at);
CREATE INDEX IF NOT EXISTS idx_license_events_created_at ON license_events (created_at);

COMMENT ON INDEX idx_audit_logs_created_at IS '保留期清理与按时间倒序翻页（#13）';
COMMENT ON INDEX idx_audit_logs_action_created_at IS '按动作 + 时间区间追溯管理操作（#13）';
