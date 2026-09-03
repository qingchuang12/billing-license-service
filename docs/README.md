# Billing & License Service

统一的计费与许可证管理服务，基于 **Java 21 + Spring Boot 4.0.6**（JPA/Hibernate + PostgreSQL + Flyway + 5 家支付渠道 + KMS）构建，为桌面端 exe 工具提供 license 签发、兑换、换机与计费/退款后端。

> 生产就绪度、整改清单与已知风险见 [`plan.md`](./plan.md)（v2.1，当前为**可发布候选**状态：P0/P1(除 H7)/M4 全绿，80 测试通过）。

## 功能特性

- **三档收费体系**：Pro 买断（一次性付费）/ Pro Plus 高级版（一次性付费，权益更高）/ 订阅制（托管 Paddle / Stripe Billing，自动续期与取消联动 License）。
- **双币种定价**：每个 SKU 维护 CNY 与 USD 两档价格，下单按区域（国内/国际）取对应金额与币种。
- **支付集成（5 家渠道）**：支付宝、微信支付（国内）；Stripe、Paddle、PayPal（国际）。支持创建支付、Webhook 回调发货、查询状态与**管理员发起退款**。
- **许可证签发与校验**：基于 JWS 的签名许可证，payload 含 `lic/cid/sku/plan/feat/mid/oid/iat/exp`，客户端可离线校验机器码绑定与档位权益。
- **兑换码系统**：密码学安全随机（`SecureRandom`）生成、兑换、管理端批量生成与吊销。
- **多算法支持**：Ed25519、ECDSA（ES256/384/512）、RSA（RS256/384/512）。
- **KMS 集成**：本地文件（默认）、AWS KMS、阿里云 KMS，按 `kms.provider` 切换（GCP / Azure 暂未实现）。
- **安全与可观测**：ApiKey 鉴权（管理端 `X-API-Key` + `ROLE_ADMIN`）、CORS 白名单、并发限流（内存淘汰 + XFF 防伪造）、操作审计日志、`/actuator/health` 健康检查（k8s 探针放行）。

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

所有管理端端点（`/api/admin/**`、`/api/v1/licenses/**`、`/api/v1/orders/**`、兑换码生成/吊销）需在请求头携带 `X-API-Key`（值须在 `security.admin-api-keys` 中，等价于 `ROLE_ADMIN`）。客户端公开端点（`/api/checkout/**`、License 校验、兑换码兑换）无需鉴权，依赖签名 License + 限流保护。

### 订单与收银台

```bash
# 创建订单（返回收银台跳转/二维码信息，按区域取 CNY/USD）
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [{"sku": "PRO-PLAN", "quantity": 1}],
    "domestic": true
  }'

# 查询订单状态（幂等，命中已签发结果直接返回，不会重复签发）
curl http://localhost:8080/api/v1/orders/{orderId}
```

### 许可证管理

```bash
# 为已支付订单签发许可证（管理端，需 X-API-Key）
curl -X POST http://localhost:8080/api/v1/licenses/issue/{orderId} \
  -H "X-API-Key: admin-key-0001"

# 验证许可证（公开，离线校验用）
curl http://localhost:8080/api/v1/licenses/verify/{licenseKey}

# 获取客户的所有许可证
curl http://localhost:8080/api/v1/licenses/customer/{customerId}

# 吊销许可证（管理端）
curl -X POST http://localhost:8080/api/v1/licenses/revoke/{licenseKey} \
  -H "X-API-Key: admin-key-0001"
```

### 兑换码

```bash
# 生成兑换码（管理端，需 X-API-Key）
curl -X POST "http://localhost:8080/api/v1/redeem/generate?productSku=PRO-PLAN&count=100" \
  -H "X-API-Key: admin-key-0001"

# 兑换码（公开）
curl -X POST http://localhost:8080/api/v1/redeem/redeem \
  -H "Content-Type: application/json" \
  -d '{"code": "ABCD-EFGH-IJKL-MNOP", "customerId": "550e8400-e29b-41d4-a716-446655440000"}'
```

### 管理端退款

```bash
# 对已完成支付订单发起退款（管理端，需 X-API-Key）
# 退款目标交易号取自 Payment 实体记录，渠道退款失败不会谎报 REFUNDED
curl -X POST "http://localhost:8080/api/v1/admin/orders/{orderNumber}/refund?reason=用户申请" \
  -H "X-API-Key: admin-key-0001"
```

## 客户端集成

### Java 客户端验证示例

```java
public class LicenseVerifier {
    private final PublicKey publicKey;

    public LicenseVerifier(String publicKeyPath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(publicKeyPath));
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        this.publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public boolean verify(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        String signingInput = parts[0] + "." + parts[1];
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        Signature sig = Signature.getInstance("Ed25519");
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

class LicenseVerifier {
  constructor(publicKeyPath) {
    this.publicKey = fs.readFileSync(publicKeyPath);
  }

  verify(token) {
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const signingInput = parts[0] + '.' + parts[1];
    const signature = Buffer.from(parts[2], 'base64url');
    const verifier = crypto.createVerify('SHA512');
    verifier.update(signingInput);
    verifier.end();
    return verifier.verify(this.publicKey, signature);
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
│   ├── config/               # 配置类（含 SecurityConfig）
│   ├── controller/           # REST 控制器（订单/许可证/兑换码/Webhook/管理端）
│   ├── service/
│   │   ├── payment/          # 支付编排（PaymentService / PaymentServiceFactory）
│   │   │   ├── strategy/     # PaymentStrategy 接口 + 5 家渠道实现
│   │   │   └── impl/
│   │   ├── subscription/     # 订阅制（续期/取消联动 License）
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
│   └── db/migration/         # Flyway 迁移脚本 V1–V6
├── src/test/                 # 单元测试 + 集成测试（80 测试，含 @SpringBootTest 上下文闸门）
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
  provider: stripe              # 默认渠道
  stripe:
    api-key: ${STRIPE_API_KEY}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}
  # alipay / wechat_pay / paddle / paypal 各有独立配置块

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

当前共 **80 个测试，0 失败 0 错误**（含退款、限流淘汰、支付失败反馈、异常脱敏、订单状态机、ApplicationContext 加载与 `healthEndpoint` Bean 装配）。
