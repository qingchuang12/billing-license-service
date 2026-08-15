-- V3 migration: licenses 表新增换机重发/作废审计字段
-- ddl-auto=validate，所有 schema 变更必须通过 Flyway

ALTER TABLE licenses ADD COLUMN IF NOT EXISTS machine_code VARCHAR(255);
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS reissued_from UUID;
ALTER TABLE licenses ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;

COMMENT ON COLUMN licenses.machine_code IS '绑定的设备机器码（换机重发时更新）';
COMMENT ON COLUMN licenses.reissued_from IS '换机重发时指向原 License ID';
COMMENT ON COLUMN licenses.revoked_at IS '作废时间';
