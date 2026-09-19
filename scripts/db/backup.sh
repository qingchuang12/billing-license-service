#!/usr/bin/env bash
# 数据库备份（K15，2026-09-18 新建）——上线前/迁移前必做。
#
# ⚠️ 本机（Windows，无 psql/docker）**未实测**：已做 bash 语法检查与参数解析自测，
#    首次在目标环境使用前请确认 pg_dump 版本与库版本兼容（PG 15 用 pg_dump 15+）。
#
# 连接信息复用项目根 .env（DB_URL / DB_USERNAME / DB_PASSWORD）。
# 用法：./scripts/db/backup.sh [输出目录]     默认 ./backups
#      需要本机有 pg_dump；容器部署时可改为：
#      docker compose exec -T postgres pg_dump -U "$DB_USERNAME" -d billing_db -Fc > backup.dump
set -euo pipefail

cd "$(dirname "$0")/../.."

[[ -f .env ]] || { echo "未找到 .env（见 .env.example）" >&2; exit 1; }
set -a; . ./.env; set +a
: "${DB_URL:?DB_URL 未设置（见 .env）}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置（见 .env）}"

# jdbc:postgresql://host:port/db?args  →  host / port / db
url="${DB_URL#jdbc:postgresql://}"
hostport="${url%%/*}"
db_and_args="${url#*/}"
db="${db_and_args%%\?*}"
host="${hostport%%:*}"
port="${hostport##*:}"
[[ "$port" == "$host" ]] && port=5432

out_dir="${1:-./backups}"
mkdir -p "$out_dir"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$out_dir/${db}-${stamp}.dump"

command -v pg_dump >/dev/null 2>&1 || {
  echo "未找到 pg_dump（PostgreSQL 客户端）。请安装，或用文件顶部的 docker compose exec 方案。" >&2
  exit 1
}

echo "==> 备份 ${db}@${host}:${port} → ${out}"
PGPASSWORD="$DB_PASSWORD" pg_dump \
  -h "$host" -p "$port" -U "${DB_USERNAME:-postgres}" -d "$db" \
  --format=custom --no-owner --no-privileges \
  -f "$out"

echo "==> 完成：$(du -h "$out" | cut -f1)  ${out}"
echo "    恢复：./scripts/db/restore.sh ${out}"
