-- V1 baseline schema —— 合并原 V1..V12（2026-09-20，上线前收敛；已在空库验证等价：15 表/184 列/产品种子 4 条与逐版迁移一致）

-- ============================================================================
-- Flyway 迁移 V1 —— 初始库表结构（products / orders / licenses / redeem_codes / payment_events 等核心表）
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- Initial schema for Billing & License Service

CREATE TABLE IF NOT EXISTS products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    billing_cycle VARCHAR(50) NOT NULL DEFAULT 'ONE_TIME',
    license_duration_days INTEGER NOT NULL DEFAULT 365,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

COMMENT ON TABLE products IS '产品/商品信息表';
COMMENT ON COLUMN products.id IS '产品唯一标识';
COMMENT ON COLUMN products.sku IS '产品SKU编码（库存单位），业务唯一';
COMMENT ON COLUMN products.name IS '产品名称';
COMMENT ON COLUMN products.description IS '产品描述';
COMMENT ON COLUMN products.price IS '产品价格';
COMMENT ON COLUMN products.currency IS '货币类型（ISO 4217，默认USD）';
COMMENT ON COLUMN products.billing_cycle IS '计费周期（ONE_TIME/MONTHLY/YEARLY等）';
COMMENT ON COLUMN products.license_duration_days IS '授权有效天数';
COMMENT ON COLUMN products.active IS '是否启用销售';
COMMENT ON COLUMN products.created_at IS '创建时间';
COMMENT ON COLUMN products.updated_at IS '更新时间';

CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number VARCHAR(100) NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    total_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    payment_status VARCHAR(50) NOT NULL DEFAULT 'UNPAID',
    payment_intent_id VARCHAR(255),
    payment_provider VARCHAR(50),
    metadata TEXT,
    description TEXT,
    machine_code VARCHAR(255),                                            -- 客户端机器码，用于绑定设备
    title VARCHAR(255),                                                   -- 订单标题
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    paid_at TIMESTAMP
);

COMMENT ON TABLE orders IS '订单表';
COMMENT ON COLUMN orders.id IS '订单唯一标识';
COMMENT ON COLUMN orders.order_number IS '订单号（业务唯一）';
COMMENT ON COLUMN orders.customer_id IS '客户标识';
COMMENT ON COLUMN orders.total_amount IS '订单总金额';
COMMENT ON COLUMN orders.currency IS '货币类型（默认USD）';
COMMENT ON COLUMN orders.status IS '订单状态（PENDING/CONFIRMED/PAID/COMPLETED/CANCELLED/REFUNDED等）';
COMMENT ON COLUMN orders.payment_status IS '支付状态（UNPAID/PAID/PARTIALLY_REFUNDED/REFUNDED/FAILED）';
COMMENT ON COLUMN orders.payment_intent_id IS '支付预创建意图ID（支付网关）';
COMMENT ON COLUMN orders.payment_provider IS '支付服务商（如STRIPE/ALIPAY）';
COMMENT ON COLUMN orders.metadata IS '扩展元数据（JSON）';
COMMENT ON COLUMN orders.description IS '订单描述';
COMMENT ON COLUMN orders.machine_code IS '客户端机器码，用于绑定设备';
COMMENT ON COLUMN orders.title IS '订单标题';
COMMENT ON COLUMN orders.created_at IS '创建时间';
COMMENT ON COLUMN orders.updated_at IS '更新时间';
COMMENT ON COLUMN orders.paid_at IS '支付完成时间';

CREATE TABLE IF NOT EXISTS order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price DECIMAL(19,4) NOT NULL,
    total_price DECIMAL(19,4) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE order_items IS '订单明细表';
COMMENT ON COLUMN order_items.id IS '订单项唯一标识';
COMMENT ON COLUMN order_items.order_id IS '关联订单ID';
COMMENT ON COLUMN order_items.product_id IS '关联产品ID';
COMMENT ON COLUMN order_items.quantity IS '购买数量';
COMMENT ON COLUMN order_items.unit_price IS '单价';
COMMENT ON COLUMN order_items.total_price IS '小计金额';
COMMENT ON COLUMN order_items.created_at IS '创建时间';

CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id);

CREATE TABLE IF NOT EXISTS licenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_key VARCHAR(100) NOT NULL UNIQUE,
    customer_id UUID NOT NULL,
    order_id UUID REFERENCES orders(id),
    product_id UUID NOT NULL REFERENCES products(id),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    activated_at TIMESTAMP,
    last_verified_at TIMESTAMP,
    signed_token TEXT,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

COMMENT ON TABLE licenses IS '授权/许可证表';
COMMENT ON COLUMN licenses.id IS '授权唯一标识';
COMMENT ON COLUMN licenses.license_key IS '授权密钥（唯一）';
COMMENT ON COLUMN licenses.customer_id IS '客户标识';
COMMENT ON COLUMN licenses.order_id IS '关联订单ID';
COMMENT ON COLUMN licenses.product_id IS '关联产品ID';
COMMENT ON COLUMN licenses.status IS '授权状态（ACTIVE/EXPIRED/REVOKED等）';
COMMENT ON COLUMN licenses.issued_at IS '签发时间';
COMMENT ON COLUMN licenses.expires_at IS '过期时间';
COMMENT ON COLUMN licenses.activated_at IS '激活时间';
COMMENT ON COLUMN licenses.last_verified_at IS '最近校验时间';
COMMENT ON COLUMN licenses.signed_token IS '签名令牌';
COMMENT ON COLUMN licenses.metadata IS '扩展元数据（JSON）';
COMMENT ON COLUMN licenses.created_at IS '创建时间';
COMMENT ON COLUMN licenses.updated_at IS '更新时间';

CREATE INDEX idx_licenses_customer ON licenses(customer_id);
CREATE INDEX idx_licenses_key ON licenses(license_key);
CREATE INDEX idx_licenses_status ON licenses(status);

CREATE TABLE IF NOT EXISTS redeem_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL UNIQUE,
    product_id UUID NOT NULL REFERENCES products(id),
    status VARCHAR(50) NOT NULL DEFAULT 'UNUSED',
    used_by UUID,
    used_at TIMESTAMP,
    expires_at TIMESTAMP,
    max_uses INTEGER NOT NULL DEFAULT 1,
    current_uses INTEGER NOT NULL DEFAULT 0,
    order_id VARCHAR(64),
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

COMMENT ON TABLE redeem_codes IS '兑换码表';
COMMENT ON COLUMN redeem_codes.id IS '兑换码唯一标识';
COMMENT ON COLUMN redeem_codes.code IS '兑换码（唯一）';
COMMENT ON COLUMN redeem_codes.product_id IS '关联产品ID';
COMMENT ON COLUMN redeem_codes.status IS '兑换码状态（UNUSED/USED/EXPIRED/REVOKED）';
COMMENT ON COLUMN redeem_codes.used_by IS '使用者客户标识';
COMMENT ON COLUMN redeem_codes.used_at IS '使用时间';
COMMENT ON COLUMN redeem_codes.expires_at IS '过期时间';
COMMENT ON COLUMN redeem_codes.max_uses IS '最大可用次数';
COMMENT ON COLUMN redeem_codes.current_uses IS '已使用次数';
COMMENT ON COLUMN redeem_codes.order_id IS '关联订单ID（字符串）';
COMMENT ON COLUMN redeem_codes.metadata IS '扩展元数据（JSON）';
COMMENT ON COLUMN redeem_codes.created_at IS '创建时间';
COMMENT ON COLUMN redeem_codes.updated_at IS '更新时间';

CREATE INDEX idx_redeem_codes_code ON redeem_codes(code);
CREATE INDEX idx_redeem_codes_status ON redeem_codes(status);

CREATE TABLE IF NOT EXISTS payment_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id VARCHAR(100) NOT NULL UNIQUE,
    order_id UUID NOT NULL REFERENCES orders(id),
    provider VARCHAR(50) NOT NULL,
    provider_transaction_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    error_message TEXT,
    metadata TEXT,
    processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE payment_transactions IS '支付交易表';
