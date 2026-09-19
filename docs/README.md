# Billing & License Service

统一的计费与许可证管理服务，基于 **Java 21 + Spring Boot 4.0.6**（JPA/Hibernate + PostgreSQL + Flyway + 5 家支付渠道 + KMS）构建，为桌面端 exe 工具提供 license 签发、兑换、换机与计费/退款后端。

> **上线执行手册见 [`上线准备工作.md`](./上线准备工作.md)**（阻断项清单 / 密钥生成实测命令 / 渠道设置 / 上线当天清单）。
> 生产就绪度与已知风险见活动 plan [`plan-3.0.md`](./plan-3.0.md)（v3.5：授权硬化与跨仓激活契约已完成，**上线闸门 T1–T4 仍待外部资源**）。

## 功能特性

- **三档收费体系**：Pro 买断（一次性付费）/ Pro Plus 高级版（一次性付费，权益更高）/ 订阅制（托管 Paddle / Stripe Billing，自动续期与取消联动 License）。
- **双币种定价**：每个 SKU 维护 CNY 与 USD 两档价格，下单按区域（国内/国际）取对应金额与币种。
- **支付集成（5 家渠道）**：支付宝、微信支付（国内）；Stripe、Paddle、PayPal（国际）。支持创建支付、Webhook 回调发货、查询状态与**管理员发起退款**。
- **许可证签发与校验**：基于 JWS 的签名许可证，payload 含 `lic/cid/sku/plan/feat/mid/oid/iat/exp`，客户端可离线校验机器码绑定与档位权益。
- **兑换码系统**：密码学安全随机（`SecureRandom`）生成、兑换、管理端批量生成与吊销。
- **多算法支持**：Ed25519（JWS `EdDSA`，默认）、ECDSA（`ES256`）、RSA（`RS256`）；可用集合（纯本地 `LocalKmsService` 支持）：`EdDSA` / `ES256` / `RS256`（详见 `application.yml` 的 `billing.signature-algorithm` 注释）。
- **KMS 集成**：纯本地文件方案（`LocalKmsService`），密钥经文件挂载，无云 KMS 依赖（GCP / Azure / 阿里云 均不接入）。
- **邮件通知**：支付成功/失败与 License 签发通知（`service/notification/EmailNotificationService`，`@Async`；未配置 SMTP 时自动跳过，不阻塞主流程）。
- **安全与可观测**：ApiKey 鉴权（特权端点 `X-API-Key` + `ROLE_ADMIN`；管理端 `/api/admin/**` 由 `AdminController` 自校验 `X-Admin-API-Key`）、CORS 白名单、并发限流（内存淘汰 + XFF 防伪造）、操作审计日志（`@Audit` + `AuditAspect` 异步独立事务落库）、`/actuator/health` 健康检查（k8s 探针放行）。

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

# 管理端 API 密钥（逗号分隔，用于 X-API-Key 鉴权，缺失则启动失败）
export ADMIN_API_KEYS=admin-key-0001,admin-key-0002

# 账号体系：令牌签名密钥（≥32B）与验证码 pepper（生产必配）
export ACCOUNT_JWT_SECRET="$(openssl rand -base64 48)"
export ACCOUNT_CODE_PEPPER="$(openssl rand -hex 16)"

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
#   编辑 .env：设置 DB_PASSWORD 与 ADMIN_API_KEYS

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

> **鉴权为三档（v2.10，2026-09-15）**：
> 1. **公开**：`/api/checkout/**`、`/api/redeem/redeem`、`/api/licenses/verify/**`、`/api/webhooks/**`、`/v3/api-docs`，以及账号的 `POST /api/account/verification-code`、`register`、`login`、`password/reset`；
> 2. **用户（v2.10 新增）**：`Authorization: Bearer <JWT>` → `ROLE_USER`，适用于 `POST /api/account/logout`、`GET /api/account/me`、`POST /api/account/password/change`；
> 3. **特权**：`/api/admin/**` 全部管理动作携带请求头 **`X-API-Key`**（值须在 `security.admin-api-keys` 中，等价于 `ROLE_ADMIN`）。
>
> 原 `X-Admin-API-Key`（`AdminController` 自校验）已删除——它与 `X-API-Key` 校验的是**同一份密钥**，属纯冗余；公开端点依赖签名 License + 限流保护。
> **权限域严格隔离**：用户令牌不能访问 `/api/admin/**`；管理员 `X-API-Key` 也不用于账号端点（登出/me/改密依赖「当前用户」上下文，管理员令牌无此上下文）。

