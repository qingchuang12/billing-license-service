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
