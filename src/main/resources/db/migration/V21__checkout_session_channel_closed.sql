-- ============================================================================
-- Flyway 迁移 V21 —— 收银台后台对账（#50）
--
-- 背景：收银台会话本地过期后，用户手里的渠道页/二维码仍可能完成支付；
--       后台对账任务需要区分「渠道已确认关单」与「关单未确认/不支持」，
--       并在清理过期会话时保留仍有支付尝试的「未对清证据」。
--
-- 实现：新增 channel_closed_at（渠道确认关单时间）；补一条
--       (status, last_compensated_at) 复合索引供后台扫描取候选。
--       纯增量：不改既有列与数据。
-- ============================================================================

ALTER TABLE checkout_sessions ADD COLUMN IF NOT EXISTS channel_closed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_checkout_sessions_status_last_comp
    ON checkout_sessions (status, last_compensated_at);

COMMENT ON COLUMN checkout_sessions.channel_closed_at IS '渠道确认关单时间；非空表示该交易已不可能再收款（#50）';
COMMENT ON INDEX idx_checkout_sessions_status_last_comp IS '后台对账扫描：按状态 + 上次对账时间取候选（#50）';
