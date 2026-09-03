#!/usr/bin/env bash
# 构建可执行 jar。CI 中应去掉 -DskipTests 以保证测试闸门。
# 用法：./scripts/deploy/package.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

MVN="${MAVEN_HOME:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1; then
  echo "未找到 mvn，请设置 MAVEN_HOME 或使用 IDEA 内置 Maven" >&2
  exit 1
fi

echo "==> 打包 billing-license-service (skipTests)"
"$MVN" -B clean package -DskipTests

echo "==> 产物："
ls -1 target/*.jar 2>/dev/null || echo "未生成 jar"
