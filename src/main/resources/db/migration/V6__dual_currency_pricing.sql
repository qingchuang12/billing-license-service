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