### 端点总览（共 28 个：公开 14 + 管理端 11 + 账号需登录 3）

| 分组 | 方法与路径 | 鉴权 |
|---|---|---|
| 收银台 | `POST /api/checkout/create`（可选 `provider`，一步下单） | 公开 |
| | `POST /api/checkout/{checkoutId}/select-provider` | 公开 |
| | `GET /api/checkout/{checkoutId}/status` | 公开 |
| License | `GET /api/licenses/verify/{licenseKey}` | 公开（失效件返回 400，查失效件用管理端接口） |
| 兑换码 | `POST /api/redeem/redeem` | 公开 |
| 支付回调 | `POST /api/webhooks/{alipay \| wechat \| stripe \| paddle \| paypal}` | 公开（各渠道自行验签） |
| 订单 | `GET /api/admin/orders?status=&orderNumber=&orderId=` | `X-API-Key` |
| | `POST /api/admin/orders/{orderNumber}/issue` | `X-API-Key` |
| | `POST /api/admin/orders/{orderNumber}/refund` | `X-API-Key` |
| License | `GET /api/admin/licenses?customerEmail=&orderNumber=&status=` | `X-API-Key` |
| | `GET /api/admin/licenses/{licenseKey}` | `X-API-Key` |
| | `POST /api/admin/licenses/{licenseKey}/revoke` | `X-API-Key` |
| | `POST /api/admin/licenses/{licenseKey}/reissue` | `X-API-Key` |
| 兑换码 | `POST /api/admin/redeem-codes/generate?productSku=&count=` | `X-API-Key` |
| | `GET /api/admin/redeem-codes?productSku=&status=` | `X-API-Key` |
| | `POST /api/admin/redeem-codes/revoke/{code}` | `X-API-Key` |
| 运维 | `GET /api/admin/payment-channels` | `X-API-Key` |
| 账号 | `POST /api/account/verification-code` | 公开 |
| | `POST /api/account/register` | 公开 |
| | `POST /api/account/login` | 公开 |
| | `POST /api/account/password/reset` | 公开 |
| | `POST /api/account/logout` | 用户令牌 |
| | `GET /api/account/me` | 用户令牌 |
| | `POST /api/account/password/change` | 用户令牌 |

> **接口合并简化（主题 I，2026-09-14）**：相比改造前的 24 个端点——**删除 6 个重复入口**（订单 by-id / by-number、订单按状态、订单下 License 列表、按客户查 License、`/api/licenses/issue`），**新增 3 个**（参数化 License 查询、订单签发归口、兑换码导出），**迁移 2 类**（签发与兑换码生成/撤销收进 `/api/admin/**`）；收银台支持带 `provider` 一步下单。

> **下单入口唯一（D4，2026-09-14）**：原 `POST /api/orders` 创建入口已删除（与收银台职责重叠，且其计价会产出「币种 CNY + 金额 USD」的资损级不一致），下单统一走 `POST /api/checkout/create`。

### 账号体系（v2.10，2026-09-15）

终端用户账号：注册 / 登录 / 登出 / 当前用户 / 改密 / 找回密码 / 邮箱验证码。

**令牌**：JWT（HS256），签名密钥来自 `account.jwt-secret`（**不设默认值，缺失即启动失败**）；有效期默认 7 天（`account.token-ttl-hours`）。
登出、改密、重置密码都会使 `users.token_version` +1，**该用户所有已签发令牌立即失效**。令牌只经 `Authorization: Bearer <token>` 传输，不落 Cookie（服务端无会话）。

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

**限流**（复用 `RateLimitService`，阈值见 `application.yml` 的 `account.risk`）：

