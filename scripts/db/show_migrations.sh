#!/usr/bin/env bash
# 查看 Flyway 已执行的迁移历史（flyway_schema_history）。
# 前置：psql 可用，且 DB_URL/DB_USERNAME/DB_PASSWORD 已设置（兼容 JDBC URL 解析）。
# 用法：./scripts/db/show_migrations.sh
set -euo pipefail

: "${DB_URL:?请设置 DB_URL（如 jdbc:postgresql://host:5432/dbname）}"
: "${DB_USERNAME:?请设置 DB_USERNAME}"
: "${DB_PASSWORD:?请设置 DB_PASSWORD}"

if ! command -v psql >/dev/null 2>&1; then
  echo "未找到 psql，请先安装 PostgreSQL 客户端" >&2
  exit 1
fi

# 从 JDBC URL 解析 host/db（支持 jdbc:postgresql://host:port/db）
URL="${DB_URL#jdbc:postgresql://}"
HOST="${URL%%/*}"; HOST="${HOST%%:*}"
DB="${URL##*/}"; DB="${DB%%\?*}"

export PGPASSWORD="$DB_PASSWORD"
echo "==> Flyway 迁移历史（host=${HOST:-localhost}, db=${DB:-license}）"
psql -h "${HOST:-localhost}" -U "$DB_USERNAME" -d "${DB:-license}" -t -A -F $'\t' \
  -c "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;"
