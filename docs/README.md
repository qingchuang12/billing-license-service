# Billing & License Service

统一的计费与许可证管理服务，基于 **Java 21 + Spring Boot 4.0.6**（JPA/Hibernate + PostgreSQL + Flyway + 5 家支付渠道 + KMS）构建，为桌面端 exe 工具提供 license 签发、兑换、换机与计费/退款后端。

> **上线执行手册见 [`上线准备工作.md`](./上线准备工作.md)**（阻断项清单 / 密钥生成实测命令 / 渠道设置 / 上线当天清单）。
> 生产就绪度与已知风险见活动 plan [`plan-7.0.md`](./plan-7.0.md)（授权硬化与跨仓激活契约已完成；**上线闸门 T1–T4 仍待外部资源**，渠道沙箱实测与实机走查见该 plan 末尾「登记表」）。

## 功能特性

- **三档收费体系**：Pro 买断（一次性付费）/ Pro Plus 高级版（一次性付费，权益更高）/ 订阅制（托管 Paddle / Stripe Billing，自动续期与取消联动 License）。
- **双币种定价**：每个 SKU 维护 CNY 与 USD 两档价格，下单按区域（国内/国际）取对应金额与币种。
- **支付集成（5 家渠道）**：支付宝、微信支付（国内）；Stripe、Paddle、PayPal（国际）。支持创建支付、Webhook 回调发货、查询状态、**管理员全额退款**与**用户端自助退款**（按 License 剩余有效期折算，可能构成部分退款）。
- **许可证签发与校验**：基于 JWS 的签名许可证，payload 含 `lic/cid/sku/plan/feat/mid/oid/iat/exp`，客户端可离线校验机器码绑定与档位权益。
- **兑换码系统**：密码学安全随机（`SecureRandom`）生成、兑换、管理端批量生成与吊销。
- **多算法支持**：Ed25519（JWS `EdDSA`，默认）、ECDSA（`ES256`）、RSA（`RS256`）；可用集合（纯本地 `LocalKmsService` 支持）：`EdDSA` / `ES256` / `RS256`（详见 `application.yml` 的 `billing.signature-algorithm` 注释）。
- **KMS 集成**：纯本地文件方案（`LocalKmsService`），密钥经文件挂载，无云 KMS 依赖（GCP / Azure / 阿里云 均不接入）。
- **邮件通知**：支付成功/失败与 License 签发通知（`service/notification/EmailNotificationService`，`@Async`；未配置 SMTP 时自动跳过，不阻塞主流程）。
- **安全与可观测**：管理员 JWT 鉴权（特权端点 `Authorization: Bearer <JWT>` + `ROLE_ADMIN`；管理员账号经 `POST /api/account/login` 登录后由 `JwtAuthFilter` 注入 `ROLE_ADMIN`）、CORS 白名单、并发限流（内存淘汰 + XFF 防伪造）、操作审计日志（`@Audit` + `AuditAspect` 异步独立事务落库，actor 取自 `SecurityContext` 的 `userId`）、`/actuator/health` 健康检查（k8s 探针放行）。

## 快速开始

### 1. 生成密钥对（用于 License 签发，KMS=local 时挂载）

> ⚠️ **格式硬约束**：`LocalKmsService` 只接受**裸 32 字节**（Ed25519）或 **DER** 字节，
> **不接受 PEM**（`-----BEGIN…` 会加载失败）。EC/RSA 也必须导出 DER，不能留 PEM。

**生成命令 + 两条自检见 [`上线准备工作.md`](./上线准备工作.md) §1.2（唯一权威源，2026-09-18 在 OpenSSL 3.2.4 实测通过）** —— 本文档不再维护副本，避免多处漂移。

### 2. 配置环境变量

> **推荐做法（2026-09-18 起）：`cp .env.example .env` 后填值** —— `application.yml` 会加载 `.env`，
> 本地与 docker compose 共用同一份配置源。下面是等价的 export 写法：

```bash
# License 签名密钥（KMS=local）
export PRIVATE_KEY_PATH=/path/to/private.key
export PUBLIC_KEY_PATH=/path/to/public.key

# 数据库（DB_PASSWORD 无默认值，缺失即拒启）
export DB_USERNAME=postgres
export DB_PASSWORD='<强口令>'

# 账号体系：令牌签名密钥（≥32B，缺失即启动失败）/ 验证码 pepper（生产必配）/ 二次因子主密钥（≥32B，缺失即启动失败）
export ACCOUNT_JWT_SECRET="$(openssl rand -base64 48)"
export ACCOUNT_CODE_PEPPER="$(openssl rand -hex 16)"
export ACCOUNT_MFA_KEY="$(openssl rand -base64 48)"

# 邮箱口令（无默认值）与应用对外基址（无默认值，缺失即拒启）
export MAIL_PASSWORD='<邮箱口令>'
export APP_BASE_URL=http://localhost:8000

# 支付渠道密钥（按需配置，未配置渠道不启用；键名见 .env.example 与 docs/上线准备工作.md §2）
export STRIPE_API_KEY=sk_live_xxx
export STRIPE_WEBHOOK_SECRET=whsec_xxx
# export ALIPAY_APP_ID=...  export ALIPAY_PRIVATE_KEY=...  export ALIPAY_GATEWAY_URL=...
# export WECHAT_APP_ID=...  export WECHAT_MCH_ID=...  export WECHAT_API_KEY=...
# export PADDLE_API_KEY=...  export PAYPAL_CLIENT_ID=...  export PAYPAL_CLIENT_SECRET=...

```

> 配置优先级：环境变量 / `.env` > `application.yml`。生产部署务必替换所有凭据，并以 `SPRING_PROFILES_ACTIVE=prod` 启动
> （关闭 Swagger、强制真发邮件；见 `application-prod.yml`），同时启用 PostgreSQL + Flyway（schema 归迁移脚本管理，`ddl-auto: validate`）。

### 3. 启动服务

```bash
# 本地运行（需 JDK 21）
mvn spring-boot:run

# 或打包后运行
mvn clean package
java -jar target/billing-license-service-*.jar
```

### 容器化部署（Docker Compose）

```bash
# 1. 准备环境变量（复制模板，填入真实值；.env 已被 .gitignore 忽略，不入库）
cp .env.example .env
#   编辑 .env：设置 DB_PASSWORD 与账号令牌签名密钥 ACCOUNT_JWT_SECRET

# 2. 准备签名密钥（挂载到 ./keys，容器只读读取；KMS=local 时必须）
mkdir -p keys
openssl genrsa -out keys/private.key 2048
openssl rsa -in keys/private.key -pubout -out keys/public.key

# 3. 构建并启动
./scripts/deploy/build.sh
./scripts/deploy/up.sh
# 等效命令：docker compose up -d --build

# 查看状态与日志
docker compose ps
docker compose logs -f app
```

