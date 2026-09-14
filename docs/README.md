# Billing & License Service

统一的计费与许可证管理服务，基于 **Java 21 + Spring Boot 4.0.6**（JPA/Hibernate + PostgreSQL + Flyway + 5 家支付渠道 + KMS）构建，为桌面端 exe 工具提供 license 签发、兑换、换机与计费/退款后端。

> 生产就绪度与已知风险见活动 plan [`plan-2.9.md`](./plan-2.9.md)（v2.9：**T1–T4 上线验证闸门未过，当前不建议发布**）；历史就绪度评估见 [`archive/plan-v2.2-readiness.md`](./archive/plan-v2.2-readiness.md)（v2.2 快照，不代表当前状态）。

## 功能特性

- **三档收费体系**：Pro 买断（一次性付费）/ Pro Plus 高级版（一次性付费，权益更高）/ 订阅制（托管 Paddle / Stripe Billing，自动续期与取消联动 License）。
- **双币种定价**：每个 SKU 维护 CNY 与 USD 两档价格，下单按区域（国内/国际）取对应金额与币种。
- **支付集成（5 家渠道）**：支付宝、微信支付（国内）；Stripe、Paddle、PayPal（国际）。支持创建支付、Webhook 回调发货、查询状态与**管理员发起退款**。
- **许可证签发与校验**：基于 JWS 的签名许可证，payload 含 `lic/cid/sku/plan/feat/mid/oid/iat/exp`，客户端可离线校验机器码绑定与档位权益。
- **兑换码系统**：密码学安全随机（`SecureRandom`）生成、兑换、管理端批量生成与吊销。
- **多算法支持**：Ed25519（JWS `EdDSA`）、ECDSA（`ES256`/`ES384`/`ES512`）、RSA（`RS256`）——可用集合取决于 KMS：`local`/`aliyun` 为 `EdDSA`/`ES256`/`RS256`，`aws` 额外支持 `ES384`/`ES512`（详见 `application.yml` 的 `billing.signature-algorithm` 注释）。
- **KMS 集成**：本地文件（默认）、AWS KMS、阿里云 KMS，按 `kms.provider` 切换（GCP / Azure 暂未实现）。
- **邮件通知**：支付成功/失败与 License 签发通知（`service/notification/EmailNotificationService`，`@Async`；未配置 SMTP 时自动跳过，不阻塞主流程）。
- **安全与可观测**：ApiKey 鉴权（特权端点 `X-API-Key` + `ROLE_ADMIN`；管理端 `/api/admin/**` 由 `AdminController` 自校验 `X-Admin-API-Key`）、CORS 白名单、并发限流（内存淘汰 + XFF 防伪造）、操作审计日志（`@Audit` + `AuditAspect` 异步独立事务落库）、`/actuator/health` 健康检查（k8s 探针放行）。

## 快速开始

### 1. 生成密钥对（用于 License 签发，KMS=local 时挂载）

```bash
# 生成 Ed25519 密钥对（推荐）
openssl genpkey -algorithm ed25519 -out private.key
openssl pkey -pubout -in private.key -out public.key

# 或 EC P-256
openssl ecparam -name prime256v1 -genkey -noout -out private.key
openssl ec -in private.key -pubout -out public.key

# 或 RSA 2048
openssl genrsa -out private.key 2048
openssl rsa -in private.key -pubout -out public.key
```

### 2. 配置环境变量

```bash
# License 签名密钥（KMS=local）
export PRIVATE_KEY_PATH=/path/to/private.key
export PUBLIC_KEY_PATH=/path/to/public.key

# 数据库
export DB_USERNAME=postgres
export DB_PASSWORD=postgres

# 管理端 API 密钥（逗号分隔，用于 X-API-Key 鉴权，缺失则启动失败）
export ADMIN_API_KEYS=admin-key-0001,admin-key-0002

# 支付渠道密钥（按需配置，未配置渠道不启用）
export STRIPE_API_KEY=sk_live_xxx
export STRIPE_WEBHOOK_SECRET=whsec_xxx
# export ALIPAY_APP_ID=...  export ALIPAY_PRIVATE_KEY=...
# export WECHAT_APP_ID=...  export WECHAT_MCH_ID=...  export WECHAT_API_V3_KEY=...
# export PADDLE_API_KEY=...  export PAYPAL_CLIENT_ID=...  export PAYPAL_CLIENT_SECRET=...

# KMS（可选；默认 local）
# export KMS_PROVIDER=aws        # 另需 AWS 区域/密钥 ID 与凭证
# export KMS_PROVIDER=aliyun     # 另需阿里云 region/密钥 ID 与 AccessKey
```

