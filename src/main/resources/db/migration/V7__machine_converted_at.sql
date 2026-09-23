-- B7 = B（plan-7.0 / D3，2026-09-23 川哥拍板）：机器「已转正」标记
-- 目的：把「试用防白嫖」（first_seen_at / source）与「正式授权判定」（converted_at）两本账分开。
-- 口径：
--   * converted_at = 该机器首次完成任一正式绑定（购买直签 / 兑换码 / 密钥激活 / 启动上报）的时间；
--   * NULL = 未转正（纯试用机器）；
--   * 首次置位后不再更新；授权作废 / 退款**不回收**（撤标记等于再送一次试用，川哥拍板 ③）；
--   * C8 首跑查询端点暂不回传该标记（④，客户端契约零改动）。
ALTER TABLE machine_first_seen ADD COLUMN IF NOT EXISTS converted_at TIMESTAMP;
