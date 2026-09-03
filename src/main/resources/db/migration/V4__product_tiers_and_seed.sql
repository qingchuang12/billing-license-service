-- ============================================================================
-- Flyway 迁移 V4（B16）—— 三档产品权益模型 + 种子数据
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- B16：三档产品权益模型 + 种子数据
-- 新增档位(tier)与权益(features)字段，并写入四种组合（覆盖三档付费模式）

ALTER TABLE products ADD COLUMN tier VARCHAR(50) NOT NULL DEFAULT 'PRO';
ALTER TABLE products ADD COLUMN features TEXT;

COMMENT ON COLUMN products.tier IS '产品档位/等级（PRO/PRO_PLUS），与 billing_cycle 组合表达买断/订阅与基础/高级';
COMMENT ON COLUMN products.features IS '权益特性清单（JSON 数组字符串，如 ["OFFLINE","MULTI_DEVICE","PRIORITY_SUPPORT"]）';

-- 种子数据（products 表初始为空，直接插入四档；双币种定价见 B19，此处以 USD 为基准）
INSERT INTO products (id, sku, name, description, price, currency, billing_cycle, license_duration_days, tier, features, active, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'pro-buyout',           'Pro 买断',        'Pro 一次性买断授权',        99.0000,  'USD', 'LIFETIME', 3650, 'PRO',      '["OFFLINE","MULTI_DEVICE","EMAIL_SUPPORT"]',                  true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (gen_random_uuid(), 'pro-plus-buyout',      'Pro Plus 高级版',  'Pro Plus 一次性买断授权',  199.0000, 'USD', 'LIFETIME', 3650, 'PRO_PLUS', '["OFFLINE","MULTI_DEVICE","PRIORITY_SUPPORT","API_ACCESS"]', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (gen_random_uuid(), 'pro-subscription',     '订阅 Pro',         'Pro 按月订阅',             19.0000,  'USD', 'MONTHLY',   31,   'PRO',      '["OFFLINE","MULTI_DEVICE","EMAIL_SUPPORT"]',                  true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (gen_random_uuid(), 'pro-plus-subscription','订阅 Pro Plus',    'Pro Plus 按月订阅',        39.0000,  'USD', 'MONTHLY',   31,   'PRO_PLUS', '["OFFLINE","MULTI_DEVICE","PRIORITY_SUPPORT","API_ACCESS"]', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