> 配置优先级：环境变量 > `application.yml`。生产部署务必替换所有占位密钥，并启用 PostgreSQL + Flyway（schema 归迁移脚本管理，`ddl-auto: validate`）。

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

> **鉴权收敛为两档（I1，2026-09-14）**：**公开**端点（`/api/checkout/**`、`/api/redeem/redeem`、`/api/licenses/verify/**`、`/api/webhooks/**`、`/v3/api-docs`）无需鉴权；**其余全部管理动作**（`/api/admin/**`：订单查询/签发/退款、License 查询/作废/换机重发、兑换码生成/导出/撤销）统一携带请求头 **`X-API-Key`**（值须在 `security.admin-api-keys` 中，等价于 `ROLE_ADMIN`），由 `SecurityConfig` 统一鉴权。
> 原 `X-Admin-API-Key`（`AdminController` 自校验）已删除——它与 `X-API-Key` 校验的是**同一份密钥**，属纯冗余；公开端点依赖签名 License + 限流保护。

### 端点总览（共 21 个：公开 10 + 管理端 11）

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
| License | `GET /api/admin/licenses?customerId=&orderNumber=&status=` | `X-API-Key` |
| | `GET /api/admin/licenses/{licenseKey}` | `X-API-Key` |
| | `POST /api/admin/licenses/{licenseKey}/revoke` | `X-API-Key` |
| | `POST /api/admin/licenses/{licenseKey}/reissue` | `X-API-Key` |
| 兑换码 | `POST /api/admin/redeem-codes/generate?productSku=&count=` | `X-API-Key` |
| | `GET /api/admin/redeem-codes?productSku=&status=` | `X-API-Key` |
| | `POST /api/admin/redeem-codes/revoke/{code}` | `X-API-Key` |
| 运维 | `GET /api/admin/payment-channels` | `X-API-Key` |

> **接口合并简化（主题 I，2026-09-14）**：相比改造前的 24 个端点——**删除 6 个重复入口**（订单 by-id / by-number、订单按状态、订单下 License 列表、按客户查 License、`/api/licenses/issue`），**新增 3 个**（参数化 License 查询、订单签发归口、兑换码导出），**迁移 2 类**（签发与兑换码生成/撤销收进 `/api/admin/**`）；收银台支持带 `provider` 一步下单。

> **下单入口唯一（D4，2026-09-14）**：原 `POST /api/orders` 创建入口已删除（与收银台职责重叠，且其计价会产出「币种 CNY + 金额 USD」的资损级不一致），下单统一走 `POST /api/checkout/create`。

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
curl -X POST http://localhost:8080/api/checkout/create \
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
curl -X POST http://localhost:8080/api/checkout/{checkoutId}/select-provider \
  -H "Content-Type: application/json" \
  -d '{"provider": "alipay"}'

# 3) 轮询支付状态（公开）：渠道确认已支付后返回 license / redeemCode
curl http://localhost:8080/api/checkout/{checkoutId}/status
```

### 订单查询

```bash
# 按订单 ID（I2：原 /api/orders/{orderId} 已收敛到此）
curl "http://localhost:8080/api/admin/orders?orderId={orderId}" -H "X-API-Key: admin-key-0001"

# 按业务订单号（原 /api/orders/number/{orderNumber}）
curl "http://localhost:8080/api/admin/orders?orderNumber=ORD-20260914-0001" -H "X-API-Key: admin-key-0001"

# 按状态过滤（原 /api/admin/orders/status/{status}）
curl "http://localhost:8080/api/admin/orders?status=PAID" -H "X-API-Key: admin-key-0001"

# 全部订单
curl "http://localhost:8080/api/admin/orders" -H "X-API-Key: admin-key-0001"
```

### 许可证管理

```bash
# 为已支付订单签发许可证（I4：由原 /api/licenses/issue/{orderId} 归口到管理端；幂等）
curl -X POST http://localhost:8080/api/admin/orders/{orderNumber}/issue \
  -H "X-API-Key: admin-key-0001"

# 验证许可证（公开，离线校验用）
curl http://localhost:8080/api/licenses/verify/{licenseKey}

