#!/usr/bin/env bash
# 应用存活探测：优先探测 actuator/health（H13 已落地，生产默认开启），否则退化为 TCP 端口探测。
# 用法：./scripts/ops/healthcheck.sh
set -uo pipefail

PORT="${APP_PORT:-8080}"
HOST="${APP_HOST:-127.0.0.1}"

# 优先用 Actuator 健康检查（H13 已落地，生产默认开启）
if command -v curl >/dev/null 2>&1; then
  if curl -fsS --max-time 3 "http://${HOST}:${PORT}/actuator/health" >/dev/null 2>&1; then
    echo "OK (actuator/health)"
    exit 0
  fi
fi

# 退化：bash /dev/tcp 端口连通性检查
if (exec 3<>"/dev/tcp/${HOST}/${PORT}") 2>/dev/null; then
  exec 3>&- 3<&-
  echo "OK (port ${PORT} reachable) —— actuator 探测未通过，请检查应用健康状态"
  exit 0
fi

echo "FAIL (port ${PORT} 不可达)" >&2
exit 1
