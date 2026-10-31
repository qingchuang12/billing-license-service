-- V22：套餐即售卖单元，删除产品档案层
--
-- 背景：产品档案（product_profiles）只为承载权益（features）与产品级名称/描述而存在；
-- 权益已在 V22 同批下线（License 不再写 feat，客户端按 SKU 解锁），名称/描述本就冗余在
-- products 行上。档案层留着只会让「配了权益却不生效」继续误导运维，故整表删除。
-- 回滚：如需恢复，按 V13__product_profiles.sql 重建并从 products 回填即可。

DROP TABLE IF EXISTS product_profiles;

COMMENT ON COLUMN products.product_code IS '产品码（产品维度，如 ai-tools）：同码套餐在管理台归组、收银台按此过滤目录';
