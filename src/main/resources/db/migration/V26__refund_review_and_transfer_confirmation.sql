-- ============================================================================
-- Flyway 迁移 V26 —— 退款人工审核 + 部分退款转账确认
--
-- Creem 官方 API 只支持全额退款，且没有向客户转账的 API；业务要求：
--   - Creem 全额退款：管理员审核通过后自动调用 Creem /v1/refunds；
--   - Creem 部分退款：管理员审核通过后进入转账待确认，运营线下/网银转账后登记流水，
--     系统据此结算权益与账本（refund_requests.status=MANUAL_REQUIRED）。
-- ============================================================================

ALTER TABLE refund_requests ADD COLUMN IF NOT EXISTS transfer_channel VARCHAR(32);
ALTER TABLE refund_requests ADD COLUMN IF NOT EXISTS transfer_reference VARCHAR(128);
ALTER TABLE refund_requests ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(64);
ALTER TABLE refund_requests ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
ALTER TABLE refund_requests ADD COLUMN IF NOT EXISTS review_note VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_refund_requests_review
    ON refund_requests (status, created_at);

COMMENT ON COLUMN refund_requests.transfer_channel IS '人工转账渠道：BANK / ALIPAY / OTHER';
COMMENT ON COLUMN refund_requests.transfer_reference IS '人工转账流水号/回单号';
COMMENT ON COLUMN refund_requests.reviewed_by IS '审核管理员 userId';
COMMENT ON COLUMN refund_requests.reviewed_at IS '审核时间';
COMMENT ON COLUMN refund_requests.review_note IS '审核备注/拒绝原因';