COMMENT ON COLUMN payment_transactions.id IS '支付交易唯一标识';
COMMENT ON COLUMN payment_transactions.transaction_id IS '交易流水号（唯一）';
COMMENT ON COLUMN payment_transactions.order_id IS '关联订单ID';
COMMENT ON COLUMN payment_transactions.provider IS '支付服务商';
COMMENT ON COLUMN payment_transactions.provider_transaction_id IS '支付服务商侧交易ID';
COMMENT ON COLUMN payment_transactions.status IS '交易状态（PENDING/SUCCESS/FAILED/REFUNDED/CANCELLED）';
COMMENT ON COLUMN payment_transactions.amount IS '交易金额';
COMMENT ON COLUMN payment_transactions.currency IS '货币类型（默认USD）';
COMMENT ON COLUMN payment_transactions.error_message IS '失败错误信息';
COMMENT ON COLUMN payment_transactions.metadata IS '扩展元数据（JSON）';
COMMENT ON COLUMN payment_transactions.processed_at IS '处理完成时间';
COMMENT ON COLUMN payment_transactions.created_at IS '创建时间';

CREATE INDEX idx_payment_transactions_order ON payment_transactions(order_id);
CREATE INDEX idx_payment_transactions_provider ON payment_transactions(provider_transaction_id);

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,                                                     -- 支付记录主键（自增）
    order_id VARCHAR(64) NOT NULL,                                                -- 关联订单ID（字符串）
    payment_id VARCHAR(128) NOT NULL UNIQUE,                                       -- 支付记录ID（唯一）
    transaction_id VARCHAR(128),                                                  -- 交易流水号
    amount DECIMAL(10,2) NOT NULL,                                                -- 支付金额
    currency VARCHAR(3),                                                          -- 货币类型
    method VARCHAR(32),                                                           -- 支付方式
    status VARCHAR(32) NOT NULL,                                                  -- 支付状态
    channel VARCHAR(32),                                                          -- 支付渠道
    metadata TEXT,                                                                -- 扩展元数据（JSON）
    error_message TEXT,                                                           -- 失败错误信息
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,                      -- 创建时间
    updated_at TIMESTAMP,                                                         -- 更新时间
    paid_at TIMESTAMP                                                            -- 支付完成时间
);

COMMENT ON TABLE payments IS '支付记录表';
COMMENT ON COLUMN payments.id IS '支付记录主键（自增）';
COMMENT ON COLUMN payments.order_id IS '关联订单ID（字符串）';
COMMENT ON COLUMN payments.payment_id IS '支付记录ID（唯一）';
COMMENT ON COLUMN payments.transaction_id IS '交易流水号';
COMMENT ON COLUMN payments.amount IS '支付金额';
COMMENT ON COLUMN payments.currency IS '货币类型';
COMMENT ON COLUMN payments.method IS '支付方式';
COMMENT ON COLUMN payments.status IS '支付状态';
COMMENT ON COLUMN payments.channel IS '支付渠道';
COMMENT ON COLUMN payments.metadata IS '扩展元数据（JSON）';
COMMENT ON COLUMN payments.error_message IS '失败错误信息';
COMMENT ON COLUMN payments.created_at IS '创建时间';
COMMENT ON COLUMN payments.updated_at IS '更新时间';
COMMENT ON COLUMN payments.paid_at IS '支付完成时间';
-- ============================================================================
-- Flyway 迁移 V2 —— 收银台会话 / 事件表 / orders.email
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- V2 migration: checkout sessions, event tables, order email column
-- Applied on top of V1 (ddl-auto=validate requires Flyway-managed schema changes)

-- 订单表增加 email 字段（用于发货通知）
ALTER TABLE orders ADD COLUMN IF NOT EXISTS email VARCHAR(255);

-- 统一收银台会话表
CREATE TABLE IF NOT EXISTS checkout_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    checkout_id VARCHAR(100) NOT NULL UNIQUE,
    order_id UUID REFERENCES orders(id) ON DELETE CASCADE,
    order_number VARCHAR(100),
    provider VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    amount DECIMAL(19,4),
    locale VARCHAR(16),
    country VARCHAR(8),
    machine_id VARCHAR(255),
    email VARCHAR(255),
    return_url TEXT,
    cancel_url TEXT,
    provider_session_id VARCHAR(255),
    pay_url TEXT,
    expires_at TIMESTAMP,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

