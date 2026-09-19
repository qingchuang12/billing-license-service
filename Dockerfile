# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
# 仅拷贝源码与 pom；Lombok 注解处理器已在 pom 的 maven-compiler-plugin 中显式声明，无需额外参数
COPY pom.xml .
COPY src ./src
# 镜像构建跳过测试以提速；测试闸门由 CI（.github/workflows/ci.yml）承担，发布脚本 scripts/deploy/package.sh 也会跑测试
RUN mvn -B -e clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
# i11/W22：健康检查依赖 curl（基础 jre 镜像不含 wget/curl）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# 以非 root 用户运行，缩小容器攻击面（遵循最小权限）
RUN groupadd -r appuser && useradd -r -g appuser appuser
COPY --from=build /build/target/*.jar /app/app.jar
RUN chown -R appuser:appuser /app
USER appuser
# 端口口径以 application.yml 的 server.port 为准（8000，K11 已统一全仓）；容器映射见 docker-compose.yml
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
