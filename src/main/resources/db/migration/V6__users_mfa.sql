-- plan-7.0 / M1：管理员 MFA（B8 定案）—— users 表二次因子字段
--
-- 设计要点：
-- 1. **TOTP 密钥必须密文落库**：TOTP 密钥与密码哈希不同，它是「可逆秘密」——
--    泄漏即等于对方能**永久生成有效动态码**，比密码哈希泄漏更严重（密码还得撞、
--    动态码直接算）。故列名带 _cipher，存 AES-256-GCM 密文（Base64(iv || ct || tag)），
--    加密密钥由 `account.mfa-key` 经 HMAC-SHA256 派生，不复用 token 签名密钥。
-- 2. **mfa_enabled 与 mfa_secret_cipher 分离**：允许「已生成密钥但尚未验证启用」
--    的中间态（即 enroll 完成、activate 未完成）。登录只看 mfa_enabled，故未激活的
--    半成品不会把管理员锁在门外。
-- 3. **mfa_last_used_step 防重放**（RFC 6238 §5.2 建议）：记录最近一次成功校验的
--    时间步（epoch/stepSeconds）。同一时间步内 6 位码只能用一次，堵住「截获到码后在
--    其 30 秒有效窗口内重放」。同时天然覆盖「容错窗口内多个步都匹配」时的挑步问题。
-- 4. **默认关**：存量账号全部 mfa_enabled = false，升级后登录行为完全不变。
--    管理员按需在管理台自助开启（决策：默认关，可自助开启）。
ALTER TABLE users
    ADD COLUMN mfa_secret_cipher VARCHAR(255),
    ADD COLUMN mfa_enabled       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN mfa_enrolled_at   TIMESTAMP,
    ADD COLUMN mfa_last_used_step BIGINT;

COMMENT ON COLUMN users.mfa_secret_cipher IS 'TOTP 密钥密文（AES-256-GCM，Base64(iv||ct||tag)）；NULL=从未生成';
COMMENT ON COLUMN users.mfa_enabled IS '是否已启用二次因子；仅 ADMIN 可由管理台自助开启，登录时按此列要求第二因子';
COMMENT ON COLUMN users.mfa_enrolled_at IS 'MFA 启用时刻；NULL=未启用';
COMMENT ON COLUMN users.mfa_last_used_step IS '最近一次成功校验的 TOTP 时间步（防重放），RFC 6238 §5.2';
