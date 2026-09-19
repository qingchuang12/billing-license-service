#!/usr/bin/env bash
# 本地运行已构建的 jar。密钥/连接信息一律通过环境变量注入（或写在项目根 .env，应用会加载），禁止明文写死。
# 用法：先 export 必要变量（或 cp .env.example .env 填值），再 ./scripts/deploy/run.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

JAR=$(ls -1 target/billing-license-service-*.jar 2>/dev/null | head -1 || true)
if [ -z "${JAR:-}" ]; then
  echo "未找到 target/*.jar，请先执行 ./scripts/deploy/package.sh" >&2
  exit 1
fi

# 缺失关键配置时明确失败（应用侧也是 fail-fast，这里提前给出更友好的提示）
# K10（2026-09-18）：仓库内已无默认值，故 APP_BASE_URL / MAIL_PASSWORD 亦属必填。
: "${DB_PASSWORD:?请设置 DB_PASSWORD}"
: "${ADMIN_API_KEYS:?请设置 ADMIN_API_KEYS}"
: "${APP_BASE_URL:?请设置 APP_BASE_URL（如 http://localhost:8000）}"
: "${MAIL_PASSWORD:?请设置 MAIL_PASSWORD}"

echo "==> 启动 ${JAR}"
exec java -jar "$JAR"