COMMENT ON TABLE checkout_sessions IS '统一收银台会话表';
COMMENT ON COLUMN checkout_sessions.checkout_id IS '收银台会话ID（业务唯一）';
COMMENT ON COLUMN checkout_sessions.order_id IS '关联订单ID';
COMMENT ON COLUMN checkout_sessions.provider IS '支付服务商';
COMMENT ON COLUMN checkout_sessions.status IS '会话状态（CREATED/PENDING/PAID/FAILED/EXPIRED/CANCELED）';
COMMENT ON COLUMN checkout_sessions.currency IS '货币类型';
COMMENT ON COLUMN checkout_sessions.locale IS '语言区域';
COMMENT ON COLUMN checkout_sessions.machine_id IS '客户端机器码（用于直接签发绑定设备License）';
COMMENT ON COLUMN checkout_sessions.email IS '客户邮箱（用于发货通知）';

CREATE INDEX IF NOT EXISTS idx_checkout_sessions_order ON checkout_sessions(order_id);
CREATE INDEX IF NOT EXISTS idx_checkout_sessions_status ON checkout_sessions(status);

-- License 事件表（激活/兑换/重发/作废/校验失败等审计）
CREATE TABLE IF NOT EXISTS license_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    license_id UUID,
    license_key VARCHAR(100),
    event_type VARCHAR(50) NOT NULL,
    machine_id VARCHAR(255),
    ip VARCHAR(64),
    user_agent TEXT,
    app_version VARCHAR(64),
    detail TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE license_events IS 'License 事件/审计表';
COMMENT ON COLUMN license_events.license_id IS '关联License ID';
COMMENT ON COLUMN license_events.license_key IS 'License 密钥';
COMMENT ON COLUMN license_events.event_type IS '事件类型（ISSUED/REDEEMED/REISSUED/REVOKED/VERIFY_FAILED）';
COMMENT ON COLUMN license_events.machine_id IS '机器码';
COMMENT ON COLUMN license_events.ip IS '请求IP';

CREATE INDEX IF NOT EXISTS idx_license_events_license ON license_events(license_id);
CREATE INDEX IF NOT EXISTS idx_license_events_key ON license_events(license_key);

-- 支付事件表（Webhook 幂等/对账/审计）
CREATE TABLE IF NOT EXISTS payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(50) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(64),
    order_id VARCHAR(64),
    provider_payment_id VARCHAR(255),
    amount DECIMAL(19,4),
    currency VARCHAR(3),
    signature_valid BOOLEAN,
    processed BOOLEAN NOT NULL DEFAULT false,
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE payment_events IS '支付事件表（Webhook 幂等/对账/审计）';
COMMENT ON COLUMN payment_events.provider IS '支付服务商';
COMMENT ON COLUMN payment_events.event_id IS '服务商侧事件ID（幂等去重）';
COMMENT ON COLUMN payment_events.order_id IS '关联订单号';
COMMENT ON COLUMN payment_events.signature_valid IS '签名验证是否通过';
COMMENT ON COLUMN payment_events.processed IS '是否已处理';

CREATE INDEX IF NOT EXISTS idx_payment_events_event ON payment_events(provider, event_id);
CREATE INDEX IF NOT EXISTS idx_payment_events_order ON payment_events(order_id);
-- ============================================================================
-- Flyway 迁移 V3 —— licenses 换机重发/作废审计字段
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- V3 migration: licenses 表新增换机重发/作废审计字段
-- ddl-auto=validate，所有 schema 变更必须通过 Flyway

ALTER TABLE licenses ADD COLUMN IF NOT EXISTS machine_code VARCHAR(255);
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS reissued_from UUID;
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;

COMMENT ON COLUMN licenses.machine_code IS '绑定的设备机器码（换机重发时更新）';
COMMENT ON COLUMN licenses.reissued_from IS '换机重发时指向原 License ID';
COMMENT ON COLUMN licenses.revoked_at IS '作废时间';
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
-- ============================================================================
-- Flyway 迁移 V5（B18）—— 订阅制生命周期表（托管 Paddle/Stripe 对账）
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- B18：订阅制（Q1 托管 Paddle/Stripe Billing）
-- 订阅生命周期由渠道托管，本表仅做对账与 License 联动（首充绑定 / 续费延长 / 取消作废）

