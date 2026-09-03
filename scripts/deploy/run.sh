#!/usr/bin/env bash
# 本地运行已构建的 jar。密钥/连接信息一律通过环境变量注入，禁止明文写死。
# 用法：先 export 必要变量，再 ./scripts/deploy/run.sh
#   export DB_URL=... DB_USERNAME=... DB_PASSWORD=... ADMIN_API_KEYS=... KMS_PROVIDER=local
set -euo pipefail

cd "$(dirname "$0")/../.."

JAR=$(ls -1 target/billing-license-service-*.jar 2>/dev/null | head -1 || true)
if [ -z "${JAR:-}" ]; then
  echo "未找到 target/*.jar，请先执行 ./scripts/deploy/package.sh" >&2
  exit 1
fi

# 缺失关键环境变量时明确失败，避免带着默认口令/空库密码启动
: "${DB_PASSWORD:?请设置 DB_PASSWORD}"
: "${ADMIN_API_KEYS:?请设置 ADMIN_API_KEYS}"

echo "==> 启动 ${JAR}"
exec java -jar "$JAR"
