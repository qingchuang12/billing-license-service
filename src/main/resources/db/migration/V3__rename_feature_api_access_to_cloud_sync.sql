-- ============================================================================
-- Flyway 迁移 V3 —— 产品权益键改名：API_ACCESS -> CLOUD_SYNC
--
-- 背景：V1 基线已提交并已应用（Flyway 校验和冻结，禁止回改），其中种子数据的
--       Pro Plus 两档（pro-plus-buyout / pro-plus-subscription）写入了权益键
--       API_ACCESS。现统一改名为 CLOUD_SYNC，与 application.yml 中
--       billing.feature-labels 的键保持一致——键不一致时收银台查不到展示文案，
--       只能回退显示原始键名。
--
-- 实现：REPLACE 按子串替换，幂等（重复执行结果一致，无旧键的行不受影响）。
--       限定 WHERE 缩小命中范围，避免对全表做无意义的行更新。
-- ============================================================================

UPDATE products
SET features = REPLACE(features, 'API_ACCESS', 'CLOUD_SYNC')
WHERE features LIKE '%API_ACCESS%';