> 容器内数据源由 `DB_URL=jdbc:postgresql://postgres:5432/billing_db` 指向同网络 `postgres` 服务（库名与 `POSTGRES_DB` 一致）；`app` 经 `depends_on: condition: service_healthy` 等数据库就绪后启动。健康检查为 `/actuator/health`（H13 已完成，HTTP 探针）。

## API 接口

> **路径约定（D1，2026-09-14）**：端点前缀统一为 `/api/**`，**不再使用 `/api/v1`**。

> **鉴权为四档（v2.10 增设用户档；B8 增设半认证档；X-API-Key 特权档已于 A12 / 2026-09-23 移除，统一为管理员 JWT）**：
> 1. **公开**：`/api/checkout/**`、`/api/redeem/redeem`、`/api/licenses/verify/**`、`POST /api/licenses/activate`、`/api/webhooks/**`、`/v3/api-docs`，以及账号的 `POST /api/account/verification-code`、`register`、`login`、`password/reset`；
> 2. **半认证（B8 新增）**：`POST /api/account/mfa/challenge`、`POST /api/account/mfa/verify`——框架层同为 `permitAll`，但**方法内强制校验一次性登录票据**（独立密钥签名 + `tokenVersion` 比对 + 300 秒有效期）。之所以不能要求 `ROLE_USER`：调用者此时尚未持有访问令牌，要求令牌会形成死锁（与登录端点同坑，故 `SecurityConfig` 中这两条也须声明在 `/api/account/**` 之前）；
> 3. **用户（v2.10 新增）**：`Authorization: Bearer <JWT>` → `ROLE_USER`，适用于 `POST /api/account/logout`、`GET /api/account/me`、`POST /api/account/password/change`，以及「我的资产」五端点（`/api/account/licenses|subscriptions|orders`、`POST /api/account/orders/{orderNumber}/refund`、`POST /api/account/licenses/{licenseKey}/unbind`）。管理员兼授 `ROLE_USER`（B1），故同样可用；
> 4. **管理（A12 起替代原 X-API-Key 特权档；B8 增设二次因子绑定入口）**：`/api/admin/**` 全部管理动作携带 `Authorization: Bearer <JWT>`，且持有账号的 `role=ADMIN`（即 `ROLE_ADMIN`）。管理员与普通用户共用同一套账号体系与登录端点 `POST /api/account/login`，登录后 `JwtAuthFilter` 按 `users.role` 动态注入角色——**无独立 API Key、无独立密钥配置**。二次因子的绑定入口（`/api/admin/mfa/**` 四个端点）挂在 `admin` 前缀下，由框架统一保证 `ROLE_ADMIN`，普通用户不开放。
>
> 历史注记：`X-Admin-API-Key`（`AdminController` 自校验）与 `X-API-Key`（`ApiKeyAuthFilter` 机读通道）均已删除（A12）。公开端点依赖签名 License + 限流保护。
> **权限域严格隔离**：用户令牌（`ROLE_USER`）不能访问 `/api/admin/**`；管理动作依赖「当前管理员用户」上下文，actor 取自 `SecurityContext` 的 `userId`（审计日志可追溯操作人）。

### 端点总览（共 42 个：公开 16 + 半认证 2 + 管理端 16 + 账号需登录 8）

| 分组 | 方法与路径 | 鉴权 |
|---|---|---|
| 收银台 | `POST /api/checkout/create`（可选 `provider`，一步下单） | 公开 |
| | `POST /api/checkout/{checkoutId}/select-provider` | 公开 |
| | `GET /api/checkout/{checkoutId}/status` | 公开 |
| License | `GET /api/licenses/verify/{licenseKey}` | 公开（失效件返回 400，查失效件用管理端接口） |
| | `POST /api/licenses/activate` | 公开·**可选鉴权**：凭证为兑换码（`RC-` 前缀）时匿名可调；为许可证密钥时**必须登录且归属本人**（判定在服务端，见下「凭证激活」） |
| | `POST /api/licenses/report-binding` | 公开（客户端兑换/激活后**启动时自动上报机器码**完成补绑；凭 `signedToken` 验签，**无需登录**） |
| 兑换码 | `POST /api/redeem/redeem` | 公开 |
| 支付回调 | `POST /api/webhooks/{alipay \| wechat \| stripe \| paddle \| paypal}` | 公开（各渠道自行验签） |
| 订单 | `GET /api/admin/orders?status=&orderNumber=&orderId=` | 管理员 JWT |
| | `POST /api/admin/orders/{orderNumber}/issue` | 管理员 JWT |
| | `POST /api/admin/orders/{orderNumber}/refund` | 管理员 JWT |
| License | `GET /api/admin/licenses?customerEmail=&orderNumber=&status=` | 管理员 JWT |
| | `GET /api/admin/licenses/{licenseKey}` | 管理员 JWT |
| | `POST /api/admin/licenses/{licenseKey}/revoke` | 管理员 JWT |
| | `POST /api/admin/licenses/{licenseKey}/reissue` | 管理员 JWT（`force=true` 可越重发上限；`newMachineId` 可空） |
| | `POST /api/admin/licenses/{licenseKey}/unbind` | 管理员 JWT（只清设备绑定、保留授权） |
| 兑换码 | `POST /api/admin/redeem-codes/generate?productSku=&count=` | 管理员 JWT |
| | `GET /api/admin/redeem-codes?productSku=&status=` | 管理员 JWT |
| | `POST /api/admin/redeem-codes/revoke/{code}` | 管理员 JWT |
| 运维 | `GET /api/admin/payment-channels` | 管理员 JWT |
| 二次因子 | `GET /api/admin/mfa/status` | 管理员 JWT（绑定状态，不回显密钥） |
| | `POST /api/admin/mfa/enroll` | 管理员 JWT（须当前密码，生成密钥） |
| | `POST /api/admin/mfa/activate` | 管理员 JWT（认证器动态码激活） |
| | `POST /api/admin/mfa/unbind` | 管理员 JWT（须密码 + 动态码/邮箱码） |
| 账号 | `POST /api/account/verification-code` | 公开 |
| | `POST /api/account/register` | 公开 |
| | `POST /api/account/login` | 公开 |
| | `POST /api/account/password/reset` | 公开 |
| | `POST /api/account/mfa/challenge` | 半认证（凭一次性登录票据，无访问令牌） |
| | `POST /api/account/mfa/verify` | 半认证（凭一次性登录票据，换回访问令牌） |
| | `POST /api/account/logout` | 用户令牌 |
| | `GET /api/account/me` | 用户令牌 |
| | `POST /api/account/password/change` | 用户令牌 |
| 我的资产 | `GET /api/account/licenses` | 用户令牌（本人 License，密钥完整回显、不含 signedToken） |
| | `GET /api/account/subscriptions` | 用户令牌 |
| | `GET /api/account/orders` | 用户令牌（另回填 `refundable` / `refundableAmount`） |
| | `POST /api/account/orders/{orderNumber}/refund` | 用户令牌（按剩余有效期折算，可能构成部分退款） |
| | `POST /api/account/licenses/{licenseKey}/unbind` | 用户令牌（释放本人 License 的设备绑定，以便换机后重新激活；只解绑不吊销） |

