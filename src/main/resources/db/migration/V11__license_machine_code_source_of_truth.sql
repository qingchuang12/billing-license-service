-- plan-1.0 / E4（审计丙）：让 licenses.machine_code 成为「设备绑定」的唯一真相
--
-- 背景：三条签发路径里，购买直签（issueLicense）与在线激活（CredentialBindingService）都会把机器码
-- 写进 licenses.machine_code，只有管理端补签发 / 收银台轮询补偿（issueLicensesForOrder → createLicense）
-- 把机器码留在 orders.machine_code 上；而 License#getMachineCode() 当时会回落读那一列。
-- 于是出现死锁：解绑只清「证上的列」（本就是 NULL），回落照旧命中订单 → 接口返回 200、写了 UNBOUND
-- 事件，换机激活却仍得 MACHINE_MISMATCH，用户侧没有任何出口（2026-09-29 E1 真链路实测复现）。
--
-- 本迁移只做一件事：把订单上的机器码抄写到证上 —— **不改变任何件的绑定事实**，只是让这个事实落到
-- 代码真正生效的那一列。迁移之后代码侧取消 getter 回落，两条读路径收敛为一条。
-- 无需建索引：licenses.machine_code 的索引已由 V9__machine_code_indexes.sql 建好。

UPDATE licenses l
   SET machine_code = o.machine_code
  FROM orders o
 WHERE l.order_id = o.id
   AND l.machine_code IS NULL
   AND o.machine_code IS NOT NULL
   AND TRIM(o.machine_code) <> '';

COMMENT ON COLUMN licenses.machine_code IS
    '绑定的设备机器码；设备绑定的唯一真相（V11 起不再从 orders.machine_code 回落读取）。NULL 表示尚未绑定设备';
