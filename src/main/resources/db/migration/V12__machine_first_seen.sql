-- C8（2026-09-20）：机器码首次出现账本
--
-- 背景：客户端的试用账本存在本地，删掉存档文件重装即可再领一次试用（"删档重来"）。
-- 服务端此前没有任何"这台机器我见过"的记忆，客户端离线也无从判断。
--
-- 口径：
--   - first_seen_at 一经写入**永不更新**（只增不动），是判定"这台机器最早何时接触过本产品"的唯一权威值；
--   - last_seen_at 每次见到就刷新，仅用于运营观察，不参与任何判定；
--   - 只记机器码本身，不记邮箱/订单等 PII（机器码是本地硬件派生的随机串，不构成身份信息）。
CREATE TABLE IF NOT EXISTS machine_first_seen (
    machine_code VARCHAR(64) PRIMARY KEY,
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    source VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_machine_first_seen_last ON machine_first_seen (last_seen_at);