> **接口合并简化（主题 I，2026-09-14）**：相比改造前的 24 个端点——**删除 6 个重复入口**（订单 by-id / by-number、订单按状态、订单下 License 列表、按客户查 License、`/api/licenses/issue`），**新增 3 个**（参数化 License 查询、订单签发归口、兑换码导出），**迁移 2 类**（签发与兑换码生成/撤销收进 `/api/admin/**`）；收银台支持带 `provider` 一步下单。

> **下单入口唯一（D4，2026-09-14）**：原 `POST /api/orders` 创建入口已删除（与收银台职责重叠，且其计价会产出「币种 CNY + 金额 USD」的资损级不一致），下单统一走 `POST /api/checkout/create`。

### 账号体系（v2.10，2026-09-15）

终端用户账号：注册 / 登录 / 登出 / 当前用户 / 改密 / 找回密码 / 邮箱验证码。

**令牌**：JWT（HS256），签名密钥来自 `account.jwt-secret`（**不设默认值，缺失即启动失败**）；有效期默认 7 天（`account.token-ttl-hours`）。
登出、改密、重置密码都会使 `users.token_version` +1，**该用户所有已签发令牌立即失效**。令牌只经 `Authorization: Bearer <token>` 传输，不落 Cookie（服务端无会话）。

**两阶段登录（B8，仅账号已开启二次因子时）**：密码校验通过后**不签发访问令牌**，只回 `mfaRequired: true` + 一枚 300 秒的 `mfaTicket`（`user` 为 `null`——未过第二因子前不泄露账号信息）。凭该票据调 `POST /api/account/mfa/verify` 换取正式令牌。票据由**独立派生密钥**签名，与访问令牌密钥隔离，故票据**不可能**被当成访问令牌使用。

```bash
# ① 发送邮箱验证码（purpose=REGISTER | RESET_PASSWORD）
curl -X POST http://localhost:8000/api/account/verification-code \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","purpose":"REGISTER"}'

# ② 注册（emailCode 必填；成功即返回令牌，无需再登录）
curl -X POST http://localhost:8000/api/account/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Passw0rd2026","emailCode":"483920"}'

# ③ 登录
curl -X POST http://localhost:8000/api/account/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Passw0rd2026"}'
# → data: {"accessToken":"eyJhbGciOi...","expiresIn":604800,"user":{...}}

# ④ 当前用户 / ⑤ 登出 / ⑥ 改密（均需登录）
curl http://localhost:8000/api/account/me -H "Authorization: Bearer <token>"
curl -X POST http://localhost:8000/api/account/logout -H "Authorization: Bearer <token>"
curl -X POST http://localhost:8000/api/account/password/change -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"Passw0rd2026","newPassword":"NewPassw0rd2026"}'

# ⑦ 找回密码（公开；凭验证码重置，不需要旧密码）
curl -X POST http://localhost:8000/api/account/password/reset \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","code":"483920","newPassword":"NewPassw0rd2026"}'
```

### 我的资产与自助退款（U1/U2）

登录后查看本人名下的许可证（`licenseKey` 完整回显，不含离线验签 `signedToken`）、订阅与订单；
订单区支持**自助退款**，口径如下：

| 项 | 口径 |
|---|---|
| 可退额 | **全周期线性折算**：实付额 × 剩余天数 ÷ 总天数（`max(expiresAt) − min(issuedAt)` 为总天数），按币种 HALF_UP 保留 2 位 |
| 可退窗口 | **即 License 有效期**——权益自然到期即不可退（故不设「支付后 N 天」的申请窗口） |
| 归属校验 | 非本人订单按 `ORDER_NOT_FOUND` 返回（不泄露他人订单存在性） |
| 一单一次 | 已退款/部分退款订单不可再退（`ALREADY_REFUNDED`） |
| License 处置 | 渠道退款成功即**吊销该订单全部 License**（避免「钱退了还能用」） |
| 渠道降级 | 渠道未受理部分金额时**自动改发全额退**，并留痕 metadata `refundDegraded` |
| 频控 / 下限 | 5 次/小时（`billing.refund.user-refund-max`）；折算额低于 `billing.refund.min-amount`（默认 1.00）不开放 |
| 暂不支持 | **订阅类订单**（渠道侧无「取消订阅」调用能力，退了不取消会持续扣款），返回 `NOT_REFUNDABLE` 引导人工 |

```bash
# 我的订单（含可退标记与折算后可退额，供前端决定是否显示退款入口）
curl http://localhost:8000/api/account/orders -H "Authorization: Bearer <token>"

# 申请退款（按剩余有效期折算；成功即吊销该订单全部 License）
curl -X POST http://localhost:8000/api/account/orders/ORD20260914224937123/refund \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"reason":"买错了版本"}'
# → data: {"orderNumber":"ORD20260914224937123","refundedAmount":91.20,
#          "fullRefund":false,"paymentStatus":"PARTIALLY_REFUNDED"}
```

> 渠道退款失败时绝不在本地谎报成功：订单标记 `REFUND_FAILED`（`paymentStatus` 保持 `PAID`）并返回
> `REFUND_FAILED` 交运营到渠道控制台手动处理（H4 资损防护）。金额在**渠道调用**与**退款流水**两处一致。

**限流**（复用 `RateLimitService`，阈值见 `application.yml` 的 `account.risk`）：

| 维度 | 阈值 |
|---|---|
| 验证码发送 | 邮箱冷却 60 秒；邮箱 5 次/60 分钟；IP 10 次/60 分钟 |
| 注册 | IP 5 次/60 分钟；邮箱 3 次/60 分钟 |
| 登录失败 | 账号 5 次 → 锁定 15 分钟（**落库，跨重启有效**）；IP 20 次/10 分钟 |
| 改密 | 3 次/60 分钟（按用户） |
| 自助退款 | 5 次/60 分钟（按用户；`billing.refund.user-refund-max`） |
| 找回密码 | 邮箱 5 次/60 分钟；IP 10 次/60 分钟 |
| 验证码校验 | 单码连续错 5 次即作废 |

