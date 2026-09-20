-- ============================================================================
-- Flyway 迁移 V2 —— 产品文案双语化（name_en / description_en）
-- 收银台产品名/描述后端化（K16 延伸）：现有 products.name/description 为 zh，
-- 新增英文列并按 SKU 回填，使前端可不经发版即切换中英文展示。
-- ADD COLUMN 用 IF NOT EXISTS 保证幂等（重复执行不报错）；
-- UPDATE 按 SKU 定位，仅覆盖已知四档，不影响后续新增 SKU（其 en 为 NULL，前端回退 zh）。
-- ============================================================================

ALTER TABLE products ADD COLUMN IF NOT EXISTS name_en VARCHAR(255);
ALTER TABLE products ADD COLUMN IF NOT EXISTS description_en TEXT;

UPDATE products SET name_en = 'Pro Lifetime',        description_en = 'One-time Pro license'         WHERE sku = 'pro-buyout';
UPDATE products SET name_en = 'Pro Plus Lifetime',   description_en = 'One-time Pro Plus license'   WHERE sku = 'pro-plus-buyout';
UPDATE products SET name_en = 'Pro Monthly',         description_en = 'Pro monthly subscription'     WHERE sku = 'pro-subscription';
UPDATE products SET name_en = 'Pro Plus Monthly',    description_en = 'Pro Plus monthly subscription' WHERE sku = 'pro-plus-subscription';
