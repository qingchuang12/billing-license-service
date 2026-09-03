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
