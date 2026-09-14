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