**新增错误码**：`EMAIL_ALREADY_REGISTERED`、`EMAIL_CODE_REQUIRED`、`INVALID_CREDENTIALS`（邮箱/密码错误统一文案，防账号枚举）、`ACCOUNT_LOCKED`、`ACCOUNT_DISABLED`、`ACCOUNT_NOT_FOUND`、`LOGIN_IP_LIMIT`、`REGISTER_LIMIT`、`RESET_LIMIT`、`CHANGE_PASSWORD_LIMIT`、`CODE_SEND_TOO_FREQUENT`、`CODE_INVALID` / `CODE_EXPIRED` / `CODE_TOO_MANY_ATTEMPTS`、`PASSWORD_POLICY_VIOLATION`、`OLD_PASSWORD_MISMATCH`、`TOKEN_INVALID`、`VALIDATION_ERROR`。

### 二次因子（MFA，B8）

管理员可**自助开启**二次因子：主因子为认证器 App（TOTP），兜底为邮箱验证码。**默认关闭**，不影响既有账号；启用后该账号登录即走两阶段。

| 项 | 口径 |
|---|---|
| 主因子 | TOTP（RFC 6238：HMAC-SHA1、6 位、30 秒步长、容错 ±1 步）；密钥 20 随机字节 → Base32。**手写实现、零新依赖**（离线构建仓无 OTP 类库） |
| 兜底 | 邮箱验证码（用途码 `LOGIN_MFA`，仅半认证端点可签发）；SMTP 不可用时以 `account.mfa.email-fallback-enabled=false` 关闭 |
| 密钥存储 | `users.mfa_secret_cipher` 为 **AES-256-GCM 密文**（密钥由 `account.mfa-key` 经 HMAC-SHA256 派生，与票据签名密钥同源但隔离）。**绝不存明文**——TOTP 密钥泄漏等价于对方可永久生成有效动态码 |
| 防重放 | 每个动态码只用一次（`users.mfa_last_used_step`，RFC 6238 §5.2）：已用过的码即使仍在容错窗口内也拒绝 |
| 绑定入口 | 管理台「安全设置」分区。`enroll` / `unbind` 均须**重新输入当前密码**——防 session 被劫持后静默换绑攻击者认证器 |
| 解绑 | 须**密码 + 动态码（或邮箱码）**双验；解绑刻意**不**递增 `tokenVersion`（属保护等级下降，非账号异常） |
| 限流 | 校验失败 5 次/10 分钟（`account.mfa.verify-fail-max`）；邮箱码发送沿用账号验证码的冷却与窗口阈值 |
| 自救路径 | 认证器丢失且邮箱不可用 → 运维执行 `scripts/db/reset-admin-mfa.sql`（**非 Flyway 迁移，须手动执行**） |
| 不引二维码库 | 页面零 CDN 依赖，故 `enroll` 只返回 Base32 密钥 + `otpauth://` URI，用户在认证器 App 内**手动录入密钥** |

```bash
# ① 生成密钥（须当前密码；secret 与 otpauthUri 仅此一次回显，服务端不落原值）
curl -X POST http://localhost:8000/api/admin/mfa/enroll \
  -H "Authorization: Bearer <admin-token>" -H "Content-Type: application/json" \
  -d '{"password":"Passw0rd2026"}'
# → data: {"secret":"JBSWY3DPEHPK3PXP","otpauthUri":"otpauth://totp/...","activated":false}

# ② 录入认证器后，用 App 显示的动态码激活
curl -X POST http://localhost:8000/api/admin/mfa/activate \
  -H "Authorization: Bearer <admin-token>" -H "Content-Type: application/json" \
  -d '{"code":"123456"}'

# ③ 此后该账号登录变两阶段：密码不直接换令牌，只回票据
curl -X POST http://localhost:8000/api/account/login \
  -H "Content-Type: application/json" -d '{"email":"admin@example.com","password":"Passw0rd2026"}'
# → data: {"accessToken":null,"expiresIn":0,"user":null,
#          "mfaRequired":true,"mfaTicket":"eyJ...","mfaMethods":["TOTP","EMAIL"]}

# ④ 用动态码换正式令牌（认证器不可用时可先调 /api/account/mfa/challenge 发邮箱码）
curl -X POST http://localhost:8000/api/account/mfa/verify \
  -H "Content-Type: application/json" -d '{"ticket":"eyJ...","code":"123456"}'
# → data: {"accessToken":"eyJ...","expiresIn":604800,"user":{...,"role":"ADMIN"}}
```

**MFA 错误码**：`MFA_TICKET_INVALID`（票据无效 / 过期 / 账号信息已变更）、`MFA_NOT_ENABLED`、`MFA_NOT_ENROLLED`、`MFA_ALREADY_ENABLED`、`MFA_CODE_INVALID`（动态码与邮箱码均不匹配）、`MFA_VERIFY_LIMIT`、`MFA_EMAIL_FALLBACK_DISABLED`、`MFA_SECRET_UNREADABLE`（密文损坏或 `ACCOUNT_MFA_KEY` 已轮换 → 走自救脚本）。

> ⚠️ **升级前必配 `ACCOUNT_MFA_KEY`**（≥32 字节，`openssl rand -base64 48`）。与 `account.jwt-secret` 同为 fail-fast：**缺失即启动失败**。轮换该密钥会使既有绑定全部解不开，须先用自救脚本重置绑定再轮换。

> ⚠️ **邮件通道**：SMTP 未配置时验证码邮件会被**静默跳过**（仅 warn 日志），注册与找回密码将不可用且难以察觉。
> 联调阶段请设置 `ACCOUNT_CODE_LOG_ONLY=true` 把验证码输出到日志；**上线前必须配置真实 SMTP 并实测可达**。
>
> ⚠️ **客户标识：对外邮箱 / 内部 UUID（决策 1，E1–E5）**：内部仍以 `users.id`(UUID) 为主键并承载 `orders.customer_id`；**对外统一用邮箱**——下单 `POST /api/checkout/create` 与兑换 `POST /api/redeem/redeem` 均传 `customerEmail`；**未注册邮箱自动建访客账户**（随机不可登录密码、`emailVerified=false`，可走 `POST /api/account/password/reset` 认领），服务端经 `CustomerIdentityService.resolveOrCreate` 解析为该账户 `userId` 落库。管理端 `GET /api/admin/licenses?customerEmail=` 亦按邮箱查（只读解析，未注册邮箱 → 无匹配）。历史匿名订单（旧随机 `customerId`）不在本方案回溯范围内。

### 响应结构（统一响应壳）

