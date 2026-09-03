-- ============================================================================
-- Flyway 迁移 V7 —— Webhook 幂等与轮询冷却加固
--   R1：payment_events(provider, event_id) 唯一约束，使并发重复投递由 DB 原子去重
--   R3：checkout_sessions.last_compensated_at 列，支撑 getStatus 轮询冷却窗口
-- 说明：R5（getStatus 并发重复签发）的单实例防御由应用内按订单串行锁承担；
--       多实例部署建议额外部署分布式锁/DB  advisory lock（见修复说明）。
-- 注意：上线前 payment_events 应为空（无历史重复行），否则 ADD CONSTRAINT 会因
--       重复 (provider, event_id) 失败，需先清理重复事件再执行本迁移。
-- ============================================================================

-- R1：将 (provider, event_id) 提升为唯一约束（原 idx_payment_events_event 为非唯一索引）
DROP INDEX IF EXISTS idx_payment_events_event;
ALTER TABLE payment_events
    ADD CONSTRAINT uk_payment_events_provider_event UNIQUE (provider, event_id);

-- R3：checkout_sessions 增加上次主动补偿时间列，供 getStatus 轮询冷却判断
ALTER TABLE checkout_sessions
    ADD COLUMN IF NOT EXISTS last_compensated_at TIMESTAMP;
