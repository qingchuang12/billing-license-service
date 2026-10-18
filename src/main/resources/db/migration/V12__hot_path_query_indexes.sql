-- ============================================================================
-- Flyway 迁移 V12 —— 热路径外键/状态列补索引
--
-- 背景：2026-10-17 全量性能审计（结论落 `架构与业务流程设计.md` 末「附二」）逐条比对了
--       repository 查询键与现存索引（V1 基线 + V4 + V9），下列四条**每次核心业务请求都会命中**
--       却仍走顺序扫描：
--         ① licenses.order_id —— LicenseRepository#findByOrder / findByOrderId / findByOrderIdIn
--            （收银台到账轮询每次命中；用户订单列表折算可退额按批量订单命中；退款与换机也走它）
--         ② orders.customer_id —— OrderRepository#findByCustomerIdOrderByCreatedAtDesc
--            （GET /api/account/orders）与 countByCustomerId（管理端用户详情）
--         ③ payments.order_id —— PaymentRepository#findByOrderIdStr
--            （webhook 定位支付记录、退款取渠道交易号，均为字符串列等值查询）
--         ④ orders.status —— OrderRepository#findByStatus 与管理端 /api/admin/orders?status=
--            （V1 只给该列写了 COMMENT，未建索引）
--       数据量增长后这四条会退化为全表扫描，且 ①③ 位于支付回调与退款这两条资损敏感链路上。
--
-- 实现：B-tree 索引各一。IF NOT EXISTS 保证幂等；纯增量，不动数据、不改已有查询语义。
--       与硬约束 4 一致：只新增本文件，V1–V11 一律不改。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_licenses_order ON licenses (order_id);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id);
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments (order_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