# 查询 License（I3：一个端点替代「按客户查询」与「订单下 License 列表」，含失效件）
curl "http://localhost:8080/api/admin/licenses?customerId={customerId}" -H "X-API-Key: admin-key-0001"
curl "http://localhost:8080/api/admin/licenses?orderNumber=ORD-20260914-0001" -H "X-API-Key: admin-key-0001"
curl "http://localhost:8080/api/admin/licenses?status=REISSUED" -H "X-API-Key: admin-key-0001"

# 查询 License 详情（含失效件；verify 对失效件返回 400，查失效件用本接口）
curl "http://localhost:8080/api/admin/licenses/{licenseKey}" \
  -H "X-API-Key: admin-key-0001"

# 吊销许可证（吊销唯一入口；D2 起客户端自吊销端点已删除）
curl -X POST "http://localhost:8080/api/admin/licenses/{licenseKey}/revoke?reason=用户申请退款" \
  -H "X-API-Key: admin-key-0001"

# 换机重发（管理端）：原证置 REISSUED，新证绑定新机器码
curl -X POST "http://localhost:8080/api/admin/licenses/{licenseKey}/reissue?newMachineId=NEW-MACHINE-ID&reason=changed_pc" \
  -H "X-API-Key: admin-key-0001"
```

### 兑换码

```bash
# 批量生成（I5：归口到管理端，且**返回码明文列表**——原实现只返回数量，生成后无法取回）
curl -X POST "http://localhost:8080/api/admin/redeem-codes/generate?productSku=pro-buyout&count=100" \
  -H "X-API-Key: admin-key-0001"
# → {"success":true,"count":100,"codes":["K7D2-9FQA-M3PZ-88BC", ...]}

# 导出/对账（I6：按产品 SKU + 状态检索，均可不传）
curl "http://localhost:8080/api/admin/redeem-codes?productSku=pro-buyout&status=UNUSED" \
  -H "X-API-Key: admin-key-0001"

# 撤销兑换码（归口到管理端）
curl -X POST http://localhost:8080/api/admin/redeem-codes/revoke/K7D2-9FQA-M3PZ-88BC \
  -H "X-API-Key: admin-key-0001"

# 兑换码兑换 License（公开；唯一保留在 /api/redeem/** 的端点）：传入 machineId 即绑定该设备
curl -X POST http://localhost:8080/api/redeem/redeem \
  -H "Content-Type: application/json" \
  -d '{
    "code": "ABCD-EFGH-IJKL-MNOP",
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "machineId": "ABCD-1234-EFGH-5678"
  }'
```

### 管理端退款

```bash
# 对已完成支付订单发起退款（管理端，需 X-API-Key）
# 退款目标交易号取自 Payment 实体记录，渠道退款失败不会谎报 REFUNDED
curl -X POST "http://localhost:8080/api/admin/orders/{orderNumber}/refund?reason=用户申请" \
  -H "X-API-Key: admin-key-0001"
```

## 客户端集成

> 默认签名算法为 **Ed25519**（JWS header `alg=EdDSA`，配置项 `billing.signature-algorithm`）。客户端应**先读 header 的 `alg` 再选择验签实现**，这样切换 KMS/算法时无需改客户端代码。
> 签名编码差异：Ed25519 为 **raw 64 字节**；ECDSA/RSA 走 JDK/Node 默认（ECDSA 为 **DER**，服务端已在 AWS KMS 路径做 raw↔DER 转换）。

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
│   │   ├── kms/              # KMS（local / aws / aliyun，按 provider 条件化切换）
│   │   └── crypto/           # LicenseIssuer（JWS 签发）/验证
│   └── exception/            # 全局异常处理（脱敏 + traceId）
├── src/main/resources/
│   ├── application.yml       # 主配置（含 management/actuator、security、payment、kms）
│   ├── application-docker.yml
│   └── db/migration/         # Flyway 迁移脚本 V1–V8
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

kms:
  provider: ${KMS_PROVIDER:local}   # local | aws | aliyun（azure 暂未实现）
  # aws:   { region, key-id, ... }   # 凭证走环境变量
  # aliyun:{ region, key-id, access-key-id, access-key-secret, key-type }

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

当前共 **129 个测试，0 失败 0 错误**（2026-09-14 实跑 `mvn test` 复核：`Tests run: 129, Failures: 0, Errors: 0, Skipped: 0`；覆盖 5 渠道策略与退款契约、收银台/订单状态机、兑换码、订阅生命周期、限流淘汰、审计切面、异常脱敏、OpenAPI 文档可用性与响应壳一致性、ApplicationContext 加载与 `healthEndpoint` Bean 装配）。
