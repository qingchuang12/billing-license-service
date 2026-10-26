-- V13：产品档案（权益/名称/描述按产品定义）与套餐推荐位
--
-- 背景：products 每行是一个「套餐/SKU」（产品 × 支付周期）；权益特性属于产品维度，
-- 同一 product_code 下的月付/半年/年付/永久共享同一份权益。此处把权益从 products 行
-- 上移到 product_profiles，并按 product_code 回填存量数据。

CREATE TABLE IF NOT EXISTS product_profiles (
    id UUID PRIMARY KEY,
    product_code VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    name_en VARCHAR(255),
    description TEXT,
    description_en TEXT,
    features TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_product_profiles_code UNIQUE (product_code)
);

COMMENT ON TABLE product_profiles IS '产品档案：按产品码定义名称/描述/权益，套餐（products）共享';
COMMENT ON COLUMN product_profiles.product_code IS '产品码（与 products.product_code 对应，唯一）';
COMMENT ON COLUMN product_profiles.features IS '权益特性清单（JSON 数组字符串，如 ["OFFLINE","MULTI_DEVICE"]）';

-- 回填：每个 product_code 取最近更新的一行作为代表（同组套餐的权益本应一致）
INSERT INTO product_profiles (id, product_code, name, name_en, description, description_en, features, created_at, updated_at)
SELECT gen_random_uuid(), t.product_code, t.name, t.name_en, t.description, t.description_en, t.features, t.created_at, t.updated_at
FROM (
    SELECT DISTINCT ON (product_code)
           product_code, name, name_en, description, description_en, features, created_at, updated_at
    FROM products
    WHERE product_code IS NOT NULL AND product_code <> ''
    ORDER BY product_code, updated_at DESC NULLS LAST, created_at DESC
) t
ON CONFLICT (product_code) DO NOTHING;

-- 权益只保留在产品档案：products.features 为历史冗余列
ALTER TABLE products DROP COLUMN IF EXISTS features;

-- 套餐推荐位（原收银台静态白名单字段，改由后台配置）
ALTER TABLE products ADD COLUMN IF NOT EXISTS recommended BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE products SET recommended = TRUE WHERE sku = 'pro-plus-buyout';
COMMENT ON COLUMN products.recommended IS '收银台是否展示「推荐」标记';
