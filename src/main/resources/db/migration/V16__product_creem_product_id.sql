-- V16：套餐的 Creem 渠道侧产品 ID（#31 Creem 渠道接入）
--
-- Creem 下单（POST /v1/checkouts）必带渠道目录产品 ID（prod_ 前缀），与 Paddle 只认
-- 目录价目同理：没有 ad-hoc 内联价格这条路，未配置即拒单（CREEM_PRODUCT_ID_MISSING）。
-- 可空：NULL = 该套餐不走 Creem 渠道（收银台能力矩阵自动排除）。

ALTER TABLE products ADD COLUMN IF NOT EXISTS creem_product_id VARCHAR(255);

COMMENT ON COLUMN products.creem_product_id IS 'Creem 产品 ID（prod_ 前缀）；Creem 只认目录产品，缺失即拒单';
