FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
# 仅拷贝源码与 pom；Lombok 注解处理器已在 pom 的 maven-compiler-plugin 中显式声明，无需额外参数
COPY pom.xml .
COPY src ./src
# 镜像构建跳过测试以提速；测试闸门由 CI（.github/workflows/ci.yml）承担，发布脚本 scripts/deploy/package.sh 也会跑测试
RUN mvn -B -e clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:25-jre
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
# 走 shell 形式让 JAVA_OPTS 可注入（compose 用 -Xmx512m 压堆，余下留给元空间/线程栈/直接内存）；
# exec 保证 java 仍是 1 号进程，容器停止时 JVM 能正常走关闭钩子。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
