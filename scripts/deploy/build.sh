#!/usr/bin/env bash
# 构建 billing-license-service 的 Docker 镜像（基于仓库根目录 Dockerfile 多阶段构建）
set -euo pipefail

cd "$(dirname "$0")/../.."

if ! command -v docker >/dev/null 2>&1; then
  echo "错误：未找到 docker，请先安装 Docker" >&2
  exit 1
fi

echo "==> 构建 Docker 镜像（compose service: app）"
docker compose build app

echo "==> 构建完成。可用 ./scripts/deploy/up.sh 启动，或：docker compose up -d"
