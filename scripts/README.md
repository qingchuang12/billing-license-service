# 运维脚本与数据库迁移索引（scripts/）

> 本目录是 billing-license-service 所有**运维脚本、构建/部署、数据库迁移**的统一入口。
> 项目尚未上线，脚本以「可读、可直接执行、无占位死代码」为原则。
> **上线执行手册见 [`../docs/上线准备工作.md`](../docs/上线准备工作.md)**（含渠道配置、密钥生成实测命令、上线当天清单）。
> 当前活动计划见 [`../docs/plan-3.0.md`](../docs/plan-3.0.md)。

## 目录结构

| 目录 | 用途 | 本仓库已提供 |
|---|---|---|
| `scripts/db/` | 数据库 / Flyway 迁移相关 | `show_migrations.sh`（查看已执行迁移）、`backup.sh`（pg_dump 备份）、`restore.sh`（pg_restore 恢复，带二次确认） |
| `scripts/deploy/` | 构建与部署 | `package.sh`（打可执行 jar，**默认跑测试**，`SKIP_TESTS=1` 可跳过）、`run.sh`（本地运行 jar）、`build.sh` / `up.sh`（Docker 镜像构建与 compose 启动） |
| `scripts/ops/` | 运行时运维 | `healthcheck.sh`（优先探测 `/actuator/health`，失败退化为端口连通性探测；默认端口 8000，`APP_PORT` 可覆盖） |

> ⚠️ `backup.sh` / `restore.sh` 于 2026-09-18 新增，仅做了语法检查与连接串解析自测（开发机无 psql/docker），**首次使用前请在目标环境确认 `pg_dump`/`pg_restore` 版本 ≥ 数据库版本**。

## 数据库迁移（Flyway）

迁移脚本位于 `src/main/resources/db/migration/`，**按文件名版本号顺序在应用启动时自动执行**
（`application.yml` 中 `spring.flyway.enabled=true`，`locations=classpath:db/migration`）。
**无需手动执行 SQL**；重新部署即自动增量迁移。

### 迁移清单（V1–V11，禁止合并、禁止首次部署后修改）

| 版本 | 主题 | 关键内容 |
|---|---|---|
| V1 | 初始库表 | `products` / `orders` / `order_items` / `licenses` / `redeem_codes` / `payment_transactions` / `payments` |
| V2 | 收银台与事件 | `checkout_sessions`、`license_events`、`payment_events` 表 |
| V3 | 换机重发审计 | `licenses.machine_code` / `reissued_from` / `revoked_at` |
| V4 | 三档产品模型（B16） | `products.tier` / `features`，四类种子（Pro/Pro Plus × 买断/订阅） |
| V5 | 订阅制（B18） | `subscriptions` 表（托管 Paddle/Stripe 生命周期对账） |
| V6 | 双币种定价（B19） | `products.price_cny` / `price_usd`，按区域取价；回填（USD 沿用 price，CNY 示例汇率 7.2） |
| V7 | Webhook 幂等与轮询冷却 | `payment_events(provider, event_id)` 唯一约束（并发重复投递由 DB 原子去重）+ `checkout_sessions.last_compensated_at`（`getStatus` 轮询冷却窗口） |
| V8 | 审计日志 | `audit_logs` 表（方案 B：`@Audit` 注解 + `AuditAspect` 异步独立事务落库） |
| V9 | License 状态语义 | 删除 `licenses.activated_at`；状态 ACTIVE/EXPIRED/REVOKED/REISSUED 语义固定 |
| V10 | 账号体系（v2.10） | `users` + `verification_codes` 表 |
| V11 | 产品更新门槛（A3） | `products.update_until_days` / `max_major_version`（买断默认 730 天/大版本 1） |

> ⚠️ **迁移文件一经首次部署即被 Flyway 校验和锁定**：之后不得再编辑内容（增列请新建 Vx+1）。
> V11 已入库（`e785a8a`），**同样受校验和保护**——即使它从未在真实库执行过，也不可再改文件。
> 上线前如需补充头注释可直接修改；上线后修改会导致启动失败。

查看已执行迁移：

```bash
./scripts/db/show_migrations.sh
```

## 构建与部署

打可执行 jar（2026-09-18 起**默认跑测试**；急用可 `SKIP_TESTS=1`，不应用于发布）：

```bash
./scripts/deploy/package.sh
```

本地运行（需先准备好 PostgreSQL，并通过 `.env` 或环境变量注入密钥，**禁止明文写死**）：

