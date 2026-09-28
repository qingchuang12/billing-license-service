-- ============================================================================
-- Flyway 迁移 V9 —— 机器码维度查询建索引 + license_events 事件类型注释补全
--
-- 背景：plan-1.0 / S1 新增公开端点 GET /api/licenses/pending（支付后客户端按**本机机器码**
--       轮询领取待激活授权）。它按两条路径筛候选：
--         ① licenses.machine_code = :machineId（支付回调直签路径写入）
--         ② orders.machine_code   = :machineId（管理端补签发 / 收银台轮询补偿路径——
--            这两条走 LicenseService.issueLicensesForOrder，机器码只落在订单上，
--            licenses.machine_code 为空，靠 License#getMachineCode() 回落）
--       V1 基线给两表的机器码列只写了 COMMENT、未建索引（idx_licenses_* 只有 customer/key/status），
--       ②路还带 created_at 范围条件。该端点客户端每 60s 轮询一次、最长 30 分钟，
--       数据量上来后两条查询都会退化为全表扫描。
--
-- 实现：B-tree 索引各一。IF NOT EXISTS 保证幂等；纯增量，不动数据、不影响既有查询计划。
--       顺带把 license_events.event_type 的列注释更新到当前实际枚举全集
--       （V1 之后已陆续新增 UNBOUND / ACTIVATED / BOUND_BY_REPORT，注释一直滞后，
--        售后按注释排查会误判「某类事件不存在」）。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_licenses_machine_code ON licenses (machine_code);
CREATE INDEX IF NOT EXISTS idx_orders_machine_code ON orders (machine_code);

COMMENT ON COLUMN license_events.event_type IS
    '事件类型（ISSUED/REDEEMED/REISSUED/REVOKED/VERIFY_FAILED/UNBOUND/ACTIVATED/BOUND_BY_REPORT/PENDING_QUERIED）';
