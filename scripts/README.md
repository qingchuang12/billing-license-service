# 运维脚本与数据库迁移索引（scripts/）

> 本目录是 billing-license-service 所有**运维脚本、构建/部署、数据库迁移**的统一入口。
> 项目尚未上线，脚本以「可读、可直接执行、无占位死代码」为原则；标注 `pending` 的待办项
> 对应整改计划 `plan.md` 中的具体卡片（如 B20 容器化、H13 健康检查）。

## 目录结构

| 目录 | 用途 | 本仓库已提供 |
|---|---|---|
| `scripts/db/` | 数据库 / Flyway 迁移相关 | `show_migrations.sh`：查看已执行的 Flyway 迁移 |
| `scripts/deploy/` | 构建与部署 | `package.sh`（打可执行 jar）、`run.sh`（本地运行 jar）、`build.sh` / `up.sh`（Docker 镜像构建与 compose 启动） |
| `scripts/ops/` | 运行时运维 | `healthcheck.sh`（端口/存活探测，actuator 待 H13） |

## 数据库迁移（Flyway）

迁移脚本位于 `src/main/resources/db/migration/`，**按文件名版本号顺序在应用启动时自动执行**
（`application.yml` 中 `spring.flyway.enabled=true`，`locations=classpath:db/migration`）。
**无需手动执行 SQL**；重新部署即自动增量迁移。

### 迁移清单（V1–V6，禁止合并、禁止首次部署后修改）

| 版本 | 主题 | 关键内容 |
|---|---|---|
| V1 | 初始库表 | products / orders / licenses 等核心表（含 `payment_events`、`redeem_codes`） |
| V2 | 收银台与事件 | `checkout_sessions` 表、`orders.email`、事件/审计表 |
| V3 | 换机重发审计 | `licenses.machine_code` / `reissued_from` / `revoked_at` |
| V4 | 三档产品模型（B16） | `products.tier` / `features`，四类种子（Pro/Pro Plus × 买断/订阅） |
| V5 | 订阅制（B18） | `subscriptions` 表（托管 Paddle/Stripe 生命周期对账） |
| V6 | 双币种定价（B19） | `products.price_cny` / `price_usd`，按区域取价；回填（USD 沿用 price，CNY 示例汇率 7.2） |

> ⚠️ **迁移文件一经首次部署即被 Flyway 校验和锁定**：之后不得再编辑内容（增列请新建 Vx+1）。
> 当前 V1–V6 尚未在任何真实库执行，可在上线前安全补充头注释，但上线后修改会导致启动失败。

查看已执行迁移：

```bash
./scripts/db/show_migrations.sh
```

## 构建与部署

打可执行 jar（跳过测试，CI 中请去掉 `-DskipTests`）：

```bash
./scripts/deploy/package.sh
```

本地运行（需先准备好 PostgreSQL，并通过环境变量注入密钥，**禁止明文写死**）：

```bash
export DB_URL=jdbc:postgresql://127.0.0.1:5432/license
export DB_USERNAME=license
export DB_PASSWORD='<强口令>'
export ADMIN_API_KEYS='<管理端密钥>'
export KMS_PROVIDER=local            # local | aws | aliyun
# 各支付渠道密钥见 application.yml（KMS_*/ALIPAY_*/WECHAT_*/STRIPE_* 等）
./scripts/deploy/run.sh
```

### 容器化部署（Docker Compose）

已提供 `Dockerfile`（多阶段构建：maven:21 构建 → temurin:21-jre 运行，非 root 用户）+
`docker-compose.yml`（app + postgres，网络隔离）。

**首次启动完整流程：**

```bash
# 1. 准备环境变量（必填：DB_PASSWORD、ADMIN_API_KEYS）
#    从模板复制，填入真实值；.env 已被 .gitignore 忽略，不会入库
cp .env.example .env
#    编辑 .env 设置强口令（或直接用下方命令生成随机值）
#    DB_PASSWORD=<随机 24 位>
#    ADMIN_API_KEYS=<随机 32 位>

# 2. 准备签名密钥（KMS=local 时必须）
mkdir -p keys
openssl genrsa -out keys/private.key 2048
openssl rsa -in keys/private.key -pubout -out keys/public.key

# 3. 构建并启动
docker compose up -d --build
# 或旧版 docker-compose（视本机 CLI 版本而定）：
# docker-compose up -d --build

# 4. 查看状态与日志
docker compose ps
docker compose logs -f app
```

**排错指南：**

| 现象 | 原因 | 修复 |
|---|---|---|
| postgres 报 `superuser password is not specified` | `.env` 不存在或 `DB_PASSWORD` 为空 | 创建 `.env` 并填入非空密码 |
| postgres 重复报错、起不来看似脏数据 | 之前失败残留了初始化数据 | `docker compose down -v && docker compose up -d postgres` 清卷重来（⚠️ 会删库） |
| app 启动失败、日志提示 `密钥文件不存在` | `keys/` 目录未生成私钥/公钥 | 按第 2 步生成密钥对 |
| app 连不上 DB、日志 `Connection refused` | app 启动快于 postgres 就绪 | `depends_on: condition: service_healthy` 已配，postgres 慢时自动等待重试 |

> 预备就绪后，`scripts/deploy/` 也提供了 `build.sh` / `up.sh` 快捷脚本（执行相同流程）。

## 环境变量速查

完整定义以 `src/main/resources/application.yml` 为准。常用：

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接（缺失即启动失败，fail-fast） |
| `KMS_PROVIDER` | KMS 方案：`local`（默认）/ `aws` / `aliyun` |
| `KMS_KEY_ID` | 云 KMS 密钥 ID/ARN（local 不使用） |
| `KMS_AWS_REGION` / `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | AWS KMS 区域与凭证（默认链） |
| `KMS_ALIYUN_REGION` / `KMS_ALIYUN_SIGN_ALG` / `KMS_ALIYUN_KEY_TYPE` / `ALIYUN_ACCESS_KEY_ID` / `ALIYUN_ACCESS_KEY_SECRET` | 阿里云 KMS 配置与凭证 |
| `ADMIN_API_KEYS` | 管理端 API 密钥（逗号分隔，缺失即启动失败） |
| `APP_BASE_URL` | 对外基址，用于拼接各渠道回调/回跳地址 |

## 健康检查

`scripts/ops/healthcheck.sh` 探测应用端口存活；更完整的 readiness/liveness 探针（actuator）
见整改计划 **H13**，落地后该脚本将优先探测 `/actuator/health`。