```bash
# 推荐：cp .env.example .env 后填值（application.yml 会加载它，见 docs/上线准备工作.md §3）
export DB_URL=jdbc:postgresql://127.0.0.1:5432/license
export DB_USERNAME=license
export DB_PASSWORD='<强口令>'
export ADMIN_API_KEYS='<管理端密钥>'
export APP_BASE_URL='http://localhost:8000'   # K10 起无默认值，缺失即拒启
# 各支付渠道密钥见 .env.example（ALIPAY_*/WECHAT_*/STRIPE_* 等）
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
#    命令见 docs/上线准备工作.md §1.2（唯一权威源，含两条自检）
#    ⚠️ 必须为「裸 32 字节」Ed25519，PEM 不被 LocalKmsService 接受（详见 §1.1）
#    ⚠️ 当前 keys/ 下是**调试密钥**（曾入 git 历史）：只用于联调，上线当天按
#    docs/上线准备工作.md §10-1 生成生产密钥并同步替换客户端公钥。
mkdir -p keys

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
| app 启动失败、日志提示无法解析占位符/口令为空 | 未注入 `DB_PASSWORD` / `ADMIN_API_KEYS`（无默认值，fail-fast） | 创建 `.env` 或在 shell 中 export 后重跑 |
| app 连不上 DB、日志 `Connection refused` | app 启动快于 postgres 就绪 | `depends_on: condition: service_healthy` 已配，postgres 慢时自动等待重试 |

> 预备就绪后，`scripts/deploy/` 也提供了 `build.sh` / `up.sh` 快捷脚本（执行相同流程）。

## 环境变量速查

完整定义以 `src/main/resources/application.yml` 为准，**模板与逐项说明见 `.env.example`**。常用：

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接（`DB_PASSWORD` 无默认值，缺失即启动失败，fail-fast） |
| `ADMIN_API_KEYS` | 管理端 API 密钥（逗号分隔，无默认值，缺失即启动失败） |
| `ACCOUNT_JWT_SECRET` / `ACCOUNT_CODE_PEPPER` | 用户令牌签名密钥（≥32B）/ 验证码哈希 pepper（生产必配） |
| `MAIL_PASSWORD` | SMTP 口令（无默认值）；`MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` 有默认值 |
| `APP_BASE_URL` | 对外基址，用于拼接各渠道回调/回跳地址（**无默认值，缺失即拒启**） |
| `PAYMENT_ENABLED_CHANNELS` | 启用的支付渠道（逗号分隔；留空=按各渠道配置齐全度自动启用） |
| `SPRING_PROFILES_ACTIVE` | `docker`（默认，schema 护栏）/ `prod`（关 Swagger、强制真发邮件）/ `prod,docker` |
| `BILLING_TRUST_XFF` | 置于可信反代之后时置 `true`，否则限流会把所有用户当成同一 IP |
| `PRIVATE_KEY_PATH` / `PUBLIC_KEY_PATH` | 签名密钥路径（默认 `/keys/private.key` / `/keys/public.key`；本地可用 `./keys/*.key` 相对路径） |
| `BILLING_LICENSE_KID` | 签发用的 kid（默认 `license-key-1`）；轮换时改新值并保留旧 kid 公钥（见下方轮换流程） |
| `ACCOUNT_CODE_LOG_ONLY` | 验证码只写日志（仅联调兜底）；**生产必须 `false`**（prod profile 已强制） |

> `docker-compose.yml` 已用 `env_file: .env` **整份透传** → 本地与容器共用一份 `.env`，无需逐项登记。
> **启动即需 `.env` 存在**（先 `cp .env.example .env` 并填值）。

## 健康检查

`scripts/ops/healthcheck.sh` 优先探测 `/actuator/health`（`management.endpoints.web.exposure.include=health,info`
并在 `docker-compose.yml` 中作为容器 healthcheck），HTTP 探针不可用时退化为端口连通性探测。
**默认端口 8000**（与 `server.port` 一致，`APP_PORT` 可覆盖）。

## License 签名密钥轮换流程（kid）

`LocalKmsService` 支持多 kid 验签：主 kid 由 `billing.license-kid` 指定（默认 `license-key-1`，对应
`billing.public-key-path`），新签发的 License header 携带该 kid；验签时按 kid 选公钥，
**旧 kid 的公钥保留即可继续校验历史 License**。

1. **生成新密钥对**（Ed25519 裸 32 字节，放入 `keys/`，该目录不入库）：
   **命令见 [`../docs/上线准备工作.md`](../docs/上线准备工作.md) §1.2（唯一权威源，含两条自检）**，生成到
   `keys/private.key.new` / `keys/public.key.new`（即把 §1.2 的输出文件名换成 `.new`），自检期望 `wc -c` 均为 32。

   > ⚠️ 只认「裸 32 字节」或 DER：`-rawin` 不是 `openssl pkey` 的合法参数（OpenSSL 3.2.4 实测报
   > `Unknown option or cipher: rawin`），PEM 也不被 `LocalKmsService` 接受（详见 §1.1）。

2. **放置新密钥**：确认新私钥落到 `PRIVATE_KEY_PATH`、新公钥落到 `PUBLIC_KEY_PATH`
   （容器场景即 `./keys`，以 `:ro` 挂载）。旧公钥另存为 `keys/public.key.old`。

3. **切换主 kid 并保留旧公钥**（保证已发出的旧 License 仍可验签）：

   ```bash
   # 新签发的 License 携带新 kid
   BILLING_LICENSE_KID=license-key-2
   # 旧 kid 的验签公钥（配置优先，其次环境变量 PUBLIC_KEY_<KID>，KID 大写、连字符转下划线）
   BILLING_PUBLIC_KEYS_LICENSE_KEY_1=/keys/public.key.old
   # 或：PUBLIC_KEY_LICENSE_KEY_1=/keys/public.key.old
   ```

4. **客户端内置新公钥**：随客户端版本发布内置 `public.key.new`；未升级的旧客户端在
   旧 License 过期前仍可用（其验签走客户端本地公钥，不受服务端轮换影响）。

5. **回滚**：把 `BILLING_LICENSE_KID` 改回 `license-key-1`、私钥路径改回旧私钥即可；
   期间用新 kid 签发的 License 会因 `license-key-2` 未配置而回退主公钥验签并告警，
   故回滚窗口内请保留 `BILLING_PUBLIC_KEYS_LICENSE_KEY_2` 指向新公钥。

> ⚠️ 轮换只改**公钥验签面**；已签发的 License token 不会重签，务必长期保留历史 kid 的公钥。
