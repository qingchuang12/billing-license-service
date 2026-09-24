# 运维脚本与数据库迁移索引（scripts/）

> 本目录是 billing-license-service 所有**运维脚本、构建/部署、数据库迁移**的统一入口。
> 项目尚未上线，脚本以「可读、可直接执行、无占位死代码」为原则。
> **上线执行手册见 [`../docs/上线准备工作.md`](../docs/上线准备工作.md)**（含渠道配置、密钥生成实测命令、上线当天清单）。
> 当前活动计划见 [`../docs/plan-7.0.md`](../docs/plan-7.0.md)。

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

### 迁移清单（V1–V7，禁止合并、禁止首次部署后修改）

| 版本 | 主题 | 关键内容 |
|---|---|---|
| V1 | 全量基线 | 14 张核心表：`products` / `orders` / `order_items` / `licenses` / `redeem_codes` / `payment_transactions` / `payments` / `checkout_sessions` / `license_events` / `payment_events` / `audit_logs` / `users` / `verification_codes` / `machine_first_seen`；历史增量序列（档位 / 订阅 / 双币种 / 账号 / 审计等，原 V2–V11）已按注释分段并入本文件 |
| V2 | 产品文案双语化（K16 延伸） | `products.name_en` / `description_en` |
| V3 | 产品权益键改名 | `feature.api_access` → `cloud_sync` |
| V4 | 账务统计索引 | `orders` / `payments` 的 `created_at` 时间范围查询索引 |
| V5 | 统一登录用户角色（plan-6.0 / A1） | `users.role`（管理员判定） |
| V6 | 管理员 MFA（plan-7.0 / B8） | `users` 增二次因子四列（详见 `V6__users_mfa.sql`） |
| V7 | 机器「已转正」标记（plan-7.0 / D3，B7 = B） | `machine_first_seen.converted_at`（NULL = 未转正；首次置位后不回收） |

> ⚠️ **迁移文件一经首次部署即被 Flyway 校验和锁定**：之后不得再编辑内容（变更请新建版本号，下一个可用 **V8**）。
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
export APP_BASE_URL='http://localhost:8000'   # K10 起无默认值，缺失即拒启
# 各支付渠道密钥见 .env.example（ALIPAY_*/WECHAT_*/STRIPE_* 等）
./scripts/deploy/run.sh
```

### 容器化部署（Docker Compose）

已提供 `Dockerfile`（多阶段构建：maven:21 构建 → temurin:21-jre 运行，非 root 用户）+
`docker-compose.yml`（app + postgres，网络隔离）。

**首次启动完整流程：**

```bash
# 1. 准备环境变量（必填：DB_PASSWORD；管理端鉴权 = 管理员 JWT，无独立 API Key 变量）
#    从模板复制，填入真实值；.env 已被 .gitignore 忽略，不会入库
cp .env.example .env
#    编辑 .env 设置强口令（或直接用下方命令生成随机值）
#    DB_PASSWORD=<随机 24 位>
#    首个管理员：首次启动前成对配置 ACCOUNT_BOOTSTRAP_ADMIN_EMAIL / ACCOUNT_BOOTSTRAP_ADMIN_PASSWORD
#    由服务自动建出（仅库中无任何 ADMIN 时生效，详见 docs/上线准备工作.md §3.3）；
#    不想用该配置则先注册普通账号、再执行 scripts/db/promote-to-admin.sql 手工提权。

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
| app 启动失败、日志提示无法解析占位符/口令为空 | 未注入 `DB_PASSWORD`（无默认值，fail-fast） | 创建 `.env` 或在 shell 中 export 后重跑 |
| app 连不上 DB、日志 `Connection refused` | app 启动快于 postgres 就绪 | `depends_on: condition: service_healthy` 已配，postgres 慢时自动等待重试 |

> 预备就绪后，`scripts/deploy/` 也提供了 `build.sh` / `up.sh` 快捷脚本（执行相同流程）。

## 环境变量速查

完整定义以 `src/main/resources/application.yml` 为准，**模板与逐项说明见 `.env.example`**。常用：

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接（`DB_PASSWORD` 无默认值，缺失即启动失败，fail-fast） |
| `ACCOUNT_JWT_SECRET` / `ACCOUNT_CODE_PEPPER` | 用户令牌签名密钥（≥32B）/ 验证码哈希 pepper（生产必配）；管理端鉴权复用同一账号体系（管理员 JWT） |
| `ACCOUNT_MFA_KEY` | 二次因子主密钥（≥32B，**无默认值，缺失即拒启**）：派生半认证票据签名密钥与 TOTP 密钥加密密钥（B8）。轮换前须先跑 `db/reset-admin-mfa.sql` |
| `MAIL_PASSWORD` | SMTP 口令（无默认值）；`MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` 有默认值 |
| `APP_BASE_URL` | 对外基址，用于拼接各渠道回调/回跳地址（**无默认值，缺失即拒启**） |
| `PAYMENT_ENABLED_CHANNELS` | 启用的支付渠道（逗号分隔；留空=按各渠道配置齐全度自动启用） |
| `SPRING_PROFILES_ACTIVE` | `docker`（默认，schema 护栏）/ `prod`（关 Swagger、强制真发邮件）/ `prod,docker` |
| `BILLING_TRUST_XFF` | 置于可信反代之后时置 `true`，否则限流会把所有用户当成同一 IP |
| `BILLING_REFUND_MIN_AMOUNT` | 自助退款可退下限（订单币种，默认 `1.00`）；折算额低于此值不开放自助退款 |
| `BILLING_REFUND_USER_MAX` / `BILLING_REFUND_USER_WINDOW_MINUTES` | 自助退款频控（同一用户，默认 5 次 / 60 分钟） |
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
