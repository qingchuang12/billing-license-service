#!/usr/bin/env bash
# 数据库恢复（K15，2026-09-18 新建）——用于「迁移失败 / 上线回滚」场景。
#
# ⚠️ 本机（Windows，无 psql/docker）**未实测**：已做 bash 语法检查与参数解析自测。
# ⚠️ 默认 `pg_restore --clean --if-exists` 会**先删同名对象**，属破坏性操作：
#    必须在确认目标库就是要回滚的那一个之后执行，生产建议先恢复到临时库核对。
#
# 用法：./scripts/db/restore.sh <backup.dump> [--into <db>]   （--into 指定恢复到另一个库名）
set -euo pipefail

cd "$(dirname "$0")/../.."

dump="${1:-}"
[[ -n "$dump" && -f "$dump" ]] || { echo "用法：./scripts/db/restore.sh <backup.dump> [--into <db>]" >&2; exit 1; }
shift || true

target_db=""
if [[ "${1:-}" == "--into" ]]; then
  target_db="${2:?--into 需要一个库名}"
fi

[[ -f .env ]] || { echo "未找到 .env（见 .env.example）" >&2; exit 1; }
set -a; . ./.env; set +a
: "${DB_URL:?DB_URL 未设置（见 .env）}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置（见 .env）}"

url="${DB_URL#jdbc:postgresql://}"
hostport="${url%%/*}"
db_and_args="${url#*/}"
db="${db_and_args%%\?*}"
host="${hostport%%:*}"
port="${hostport##*:}"
[[ "$port" == "$host" ]] && port=5432

db="${target_db:-$db}"

command -v pg_restore >/dev/null 2>&1 || {
  echo "未找到 pg_restore（PostgreSQL 客户端）。容器部署可改为：" >&2
  echo "  docker compose exec -T postgres pg_restore -U \"\$DB_USERNAME\" -d <db> --clean --if-exists < backup.dump" >&2
  exit 1
}

echo "==> 恢复到 ${db}@${host}:${port}（--clean --if-exists，会删除同名对象）"
read -r -p "确认继续？输入 yes 执行：" ans
[[ "$ans" == "yes" ]] || { echo "已取消"; exit 1; }

PGPASSWORD="$DB_PASSWORD" pg_restore \
  -h "$host" -p "$port" -U "${DB_USERNAME:-postgres}" -d "$db" \
  --clean --if-exists --no-owner --no-privileges \
  "$dump"

echo "==> 完成。校验迁移状态：./scripts/db/show_migrations.sh"
