-- =====================================================================
-- 运维脚本：重置指定账号的二次因子（MFA）绑定状态
-- ---------------------------------------------------------------------
-- 归属：plan-7.0（管理员 MFA / 决策 B8）—— 认证器丢失且邮箱也不可用时的自救路径
-- 重要：本脚本**不是 Flyway 迁移**，不会被自动执行；须运维在目标
--       环境**手动跑一次**。Flyway 仅扫描 classpath:db/migration，
--       本文件位于 scripts/db/，不受影响。
--
-- 适用情形（两类）：
--   1. 管理员的认证器设备丢失 / 更换，且邮箱验证码兜底也不可用
--      （邮箱停用、SMTP 故障、或 `account.mfa.email-fallback-enabled=false`），
--      此时该账号无法完成第二因子，也就登不进管理台去自助解绑。
--   2. `ACCOUNT_MFA_KEY` 已轮换：旧密文用新密钥解不开，登录会在解密步骤失败
--      （业务错误码 MFA_SECRET_UNREADABLE）。因该配置是 fail-fast 设计，
--      服务本身仍能启动，但受影响账号须重置后重新绑定。
--
-- 效果：清空 MFA 四个字段，使该账号回到「未开启二次因子」状态，可用密码直接登录，
--       随后在管理台「安全设置」中重新绑定。同时 token_version + 1，
--       确保此前已签发的令牌（包括可能被他人持有的）立即失效。
--
-- ⚠️ 安全提示：本脚本等同于临时关闭该账号的一道防线。执行前请确认
--      操作者身份（电话 / 工单等线下核验），并在执行后立即要求其重新绑定。
--
-- 连库：与 scripts/db/show_migrations.sh 同约定，依赖环境变量
--       DB_URL / DB_USERNAME / DB_PASSWORD，并需本地有 psql。
-- 执行示例：
--   DB_URL=jdbc:postgresql://host:5432/license \
--   DB_USERNAME=... DB_PASSWORD=... \
--   psql -h <host> -U <user> -d <db> -f scripts/db/reset-admin-mfa.sql
--   （先把下方 <admin-email> 占位符替换为真实邮箱，小写）
-- =====================================================================

-- 0) 预检：确认目标账号存在，并看清当前 MFA 状态
SELECT id, email, role, status, mfa_enabled, mfa_enrolled_at, token_version
FROM users
WHERE email = '<admin-email>';

-- 1) 重置 MFA
--    - 清空密钥密文与启用标记，回到「未开启」状态
--    - token_version + 1：使该账号已签发的全部令牌立即失效
--      （即便此前有令牌泄漏，重置后也无法继续使用）
UPDATE users
SET mfa_secret_cipher = NULL,
    mfa_enabled       = false,
    mfa_enrolled_at   = NULL,
    mfa_last_used_step = NULL,
    token_version     = token_version + 1,
    updated_at        = now()
WHERE email = '<admin-email>';

-- 2) 后检：确认已重置且 token_version 已递增
SELECT id, email, role, status, mfa_enabled, mfa_enrolled_at, token_version
FROM users
WHERE email = '<admin-email>';

-- =====================================================================
-- 提醒：重置只是恢复手段，不是收尾。请务必让该管理员尽快重新登录管理台，
--       在「安全设置」中重新绑定认证器（生成密钥 → 录入 → 动态码激活），
--       否则该账号将长期处于无二次因子保护的状态。
-- =====================================================================