除 `/api/webhooks/**`（渠道原始报文，必须原样返回）外，所有响应由 `ApiResponseAdvice` 包为：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": null,
  "data": {},
  "traceId": "…",
  "timestamp": "2026-09-14T17:00:00"
}
```

失败时 `success=false`、`code` 为业务错误码、`message` 为可读描述、`traceId` 与响应头 `X-Trace-Id` 一致。常见业务错误码：`PRODUCT_NOT_FOUND`、`PRODUCT_INACTIVE`、`PRICE_NOT_CONFIGURED`、`NO_PAYMENT_METHOD`、`PAYMENT_CREATE_FAILED`、`EMAIL_PURCHASE_LIMIT`、`CREDENTIAL_NOT_FOUND`、`LICENSE_NOT_FOUND`、`LICENSE_INVALID`、`LICENSE_EXPIRED`、`LICENSE_REVOKED`、`LICENSE_REISSUE_LIMIT`、`INVALID_COUNT`、`COUNT_EXCEED_LIMIT`、`INVALID_ORDER_STATUS`、`REFUND_FAILED`、`UNSUPPORTED_CURRENCY`。`/v3/api-docs` 的响应 schema 已同步该壳（H-C3），可直接据此生成客户端 SDK。

### 状态枚举（以代码为准）

| 对象 | 取值 |
|---|---|
| 订单状态 `Order.OrderStatus` | `PENDING` / `CONFIRMED` / `PROCESSING` / `COMPLETED` / `CANCELLED` / `REFUNDED` / `REFUND_FAILED` / `PAID` |
| 订单支付状态 `Order.PaymentStatus` | `UNPAID` / `PAID` / `PARTIALLY_REFUNDED` / `REFUNDED` / `FAILED` |
| License `License.LicenseStatus` | `ACTIVE` / `EXPIRED` / `REVOKED`（退款或违规，管理端全额退或用户端自助退成功后一律吊销）/ `REISSUED`（换机重发后旧证退出，新证经 `reissuedFrom` 指回） |
| 兑换码 `RedeemCode.RedeemCodeStatus` | `UNUSED` / `USED` / `EXPIRED` / `REVOKED` |
| 收银台 `CheckoutSession.Status` | `CREATED` / `PENDING` / `PAID` / `FAILED` / `EXPIRED` / `CANCELED` |
| 订阅 `Subscription.SubscriptionStatus` | `PENDING` / `ACTIVE` / `PAST_DUE` / `CANCELED` / `EXPIRED` |
| 渠道支付 `PaymentStatus` | `PENDING` / `SUCCESS` / `FAILED` / `REFUNDED` / `CANCELLED` / `UNKNOWN` |

### 收银台（下单唯一入口）

```bash
# 1) 创建收银台会话（公开）：返回可用支付方式与应付金额
#    区域判定：currency=CNY 或 locale=zh-CN → 国内（取 price_cny/CNY），否则国际（取 price_usd/USD）
#    可用 SKU（V4 种子）：pro-buyout / pro-plus-buyout / pro-subscription / pro-plus-subscription
#    I7：请求体带 provider 可**一步下单**——直接创建支付并返回二维码/跳转链接，
#        免去第 2 步；不带 provider 则先返回 paymentMethods 列表，由用户选择后再走第 2 步。
curl -X POST http://localhost:8000/api/checkout/create \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "pro-buyout",
    "currency": "CNY",
    "locale": "zh-CN",
    "machineId": "ABCD-1234-EFGH-5678",
    "email": "buyer@example.com",
    "returnUrl": "https://pay.example.com/success",
    "cancelUrl": "https://pay.example.com/cancel",
    "provider": "alipay"
  }'

# 2) 选择支付方式（公开）：返回二维码 / 跳转链接
curl -X POST http://localhost:8000/api/checkout/{checkoutId}/select-provider \
  -H "Content-Type: application/json" \
  -d '{"provider": "alipay"}'

