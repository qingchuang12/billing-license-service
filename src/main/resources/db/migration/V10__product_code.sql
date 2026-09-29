-- ============================================================================
-- Flyway 迁移 V10 —— 产品维度建列（product_code）
-- 收银台此前只有 SKU 维度：客户端传 ?productId=AI-TOOLS-PRO 时把「产品码」与「SKU」
-- 混在一个参数里（四档 SKU 实际全部属于 AI-TOOLS 单一产品）。新增 product_code 列
-- 承载产品维度，GET /api/products?product=<产品码> 按此过滤目录。
-- ADD COLUMN 用 IF NOT EXISTS 保证幂等（重复执行不报错），VARCHAR(64) 与既有编码类列同型；
-- UPDATE 回填现有全部行为 'ai-tools'（当前 products 表内所有 SKU 均属 AI-TOOLS）。
-- 不建索引：products 为在售目录表（行数十位量级），按产品码过滤走顺序扫描即可，索引无收益。
-- ============================================================================

ALTER TABLE products ADD COLUMN IF NOT EXISTS product_code VARCHAR(64);

UPDATE products SET product_code = 'ai-tools';

COMMENT ON COLUMN products.product_code IS
    '产品码（产品维度，如 ai-tools）；一个产品含多个 SKU 档位，收银台按产品码过滤目录用；NULL 表示未归类（历史数据/非目录类产品）';