CREATE TABLE subscriptions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                    UUID,
    customer_id                 UUID NOT NULL,
    product_id                  UUID,
    provider                    VARCHAR(50)  NOT NULL,
    provider_subscription_id    VARCHAR(255) NOT NULL,
    status                      VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    current_period_start        TIMESTAMP,
    current_period_end          TIMESTAMP,
    cancel_at_period_end        BOOLEAN      NOT NULL DEFAULT FALSE,
    license_id                  UUID,
    created_at                  TIMESTAMP    NOT NULL,
    updated_at                  TIMESTAMP,
    CONSTRAINT uq_subscriptions_provider_sub UNIQUE (provider, provider_subscription_id)
);

COMMENT ON TABLE  subscriptions IS '订阅记录：关联渠道订阅、订单与已签发 License，驱动续期/作废';
COMMENT ON COLUMN subscriptions.provider_subscription_id IS '渠道侧订阅 ID（Paddle subscription_id / Stripe subscription id）';
COMMENT ON COLUMN subscriptions.license_id IS '关联已签发的 License，续期延长其 expires_at，取消时置 EXPIRED';

CREATE INDEX idx_subscriptions_order_id ON subscriptions (order_id);
CREATE INDEX idx_subscriptions_customer_id ON subscriptions (customer_id);
-- ============================================================================
-- Flyway 迁移 V6（B19）—— 双币种定价（price_cny / price_usd + 回填）
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- B19：双币种定价
-- 原 products 仅单一 price + currency（USD），导致国内用户按美元金额付人民币（资损/客诉）。
-- 改为每个 SKU 同时维护 CNY 与 USD 两档价格，下单按区域取用。

ALTER TABLE products ADD COLUMN price_cny NUMERIC(12, 2);
ALTER TABLE products ADD COLUMN price_usd NUMERIC(12, 2);

COMMENT ON COLUMN products.price_cny IS '人民币定价（国内下单使用）';
COMMENT ON COLUMN products.price_usd IS '美元定价（国际下单使用，沿用原 price 语义）';

-- 回填：USD 沿用原 price；CNY 按示例汇率 7.2 换算（示例值，部署时按实际汇率调整）
UPDATE products SET price_usd = price WHERE price_usd IS NULL;
UPDATE products SET price_cny = ROUND(price * 7.2, 2) WHERE price_cny IS NULL AND price IS NOT NULL;
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
-- 审计日志表（方案 B：@Audit 注解 + AOP 切面 + 异步独立事务落库）
-- 幂等：重复执行不报错。
CREATE TABLE IF NOT EXISTS audit_logs (
    id          UUID PRIMARY KEY,
    actor       VARCHAR(64),
    action      VARCHAR(64) NOT NULL,
    target      VARCHAR(255),
    success     BOOLEAN NOT NULL,
    detail      TEXT,
    ip          VARCHAR(64),
    user_agent  TEXT,
    created_at  TIMESTAMP NOT NULL
);
-- ============================================================================
-- Flyway 迁移 V9 —— License 死字段清理与状态语义收敛（D3，2026-09-14）
--   1. licenses.activated_at 全库无写入点（本项目为离线 License，无服务端激活上报），
--      彻底删除；「最近校验时间」由 last_verified_at 承担（verifyLicense 写入）。
--   2. status 语义收敛为 ACTIVE / EXPIRED / REVOKED / REISSUED：
--        · 换机重发的旧 License 记 REISSUED（此前被误写为 REVOKED，与退款吊销混淆）；
--        · 删除从未被赋值过的 SUSPENDED / PENDING_ACTIVATION（列类型为 varchar，仅更新注释）。
-- 注意：本文件在首次部署后禁止修改（Flyway 校验和固定）；后续变更请新建 V10。
-- ============================================================================

ALTER TABLE licenses DROP COLUMN IF EXISTS activated_at;

