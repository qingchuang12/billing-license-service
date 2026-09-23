-- plan-6.0 / A1：统一登录 —— 用户角色
--
-- 设计要点：
-- 1. **角色不写入 JWT**：`JwtAuthFilter` 每次请求本就会从 DB 查 User（校验 status / tokenVersion），
--    顺带按 role 授予权限即可，零额外查询。好处是**降权/停用可即时生效**，
--    并可复用既有的 `tokenVersion + 1` 机制把该用户已签发的令牌全部踢下线。
-- 2. **默认 USER**：存量账号全部是消费者，升级后行为完全不变；管理员需显式置为 ADMIN
--    （首个管理员的产生方式见 plan-6.0 决策 B3，本迁移不插入业务数据）。
ALTER TABLE users
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';

COMMENT ON COLUMN users.role IS '角色：USER=普通消费者 / ADMIN=管理员；决定登录后获得的权限域';

CREATE INDEX idx_users_role ON users (role);