| 维度 | 阈值 |
|---|---|
| 验证码发送 | 邮箱冷却 60 秒；邮箱 5 次/60 分钟；IP 10 次/60 分钟 |
| 注册 | IP 5 次/60 分钟；邮箱 3 次/60 分钟 |
| 登录失败 | 账号 5 次 → 锁定 15 分钟（**落库，跨重启有效**）；IP 20 次/10 分钟 |
| 改密 | 3 次/60 分钟（按用户） |
| 找回密码 | 邮箱 5 次/60 分钟；IP 10 次/60 分钟 |
| 验证码校验 | 单码连续错 5 次即作废 |

**新增错误码**：`EMAIL_ALREADY_REGISTERED`、`EMAIL_CODE_REQUIRED`、`INVALID_CREDENTIALS`（邮箱/密码错误统一文案，防账号枚举）、`ACCOUNT_LOCKED`、`ACCOUNT_DISABLED`、`ACCOUNT_NOT_FOUND`、`LOGIN_IP_LIMIT`、`REGISTER_LIMIT`、`RESET_LIMIT`、`CHANGE_PASSWORD_LIMIT`、`CODE_SEND_TOO_FREQUENT`、`CODE_INVALID` / `CODE_EXPIRED` / `CODE_TOO_MANY_ATTEMPTS`、`PASSWORD_POLICY_VIOLATION`、`OLD_PASSWORD_MISMATCH`、`TOKEN_INVALID`、`VALIDATION_ERROR`。

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

失败时 `success=false`、`code` 为业务错误码、`message` 为可读描述、`traceId` 与响应头 `X-Trace-Id` 一致。常见业务错误码：`PRODUCT_NOT_FOUND`、`PRODUCT_INACTIVE`、`PRICE_NOT_CONFIGURED`、`NO_PAYMENT_METHOD`、`PAYMENT_CREATE_FAILED`、`EMAIL_PURCHASE_LIMIT`、`LICENSE_NOT_FOUND`、`LICENSE_INVALID`、`LICENSE_EXPIRED`、`LICENSE_REVOKED`、`LICENSE_REISSUE_LIMIT`、`INVALID_COUNT`、`COUNT_EXCEED_LIMIT`、`INVALID_ORDER_STATUS`、`REFUND_FAILED`、`UNSUPPORTED_CURRENCY`。`/v3/api-docs` 的响应 schema 已同步该壳（H-C3），可直接据此生成客户端 SDK。

### 状态枚举（以代码为准）

| 对象 | 取值 |
|---|---|
| 订单状态 `Order.OrderStatus` | `PENDING` / `CONFIRMED` / `PROCESSING` / `COMPLETED` / `CANCELLED` / `REFUNDED` / `REFUND_FAILED` / `PAID` |
| 订单支付状态 `Order.PaymentStatus` | `UNPAID` / `PAID` / `PARTIALLY_REFUNDED` / `REFUNDED` / `FAILED` |
| License `License.LicenseStatus` | `ACTIVE` / `EXPIRED` / `REVOKED`（退款或违规，管理端发起）/ `REISSUED`（换机重发后旧证退出，新证经 `reissuedFrom` 指回） |
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

```bash
# 按订单 ID（I2：原 /api/orders/{orderId} 已收敛到此）
curl "http://localhost:8000/api/admin/orders?orderId={orderId}" -H "X-API-Key: admin-key-0001"

# 按业务订单号（原 /api/orders/number/{orderNumber}）
curl "http://localhost:8000/api/admin/orders?orderNumber=ORD-20260914-0001" -H "X-API-Key: admin-key-0001"

# 按状态过滤（原 /api/admin/orders/status/{status}）
curl "http://localhost:8000/api/admin/orders?status=PAID" -H "X-API-Key: admin-key-0001"

# 全部订单
curl "http://localhost:8000/api/admin/orders" -H "X-API-Key: admin-key-0001"
```

### 许可证管理

