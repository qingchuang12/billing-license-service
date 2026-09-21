-- ============================================================================
-- Flyway 迁移 V4 —— 账务统计时间范围查询建索引：orders / payments 的 created_at
--
-- 背景：平台账务管理（/api/admin/accounting/**，见 AccountingService）全部 6 个端点
--       均以 findByCreatedAtBetween 按时间范围拉取 orders / payments 后再聚合。
--       V1 基线只给这两表的 created_at 写了 COMMENT，未建索引（licenses、subscriptions
--       等表都已建），数据量上来后按时间范围查询会退化为全表扫描。
--
-- 实现：为两表 created_at 各建 B-tree 索引。IF NOT EXISTS 保证幂等，不影响已有数据。
--       统计查询天然按时间倒序返回，升序索引即可支撑范围过滤与排序。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders (created_at);
CREATE INDEX IF NOT EXISTS idx_payments_created_at ON payments (created_at);
