-- V14：套餐的渠道侧订阅价目 ID（#25 收银台真订阅）
--
-- 背景：订阅类周期（MONTHLY/QUARTERLY/HALF_YEAR/YEARLY）此前在收银台一律按一次性收款开出，
-- 渠道侧不建订阅对象，自动续费只对渠道后台手工预建的订阅生效。本迁移给套餐补两个
-- 「渠道订阅价目 ID」字段，配了才允许对应渠道对订阅周期开订阅下单：
--   stripe_price_id：Stripe recurring price（price_ 前缀）；为空时 Stripe 侧按周期内联
--                    recurring price_data 自动建价目，不依赖后台预建。
--   paddle_price_id：Paddle catalog price（pri_ 前缀）；Paddle 只认目录价目，订阅周期缺失即拒单。
-- 两者均可空：NULL = 该渠道不接此套餐的订阅（买断套餐本就不需要，保持为空）。

ALTER TABLE products ADD COLUMN IF NOT EXISTS stripe_price_id VARCHAR(255);
ALTER TABLE products ADD COLUMN IF NOT EXISTS paddle_price_id VARCHAR(255);

COMMENT ON COLUMN products.stripe_price_id IS 'Stripe 订阅价目 ID（price_ 前缀）；空 = 按周期内联 recurring price';
COMMENT ON COLUMN products.paddle_price_id IS 'Paddle 订阅价目 ID（pri_ 前缀）；Paddle 只认目录价目，订阅周期缺失即拒单';
