-- =====================================================================
-- 运维脚本：将指定用户提升为管理员（ADMIN）
-- ---------------------------------------------------------------------
-- 归属：plan-6.0 (统一登录) 决策 B3 —— 首个管理员的产生方式
-- 拍板：川哥 2026-09-22 选定「运维 SQL 直改」
-- 重要：本脚本**不是 Flyway 迁移**，不会被自动执行；须运维在目标
--       环境**手动跑一次**。Flyway 仅扫描 classpath:db/migration，
--       本文件位于 scripts/db/，不受影响。
-- 前置：目标账号须已通过普通注册流程存在（role 默认 USER）。
--       若尚未注册，请先在结账/账号页用目标邮箱注册，再执行本脚本。
-- 连库：与 scripts/db/show_migrations.sh 同约定，依赖环境变量
--       DB_URL / DB_USERNAME / DB_PASSWORD，并需本地有 psql。
-- 执行示例：
--   DB_URL=jdbc:postgresql://host:5432/license \
--   DB_USERNAME=... DB_PASSWORD=... \
--   psql -h <host> -U <user> -d <db> -f scripts/db/promote-to-admin.sql
--   （先把下方 <admin-email> 占位符替换为真实邮箱，小写）
-- =====================================================================

-- 0) 预检：确认目标账号存在，并看清当前角色/状态
SELECT id, email, role, status, token_version
FROM users
WHERE email = '<admin-email>';

-- 1) 执行提升
--    - role 置为 ADMIN，使其登录后获得 ROLE_USER + ROLE_ADMIN
--    - token_version + 1：清掉该账号可能残留的 USER 令牌，强制下一次
--      登录拿新令牌（角色不写进 JWT，每请求现查，提升即时生效）
UPDATE users
SET role         = 'ADMIN',
    token_version = token_version + 1,
    updated_at    = now()
WHERE email = '<admin-email>';

-- 2) 后检：确认已变为 ADMIN
SELECT id, email, role, status, token_version
FROM users
WHERE email = '<admin-email>';

-- =====================================================================
-- 回滚（按需）：将管理员降级回普通消费者
--   降级同样 +1 token_version，使其已签发的 ADMIN 令牌立即失效。
-- =====================================================================
-- UPDATE users
-- SET role         = 'USER',
--     token_version = token_version + 1,
--     updated_at    = now()
-- WHERE email = '<admin-email>';
