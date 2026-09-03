# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
# 仅拷贝源码与 pom；Lombok 注解处理器已在 pom 的 maven-compiler-plugin 中显式声明，无需额外参数
COPY pom.xml .
COPY src ./src
RUN mvn -B -e clean package -DskipTests

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# 以非 root 用户运行，缩小容器攻击面（遵循最小权限）
RUN groupadd -r appuser && useradd -r -g appuser appuser
COPY --from=build /build/target/*.jar /app/app.jar
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
