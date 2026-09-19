#!/usr/bin/env bash
# 构建可执行 jar。K14（2026-09-18）起**默认跑测试**（此前固定 -DskipTests，制品可带失败测试出厂）；
# 急用可 SKIP_TESTS=1 跳过，但不应用于发布。
# 用法：./scripts/deploy/package.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

MVN="${MAVEN_HOME:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1; then
  echo "未找到 mvn，请设置 MAVEN_HOME 或使用 IDEA 内置 Maven" >&2
  exit 1
fi

if [[ "${SKIP_TESTS:-0}" == "1" ]]; then
  echo "==> 打包 billing-license-service（SKIP_TESTS=1，跳过测试）"
  "$MVN" -B clean package -DskipTests
else
  echo "==> 打包 billing-license-service（含全量单测）"
  "$MVN" -B clean package
fi

echo "==> 产物："
ls -1 target/*.jar 2>/dev/null || echo "未生成 jar"
