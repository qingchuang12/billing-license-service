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
