-- ============================================================================
-- Flyway 迁移 V27 —— 支付渠道/功能运行时开关
--
-- Web 管理端可启停渠道与功能；数据库值覆盖环境变量默认值。
-- 渠道停用只影响「新建支付」，已支付订单的 Webhook/退款仍按配置继续处理。
-- ============================================================================

CREATE TABLE IF NOT EXISTS payment_channel_settings (
    channel     VARCHAR(32) PRIMARY KEY,
    enabled     BOOLEAN NOT NULL,
    updated_by  VARCHAR(64),
    updated_at  TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment_feature_settings (
    feature_key VARCHAR(64) PRIMARY KEY,
    enabled     BOOLEAN NOT NULL,
    updated_by  VARCHAR(64),
    updated_at  TIMESTAMP
);

COMMENT ON TABLE payment_channel_settings IS 'Web 端支付渠道启停覆盖：有行则以数据库为准，无行沿用环境变量/自动判定';
COMMENT ON TABLE payment_feature_settings IS 'Web 端支付功能开关覆盖：有行则以数据库为准，无行沿用代码默认值';