COMMENT ON COLUMN licenses.status IS '授权状态：ACTIVE/EXPIRED/REVOKED/REISSUED';
COMMENT ON COLUMN licenses.last_verified_at IS '最近校验时间（verifyLicense 时更新）';
-- 账号体系（plan v2.10 / A1）：终端用户表 + 邮箱验证码表
-- 幂等：重复执行不报错。
-- 说明：users.id 直接承载 orders.customer_id（决策 1）——登录用户下单即以 userId 作为 customerId，
--       匿名订单仍为随机 UUID，不对应任何 User，故此处不建外键，避免匿名订单插入失败。

CREATE TABLE IF NOT EXISTS users (
    id                 UUID PRIMARY KEY,
    email              VARCHAR(255) NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified     BOOLEAN      NOT NULL DEFAULT false,
    token_version      INTEGER      NOT NULL DEFAULT 0,
    failed_login_count INTEGER      NOT NULL DEFAULT 0,
    locked_until       TIMESTAMP,
    last_login_at      TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email ON users (email);
CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);

-- 邮箱验证码：仅存哈希（不存明文），一次性消费
CREATE TABLE IF NOT EXISTS verification_codes (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    purpose       VARCHAR(30)  NOT NULL,
    code_hash     VARCHAR(100) NOT NULL,
    expires_at    TIMESTAMP    NOT NULL,
    consumed_at   TIMESTAMP,
    attempt_count INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vcode_lookup ON verification_codes (email, purpose, created_at DESC);
-- ============================================================================
-- Flyway 迁移 V11（A3）—— 产品更新门槛字段
-- 按文件名版本号顺序自动执行；首次部署后禁止修改本文件（Flyway 校验和固定）
-- ============================================================================
-- A3：买断制「永久使用当前大版本 + N 天小版本更新」的更新权益门槛，
-- 签发时由 LicenseIssuer 写入 payload 的 update_until / max_major_version。
-- 两列均可空：NULL 或 <= 0 均表示「不限制」，签发时对应键不写入 payload。
-- 注意：0 不作为「不含更新」的哨兵值使用（写入即被客户端按上限值解读，口径会与 update_until 打架）。

ALTER TABLE products ADD COLUMN update_until_days INTEGER;
ALTER TABLE products ADD COLUMN max_major_version INTEGER;

COMMENT ON COLUMN products.update_until_days IS '更新权益截止天数（自签发日起算）；NULL 或 <=0 = 不限制（签发时对应键不写入 payload），常见 365/730；不可用 0 表达「不含更新」';
COMMENT ON COLUMN products.max_major_version IS '允许使用的大版本上限；NULL 或 <=0 = 不限制（签发时对应键不写入 payload）';

-- 种子数据赋值（沿用 V4 四档语义：买断=LIFETIME 长期授权，订阅=MONTHLY 续费期内可用）
-- 买断：含 2 年（730 天）小版本更新，限当前大版本 1.x
UPDATE products SET update_until_days = 730, max_major_version = 1 WHERE sku IN ('pro-buyout', 'pro-plus-buyout');
-- 订阅：更新权益随订阅期滚动，额度由订阅状态决定，此处不做静态限制（保持 NULL）
UPDATE products SET update_until_days = NULL, max_major_version = NULL WHERE sku IN ('pro-subscription', 'pro-plus-subscription');
-- C8（2026-09-20）：机器码首次出现账本
--
-- 背景：客户端的试用账本存在本地，删掉存档文件重装即可再领一次试用（"删档重来"）。
-- 服务端此前没有任何"这台机器我见过"的记忆，客户端离线也无从判断。
--
-- 口径：
--   - first_seen_at 一经写入**永不更新**（只增不动），是判定"这台机器最早何时接触过本产品"的唯一权威值；
--   - last_seen_at 每次见到就刷新，仅用于运营观察，不参与任何判定；
--   - 只记机器码本身，不记邮箱/订单等 PII（机器码是本地硬件派生的随机串，不构成身份信息）。
CREATE TABLE IF NOT EXISTS machine_first_seen (
    machine_code VARCHAR(64) PRIMARY KEY,
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    source VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_machine_first_seen_last ON machine_first_seen (last_seen_at);
