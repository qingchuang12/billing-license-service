-- plan-7.0 · Web 后台账户基础功能（Q3 = 是）：users 表强制改密标记
--
-- 设计要点：
-- 1. **标记只由服务端判定**：管理员代重置后置为 true，`MustChangePasswordFilter` 据此拦截
--    除「读自己 / 改密 / 登出」之外的全部受保护 API，返回 PASSWORD_CHANGE_REQUIRED。
--    只靠前端弹窗引导不算数——客户端可被绕过。
-- 2. **清除路径**：用户走自助找回（邮箱验证码）或已登录改密后清除该标记；两条路径都在
--    AccountService 内与「写新哈希 + tokenVersion+1」同一事务完成。
-- 3. **默认 false**：存量账号升级后行为完全不变；只有被管理员代重置的账号才会被置位。
-- 4. **发布顺序**：本迁移只是加列，本身不会困住任何用户；必须先发布「识别标记 + 改密端点可用」
--    的后端，再开放管理员代重置入口（否则被代重置的用户会被挡在登录态里）。
ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN users.must_change_password
    IS '管理员代重置后是否必须在下次登录完成自助改密；false=无需强制';
