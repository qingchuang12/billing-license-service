-- 收银台会话持久化完整支付入口：此前只存 pay_url，扫码单在重复选渠道/刷新恢复时
-- qrCode、paymentMode 全丢，前端无法继续支付。新增列只补存储，不改既有数据语义。
ALTER TABLE checkout_sessions ADD COLUMN qr_code TEXT;
ALTER TABLE checkout_sessions ADD COLUMN payment_mode VARCHAR(16);