# 3) 轮询支付状态（公开）：渠道确认已支付后返回 license / redeemCode
curl http://localhost:8000/api/checkout/{checkoutId}/status
```

### 订单查询

> **鉴权方式（A12 起）**：所有 `/api/admin/**` 管理端点统一用**管理员 JWT**——先以 `role=ADMIN` 的账号 `POST /api/account/login` 拿 `accessToken`，再带 `Authorization: Bearer <token>`。下文示例以变量 `$ADMIN_TOKEN` 指代该令牌，不再使用 `X-API-Key`。

```bash
# 0) 管理员登录，取令牌（下文 $ADMIN_TOKEN 即 data.accessToken）
curl -s -X POST http://localhost:8000/api/account/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"<管理员口令>"}' \
  | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'   # 仅演示：实际请安全保存令牌

# 按订单 ID（I2：原 /api/orders/{orderId} 已收敛到此）
curl "http://localhost:8000/api/admin/orders?orderId={orderId}" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 按业务订单号（原 /api/orders/number/{orderNumber}）
curl "http://localhost:8000/api/admin/orders?orderNumber=ORD-20260914-0001" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 按状态过滤（原 /api/admin/orders/status/{status}）
curl "http://localhost:8000/api/admin/orders?status=PAID" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 全部订单
curl "http://localhost:8000/api/admin/orders" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### 许可证管理

```bash
# 为已支付订单签发许可证（I4：由原 /api/licenses/issue/{orderId} 归口到管理端；幂等）
curl -X POST http://localhost:8000/api/admin/orders/{orderNumber}/issue \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 验证许可证（公开，离线校验用）
curl http://localhost:8000/api/licenses/verify/{licenseKey}

# 查询 License（I3：一个端点替代「按客户查询」与「订单下 License 列表」，含失效件）
curl "http://localhost:8000/api/admin/licenses?customerEmail=buyer@example.com" -H "Authorization: Bearer $ADMIN_TOKEN"
curl "http://localhost:8000/api/admin/licenses?orderNumber=ORD-20260914-0001" -H "Authorization: Bearer $ADMIN_TOKEN"
curl "http://localhost:8000/api/admin/licenses?status=REISSUED" -H "Authorization: Bearer $ADMIN_TOKEN"

# 查询 License 详情（含失效件；verify 对失效件返回 400，查失效件用本接口）
curl "http://localhost:8000/api/admin/licenses/{licenseKey}" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 作废许可证（终态不可逆；D2 起客户端自吊销端点已删除，本端点即吊销唯一入口）
curl -X POST "http://localhost:8000/api/admin/licenses/{licenseKey}/revoke?reason=用户申请退款" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 解绑设备（plan-7.0 主体三）：只清机器码、保留授权，用户可在新机重新激活
# 适用场景：用户换机后旧令牌随旧机器失效、登不进账号页自助解绑时，由客服代劳
curl -X POST "http://localhost:8000/api/admin/licenses/{licenseKey}/unbind?reason=用户换机" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 换机重发（管理端）：原证置 REISSUED，并签发**新 licenseKey**
# newMachineId 可留空 = 只失效重发、不绑设备；force=true 越过重发次数上限（人工处置用）
curl -X POST "http://localhost:8000/api/admin/licenses/{licenseKey}/reissue?newMachineId=NEW-MACHINE-ID&reason=changed_pc&force=true" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# → data 含新证 licenseKey；重发**不发邮件**，新密钥同时可在该用户账号页看到
```

> **三种处置手段语义不同，不可混用（plan-7.0 主体三）**：
>
> | 手段 | 效果 | 可逆性 |
> |---|---|---|
> | `unbind` **解绑** | 只清 `machineCode`；授权状态、`customerId`、`licenseKey` 均不变 | ✅ 用户在新机重新激活即绑回 |
> | `reissue` **失效重发** | 旧证置 `REISSUED`，签发**新 `licenseKey`**（继承客户 / 产品 / 到期时间）；会写 `reissuedFrom` 指回旧证 | ⚠️ 旧 key 永久退出使用，不可撤回 |
> | `revoke` **作废** | 置 `REVOKED` + `revokedAt`，并写 `license_events` 留痕（detail 含原因） | ❌ 终态不可逆 |
>
> - **作废必留痕**：管理端作废与**退款吊销**（`refundOrder` 成功后的批量作废）都写 `license_events`（`REVOKED`），事件统一由 `LicenseService#recordLicenseEvent` 产出；「退款吊销」的 detail 为 `Revoked by refund. order=<订单号>`。
>
> - **`revoke` 之后不能再 `reissue`**（服务端显式拒绝 `LICENSE_REVOKED`）——故「失效并重新生成 key」只能走 `reissue`。
> - **重发次数上限**：`billing.risk.license-reissue-max`（默认 5）按原 key 累计 `REISSUED` 事件计数；达上限默认抛 `LICENSE_REISSUE_LIMIT`，管理端可显式 `force=true` 越过——**越限会在事件 detail 标注 `forced over limit=N`**，使审计能区分常规重发与人工越限。
> - **解绑同样拒绝已作废件**（`LICENSE_REVOKED`，与账号侧 `unbindByOwner` 同码，避免同一语义在两个入口分叉）；本就未绑定时**幂等返回 200**，不落库、不重复留痕。
> - 管理台「**License 处置**」分区即上述三动作的界面入口（按密钥精确查 / 按邮箱列表查）。

### 凭证激活（统一入口，plan-7.0 方案 A）

**为什么合并**：此前客户端要认四条「绑定设备 + 拿签名令牌」的路径（购买直签、兑换码、在线校验、激活）。
现收敛为**单一 `POST /api/licenses/activate`**，服务端按凭证格式自动识别：

| 凭证形态 | 分支 | 鉴权 | 归属凭证 |
|---|---|---|---|
| `RC-` 前缀（如 `RC-8F3C-1D2E-9A4B`） | 整段委托 `RedeemCodeService`（沿用 B9 幂等 + IP 风控） | **匿名可调**（不破坏离线发货 / 礼品场景） | 持码即持有人 |
| 其余（`XXXX-XXXX-XXXX-XXXX`） | 校验登录态 → 归属 → 幂等 → 换机边界 | **必须登录** | `Authorization: Bearer <JWT>` |

> ⚠️ **安全红线**：许可证密钥分支**绝不**允许匿名绑定。账号页会明文展示 `licenseKey`，
> 若「仅凭 key 即可绑」成立，明文泄漏即等于「谁先抢到算谁的」。归属校验取登录用户 id
> （`License.customerId == users.id`）后，明文 key **单独**泄漏不足以完成绑定。

**幂等与换机边界**：已绑定**同一机器** → 直接返回既有 `signedToken`，**不二次签发**；
已绑定**其他机器** → `MACHINE_MISMATCH`（**不自动改绑**），用户须先在账号页
`POST /api/account/licenses/{licenseKey}/unbind` 释放绑定，再激活到新设备。

```bash
# ① 许可证密钥激活（须登录；未登录 → LOGIN_REQUIRED）
curl -X POST http://localhost:8000/api/licenses/activate \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <USER_TOKEN>" \
  -d '{"credential": "2F8A-7C31-9D04-B5E6", "machineId": "ABCD-1234-EFGH-5678"}'

# ② 兑换码激活（匿名，与 POST /api/redeem/redeem 等价；已登录时以登录账号邮箱为准）
curl -X POST http://localhost:8000/api/licenses/activate \
  -H "Content-Type: application/json" \
  -d '{"credential": "RC-8F3C-1D2E-9A4B", "machineId": "ABCD-1234-EFGH-5678",
       "customerEmail": "buyer@example.com"}'

# ③ 换机恢复路径：先释放设备绑定，再到新设备执行 ①
curl -X POST "http://localhost:8000/api/account/licenses/2F8A-7C31-9D04-B5E6/unbind" \
  -H "Authorization: Bearer <USER_TOKEN>"
# → 200（只释放绑定，不吊销授权；本就未绑定时幂等成功）
```

> 响应合同（两条分支统一）：`{ success, licenseKey, signedToken, status, machineId, expiresAt, serverTime }`。
> `serverTime`（epoch 毫秒）口径同 `RedeemResponse`——客户端用它抬高本地单调时间下界，
> 抹平「把系统时间改回过去让过期授权复活」这类作弊。
>
> **错误码（两条分支统一）**：`CREDENTIAL_NOT_FOUND` 表示「凭证不认识」——两条分支**同码**，且密钥分支
> 对「不存在」与「非本人」也**同码返回**（防枚举：不泄露他人 License 是否存在）。其余按语义类区分：
> 入参 `CREDENTIAL_REQUIRED` / `MACHINE_ID_REQUIRED`、身份 `LOGIN_REQUIRED`（密钥分支未登录）与
> `EMAIL_REQUIRED` / `INVALID_EMAIL`（兑换分支未登录且未传邮箱）、状态 `LICENSE_NOT_ACTIVE` /
> `CODE_ALREADY_USED` / `CODE_EXPIRED`、设备 `MACHINE_MISMATCH`、风控 `REDEEM_IP_LIMIT` / `REDEEM_BRUTE_FORCE`。
> *（B4 / 2026-09-23：先前密钥分支返回 `LICENSE_NOT_FOUND`、兑换分支返回 `CODE_NOT_FOUND`，客户端对接
> 两个端点要认两个码，现已统一；只统一「凭证不认识」这一类——「已用/过期」描述的是**状态**而非识别失败。）*

### 客户端自动上报绑定（plan-7.0 / D2）

**场景**：客户网购后拿到兑换码，在客户端激活时若**未携带机器码**，该 License 落成「未绑定」态。
客户端随后在**程序启动时**把本机机器码上报一次，本服务把这张未绑定的授权补绑到该设备
（客户端本地只上报一次，成功后不再触发）。

**归属凭证 = `signedToken`（E1 = ①）**：签名令牌即授权本体，客户端在兑换/激活时**必然持有**
（`LicenseService#bindToMachine` 对未绑定件也**无条件**签发），故本端点**不需要登录**。

```bash
# 客户端启动时上报一次（幂等：已绑同机返回既有状态，已绑他机拒绝）
curl -X POST http://localhost:8000/api/licenses/report-binding \
  -H "Content-Type: application/json" \
  -d '{"signedToken": "eyJhbGciOiJFZERTQSJ9...", "machineId": "ABCD-1234-EFGH-5678"}'
```

> 响应合同与 `POST /api/licenses/activate` **同构**（含 `serverTime`），客户端可复用同一套解析。
> **行为**：验签通过 → 取 token 内 `lic` 载入授权 → `ACTIVE` 且**未绑定**则补绑并**重签 token**
> （重签后的令牌已含本次机器码）；**已绑同机**幂等返回、不二次签发；**已绑他机** `MACHINE_MISMATCH`
> （守 B6 = A，不自动改绑，恢复路径为账号页 / 管理端解绑）。
> **错误码**：入参 `CREDENTIAL_REQUIRED` / `MACHINE_ID_REQUIRED`；验签失败或授权不存在统一 `CREDENTIAL_NOT_FOUND`；
> 状态 `LICENSE_NOT_ACTIVE`；设备 `MACHINE_MISMATCH`；风控 `REPORT_RATE_LIMIT`（按**机器码**维度限流）。
> **留痕**：补绑成功记 `license_events.BOUND_BY_REPORT`；机器码登记来源 = `REPORT`。

### 兑换码

> 兑换与批量生成两个端点的 `data` 已由匿名 `Map` 改为类型化 DTO（J3，2026-09-14）：
> `dto/RedeemResponse`（success / licenseKey / signedToken / expiresAt）、
> `dto/GenerateRedeemCodesResponse`（success / count / codes）。字段名与取值不变，仅补上可生成的 OpenAPI Schema。

```bash
# 批量生成（I5：归口到管理端，且**返回码明文列表**——原实现只返回数量，生成后无法取回）
curl -X POST "http://localhost:8000/api/admin/redeem-codes/generate?productSku=pro-buyout&count=100" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# → {"success":true,"count":100,"codes":["K7D2-9FQA-M3PZ-88BC", ...]}

# 导出/对账（I6：按产品 SKU + 状态检索，均可不传）
curl "http://localhost:8000/api/admin/redeem-codes?productSku=pro-buyout&status=UNUSED" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 撤销兑换码（归口到管理端）
curl -X POST http://localhost:8000/api/admin/redeem-codes/revoke/K7D2-9FQA-M3PZ-88BC \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 兑换码兑换 License（公开；唯一保留在 /api/redeem/** 的端点）：传入 machineId 即绑定该设备
curl -X POST http://localhost:8000/api/redeem/redeem \
  -H "Content-Type: application/json" \
  -d '{
    "code": "ABCD-EFGH-IJKL-MNOP",
    "customerEmail": "buyer@example.com",
    "machineId": "ABCD-1234-EFGH-5678"
  }'
```

### 管理端用户管理（plan-7.0 / D4，B9 = B）

> 管理端用户管理 API：变更角色（USER ↔ ADMIN）与启用 / 停用（ACTIVE ↔ DISABLED）。
> 两个端点内部均 `tokenVersion + 1`，令目标用户旧令牌立即失效（配合 `JwtAuthFilter` 每请求现查，使降权 / 停用**即时生效**，不再依赖令牌 7 天自然过期）；操作经 `@Audit` 留痕。
> **护栏**：禁止管理员对自身执行管理操作（防误操作自锁）；禁止降级 / 停用最后一个管理员（否则管理台被锁死）。

```bash
# 变更用户角色（USER ↔ ADMIN）
curl -X PATCH "http://localhost:8000/api/admin/users/{userId}/role?role=ADMIN" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# 启用 / 停用用户（ACTIVE ↔ DISABLED）
curl -X PATCH "http://localhost:8000/api/admin/users/{userId}/status?status=DISABLED" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### 管理端退款

```bash
# 对已完成支付订单发起退款（管理端，需管理员 JWT）
# 退款目标交易号取自 Payment 实体记录，渠道退款失败不会谎报 REFUNDED
curl -X POST "http://localhost:8000/api/admin/orders/{orderNumber}/refund?reason=用户申请" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 客户端集成

> 默认签名算法为 **Ed25519**（JWS header `alg=EdDSA`，配置项 `billing.signature-algorithm`）。客户端应**先读 header 的 `alg` 再选择验签实现**，这样切换 KMS/算法时无需改客户端代码。
> 签名编码差异：Ed25519 为 **raw 64 字节**；ECDSA/RSA 走 JDK/Node 默认（ECDSA 为 **DER**，云 KMS 签发路径已做 raw↔DER 转换以适配 JWS 客户端离线校验，Ed25519 无需转换）。

### Java 客户端验证示例

```java
public class LicenseVerifier {
    private final PublicKey publicKey;

    public LicenseVerifier(String publicKeyPath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(publicKeyPath));
        // 默认 Ed25519（X.509/SPKI）；若用 EC / RSA，改为 KeyFactory.getInstance("EC" / "RSA")
        this.publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public boolean verify(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        String signingInput = parts[0] + "." + parts[1];
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);

        String alg = new ObjectMapper()
            .readTree(Base64.getUrlDecoder().decode(parts[0])).path("alg").asText();
        String jcaAlg = switch (alg) {
            case "EdDSA" -> "Ed25519";
            case "ES256" -> "SHA256withECDSA";
            case "ES384" -> "SHA384withECDSA";
            case "ES512" -> "SHA512withECDSA";
            case "RS256" -> "SHA256withRSA";
            default -> throw new IllegalArgumentException("不支持的 alg: " + alg);
        };

        Signature sig = Signature.getInstance(jcaAlg);
        sig.initVerify(publicKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return sig.verify(signature);
    }

    public Map<String, Object> decodePayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        return new ObjectMapper().readValue(new String(payloadBytes, StandardCharsets.UTF_8), Map.class);
    }
}
```

### JavaScript/Node.js 客户端

```javascript
const crypto = require('crypto');
const fs = require('fs');

const SUPPORTED_ALGS = { EdDSA: null, ES256: 'sha256', ES384: 'sha384', ES512: 'sha512', RS256: 'sha256' };

class LicenseVerifier {
  constructor(publicKeyPath) {
    // PEM/SPKI 公钥；算法由密钥类型与 header.alg 共同决定
    this.publicKey = crypto.createPublicKey(fs.readFileSync(publicKeyPath));
  }

  verify(token) {
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const signingInput = Buffer.from(parts[0] + '.' + parts[1]);
    const signature = Buffer.from(parts[2], 'base64url');

    const { alg } = JSON.parse(Buffer.from(parts[0], 'base64url').toString());
    if (!Object.prototype.hasOwnProperty.call(SUPPORTED_ALGS, alg)) {
      throw new Error('不支持的 alg: ' + alg);
    }
    // Ed25519 的 algorithm 参数必须为 null（算法由密钥类型决定）
    return crypto.verify(SUPPORTED_ALGS[alg], signingInput, this.publicKey, signature);
  }

  decodePayload(token) {
    const parts = token.split('.');
    return JSON.parse(Buffer.from(parts[1], 'base64url').toString());
  }
}
```

## 项目结构

```
billing-license-service/
├── src/main/java/com/billing/license/
│   ├── annotation/           # 自定义注解（@Audit）
│   ├── aspect/               # AOP 切面（AuditAspect：审计统一落库）
│   ├── common/               # 通用组件（响应壳/常量等）
│   ├── config/               # 配置类（SecurityConfig / OpenApiConfig / BillingProperties）
│   ├── controller/           # REST 控制器（订单/许可证/兑换码/收银台/Webhook/管理端）
│   ├── security/             # 鉴权（JwtAuthFilter：JWT 解析 + 按 users.role 动态注入 ROLE_USER/ROLE_ADMIN）
│   ├── service/
│   │   ├── payment/          # 支付编排（PaymentService / PaymentServiceFactory）
│   │   │   ├── strategy/     # PaymentStrategy 接口
│   │   │   └── impl/         # 5 家渠道实现（Alipay / WechatPay / Stripe / Paddle / PayPal）
│   │   ├── subscription/     # 订阅制（续期/取消联动 License）
│   │   ├── notification/     # 邮件通知（EmailNotificationService）
│   │   ├── risk/             # 并发限流（RateLimitService）
│   │   ├── AuditLogService   # 操作审计
│   │   └── ...               # 订单/许可证/兑换码/Checkout 等业务
│   ├── repository/           # 数据访问层
│   ├── entity/               # JPA 实体（Order 双状态机、Product/PlanTier、Subscription、Payment…）
│   ├── dto/                  # 数据传输对象（含脱敏 LicenseResponse/OrderResponse）
│   ├── infrastructure/
│   │   ├── kms/              # KMS（local 纯本地实现）
│   │   └── crypto/           # LicenseIssuer（JWS 签发）/验证
│   └── exception/            # 全局异常处理（脱敏 + traceId）
├── src/main/resources/
│   ├── application.yml       # 主配置（含 management/actuator、payment；KMS 为纯本地文件方案；鉴权无独立配置块，角色由 users.role 决定）
│   ├── application-docker.yml
│   └── db/migration/         # Flyway 迁移脚本 V1–V7
├── src/test/                 # 单元测试 + 集成测试（含 @SpringBootTest 上下文闸门、OpenAPI 文档可用性）
├── scripts/{db,deploy,ops}/  # 运维脚本（package/run/healthcheck/show_migrations…）
└── pom.xml                   # Maven 配置（Java 21 + Spring Boot 4.0.6）
```

## 配置说明

### application.yml 关键配置

```yaml
billing:
  signature-algorithm: ED25519
  private-key-path: /keys/private.key
  public-key-path: /keys/public.key
  default-license-duration-days: 365
  trust-x-forwarded-for: false   # 默认不信 X-Forwarded-For，防限流伪造

payment:
  enabled-channels: ""          # 逗号分隔：alipay / wechat_pay / stripe / paddle / paypal；留空=按各渠道配置齐全度自动启用
  stripe:
    api-key: ${STRIPE_API_KEY}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}
  # 各渠道独立配置块：alipay / wechat / stripe / paddle / paypal（渠道标识见上）

# 鉴权无需独立配置块：管理员与普通用户共用账号体系，角色由 users.role 决定
# （X-API-Key / admin-api-keys 已于 A12 / 2026-09-23 移除）

management:
  endpoints.web.exposure.include: health,info
  endpoint.health.show-details: when_authorized
```

## 扩展支付渠道

实现 `PaymentStrategy` 接口（包 `com.billing.license.service.payment.strategy`），并注册到 `PaymentServiceFactory`：

```java
public interface PaymentStrategy {
    PaymentResponse createPayment(Order order);
    PaymentStatus queryPayment(String paymentId);
    boolean verifyWebhookSignature(String payload, String signature, Map<String, String> headers);
    WebhookPayload parseWebhookPayload(String payload);
    PaymentMethod getPaymentMethod();
    default boolean refundPayment(Order order, String paymentId, java.math.BigDecimal amount) {
        return false;   // 渠道需自行实现退款
    }
}
```

退款时，退款目标交易号取自 `Payment` 实体记录的渠道交易 ID（而非未赋值的 `order.paymentIntentId`）；渠道退款失败不会本地标记 `REFUNDED`，订单保留 `PAID` 并置 `REFUND_FAILED`，由人工介入。

## 许可证格式

签发的许可证采用 JWS 格式（`kid=license-key-1`）：

```
eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.
eyJsaWMiOiJBQkNELUVGR0gtSUpLTC1NTk9QIiwiY2lkIjoiLi4uIn0.
<signature>
```

Payload 包含：

- `lic`: 许可证密钥
- `cid`: 客户 ID
- `sku`: 产品 SKU
- `plan`: 档位（PRO / PRO_PLUS，用于客户端权益区分）
- `feat`: 权益清单（解析自 `Product.features`）
- `mid`: 机器码（客户端离线绑定校验）
- `oid`: 订单 ID
- `iat`: 签发时间
- `exp`: 过期时间
- `meta`: 自定义元数据

## 测试

```bash
# 纯 Mockito 单元测试 + @SpringBootTest 上下文加载/健康检查闸门（H2 内存库，Flyway 关闭）
mvn test
```

当前共 **343 个测试，0 失败 0 错误**（2026-09-23 实跑 `mvn test` 复核：`Tests run: 343, Failures: 0, Errors: 0, Skipped: 0`，并已接入 CI：`.github/workflows/ci.yml`；覆盖 5 渠道策略与退款契约、收银台/订单状态机、兑换码、订阅生命周期、限流淘汰、审计切面、异常脱敏、管理员 MFA（TOTP / 票据 / 密钥加密）、凭证激活体系统一、客户端自动上报绑定、管理端 License 处置（解绑 / 失效重发 / 作废）、机器「已转正」标记（B7 / D3）、OpenAPI 文档可用性与响应壳一致性、ApplicationContext 加载与 `healthEndpoint` Bean 装配）。