```bash
# 为已支付订单签发许可证（I4：由原 /api/licenses/issue/{orderId} 归口到管理端；幂等）
curl -X POST http://localhost:8000/api/admin/orders/{orderNumber}/issue \
  -H "X-API-Key: admin-key-0001"

# 验证许可证（公开，离线校验用）
curl http://localhost:8000/api/licenses/verify/{licenseKey}

# 查询 License（I3：一个端点替代「按客户查询」与「订单下 License 列表」，含失效件）
curl "http://localhost:8000/api/admin/licenses?customerEmail=buyer@example.com" -H "X-API-Key: admin-key-0001"
curl "http://localhost:8000/api/admin/licenses?orderNumber=ORD-20260914-0001" -H "X-API-Key: admin-key-0001"
curl "http://localhost:8000/api/admin/licenses?status=REISSUED" -H "X-API-Key: admin-key-0001"

# 查询 License 详情（含失效件；verify 对失效件返回 400，查失效件用本接口）
curl "http://localhost:8000/api/admin/licenses/{licenseKey}" \
  -H "X-API-Key: admin-key-0001"

# 吊销许可证（吊销唯一入口；D2 起客户端自吊销端点已删除）
curl -X POST "http://localhost:8000/api/admin/licenses/{licenseKey}/revoke?reason=用户申请退款" \
  -H "X-API-Key: admin-key-0001"

# 换机重发（管理端）：原证置 REISSUED，新证绑定新机器码
curl -X POST "http://localhost:8000/api/admin/licenses/{licenseKey}/reissue?newMachineId=NEW-MACHINE-ID&reason=changed_pc" \
  -H "X-API-Key: admin-key-0001"
```

### 兑换码

> 兑换与批量生成两个端点的 `data` 已由匿名 `Map` 改为类型化 DTO（J3，2026-09-14）：
> `dto/RedeemResponse`（success / licenseKey / signedToken / expiresAt）、
> `dto/GenerateRedeemCodesResponse`（success / count / codes）。字段名与取值不变，仅补上可生成的 OpenAPI Schema。

```bash
# 批量生成（I5：归口到管理端，且**返回码明文列表**——原实现只返回数量，生成后无法取回）
curl -X POST "http://localhost:8000/api/admin/redeem-codes/generate?productSku=pro-buyout&count=100" \
  -H "X-API-Key: admin-key-0001"
# → {"success":true,"count":100,"codes":["K7D2-9FQA-M3PZ-88BC", ...]}

# 导出/对账（I6：按产品 SKU + 状态检索，均可不传）
curl "http://localhost:8000/api/admin/redeem-codes?productSku=pro-buyout&status=UNUSED" \
  -H "X-API-Key: admin-key-0001"

# 撤销兑换码（归口到管理端）
curl -X POST http://localhost:8000/api/admin/redeem-codes/revoke/K7D2-9FQA-M3PZ-88BC \
  -H "X-API-Key: admin-key-0001"

# 兑换码兑换 License（公开；唯一保留在 /api/redeem/** 的端点）：传入 machineId 即绑定该设备
curl -X POST http://localhost:8000/api/redeem/redeem \
  -H "Content-Type: application/json" \
  -d '{
    "code": "ABCD-EFGH-IJKL-MNOP",
    "customerEmail": "buyer@example.com",
    "machineId": "ABCD-1234-EFGH-5678"
  }'
```

### 管理端退款

```bash
# 对已完成支付订单发起退款（管理端，需 X-API-Key）
# 退款目标交易号取自 Payment 实体记录，渠道退款失败不会谎报 REFUNDED
curl -X POST "http://localhost:8000/api/admin/orders/{orderNumber}/refund?reason=用户申请" \
  -H "X-API-Key: admin-key-0001"
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
│   ├── security/             # API Key 鉴权过滤器（ApiKeyAuthFilter）
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
│   ├── application.yml       # 主配置（含 management/actuator、security、payment；KMS 为纯本地文件方案）
│   ├── application-docker.yml
│   └── db/migration/         # Flyway 迁移脚本 V1–V11
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

security:
  api-key-header: X-API-Key
  admin-api-keys: ${ADMIN_API_KEYS}   # 逗号分隔，缺失即启动失败（fail-fast）

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

当前共 **188 个测试，0 失败 0 错误**（2026-09-18 实跑 `mvn test` 复核：`Tests run: 188, Failures: 0, Errors: 0, Skipped: 0`，并已接入 CI：`.github/workflows/ci.yml`；覆盖 5 渠道策略与退款契约、收银台/订单状态机、兑换码、订阅生命周期、限流淘汰、审计切面、异常脱敏、OpenAPI 文档可用性与响应壳一致性、ApplicationContext 加载与 `healthEndpoint` Bean 装配）。
