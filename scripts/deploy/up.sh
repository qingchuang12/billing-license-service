#!/usr/bin/env bash
# 启动容器栈（app + postgres）。密钥/必填项 fail-fast，缺失即拒绝启动，避免带着空口令/默认口令上线。
set -euo pipefail

cd "$(dirname "$0")/../.."

# 必填环境变量（由 .env 或 shell 注入；compose 透传给容器）
: "${DB_PASSWORD:?请设置 DB_PASSWORD（环境变量或 .env 文件）}"
: "${ADMIN_API_KEYS:?请设置 ADMIN_API_KEYS（管理端 API 密钥，逗号分隔）}"

# 本地签名密钥（KMS=local 时挂载进容器，绝不入库）
if [ ! -f ./keys/private.key ]; then
  echo "错误：缺少 ./keys/private.key（商户签名私钥）。请先生成（见 README 容器化章节）" >&2
  exit 1
fi
if [ ! -f ./keys/public.key ]; then
  echo "错误：缺少 ./keys/public.key（商户签名公钥）" >&2
  exit 1
fi

echo "==> 启动容器栈（billing-network: app + postgres）"
docker compose up -d

echo "==> 启动完成。"
echo "    查看状态：docker compose ps"
echo "    查看日志：docker compose logs -f app"
