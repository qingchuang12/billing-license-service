-- 审计日志表（方案 B：@Audit 注解 + AOP 切面 + 异步独立事务落库）
-- 幂等：重复执行不报错。
CREATE TABLE IF NOT EXISTS audit_logs (
    id          UUID PRIMARY KEY,
    actor       VARCHAR(64),
    action      VARCHAR(64) NOT NULL,
    target      VARCHAR(255),
    success     BOOLEAN NOT NULL,
    detail      TEXT,
    ip          VARCHAR(64),
    user_agent  TEXT,
    created_at  TIMESTAMP NOT NULL
);
