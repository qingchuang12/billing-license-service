# Billing & License Service (Java/Spring Boot)

统一的计费和许可证管理服务，基于 Java 17 + Spring Boot 3.2 构建。

## 功能特性

- **产品管理**: 定义可销售的产品/套餐
- **订单处理**: 创建和管理订单
- **支付集成**: 支持 Stripe（可扩展其他支付提供商）
- **许可证签发**: 基于 JWS 格式的签名许可证
- **兑换码系统**: 生成和验证激活码
- **多算法支持**: Ed25519, ECDSA (ES256/384/512), RSA (RS256/384/512)
- **KMS 集成**: 本地文件、AWS KMS、GCP KMS、Azure Key Vault

## 快速开始

### 1. 生成密钥对

```bash
# 生成 Ed25519 密钥对（推荐）
openssl genpkey -algorithm ed25519 -out private.key
openssl pkey -pubout -in private.key -out public.key

# 或生成 EC P-256 密钥对
openssl ecparam -name prime256v1 -genkey -noout -out private.key
openssl ec -in private.key -pubout -out public.key

# 或生成 RSA 2048 密钥对
openssl genrsa -out private.key 2048
openssl rsa -in private.key -pubout -out public.key
```

### 2. 配置环境变量

```bash
export PRIVATE_KEY_PATH=/path/to/private.key
export PUBLIC_KEY_PATH=/path/to/public.key
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export STRIPE_API_KEY=sk_test_xxx
```

### 3. 启动服务

```bash
./mvnw spring-boot:run
```

或使用 Docker:

```bash
docker-compose up -d
```

## API 接口

### 订单管理

```bash
# 创建订单
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "550e8400-e29b-41d4-a716-446655440000",
    "items": [
      {"sku": "PRO-PLAN", "quantity": 1}
    ]
  }'

# 获取订单
curl http://localhost:8080/api/v1/orders/{orderId}
```

### 许可证管理

```bash
# 为已支付订单签发许可证
curl -X POST http://localhost:8080/api/v1/licenses/issue/{orderId}

# 验证许可证
curl http://localhost:8080/api/v1/licenses/verify/{licenseKey}

# 获取客户的所有许可证
curl http://localhost:8080/api/v1/licenses/customer/{customerId}

# 吊销许可证
curl -X POST http://localhost:8080/api/v1/licenses/revoke/{licenseKey}
```

### 兑换码

```bash
# 生成兑换码（管理员）
curl -X POST "http://localhost:8080/api/v1/redeem/generate?productSku=PRO-PLAN&count=100"

# 兑换码
curl -X POST http://localhost:8080/api/v1/redeem/redeem \
  -H "Content-Type: application/json" \
  -d '{
    "code": "ABCD-EFGH-IJKL-MNOP",
    "customerId": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

## 客户端集成

### Java 客户端验证示例

```java
public class LicenseVerifier {
    private final PublicKey publicKey;
    
    public LicenseVerifier(String publicKeyPath) throws Exception {
        byte[] keyBytes = Files.readAllBytes(Paths.get(publicKeyPath));
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        this.publicKey = keyFactory.generatePublic(
            new X509EncodedKeySpec(keyBytes)
        );
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
        String json = new String(payloadBytes, StandardCharsets.UTF_8);
        return new ObjectMapper().readValue(json, Map.class);
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
    const payload = Buffer.from(parts[1], 'base64url').toString();
    return JSON.parse(payload);
  }
}
```

## 项目结构

```
billing-license-service/
├── src/main/java/com/billing/license/
│   ├── config/              # 配置类
│   ├── controller/          # REST 控制器
│   ├── service/             # 业务逻辑
│   ├── repository/          # 数据访问层
│   ├── entity/              # JPA 实体
│   ├── dto/                 # 数据传输对象
│   ├── infrastructure/      # 基础设施
│   │   ├── kms/            # 密钥管理服务
│   │   └── crypto/         # 加密工具
│   └── exception/           # 异常处理
├── src/main/resources/
│   ├── application.yml      # 应用配置
│   └── db/migration/        # Flyway 迁移脚本
└── pom.xml                  # Maven 配置
```

## 配置说明

### application.yml 关键配置

```yaml
billing:
  signature-algorithm: ED25519  # 签名算法
  private-key-path: /keys/private.key
  public-key-path: /keys/public.key
  default-license-duration-days: 365

payment:
  provider: stripe
  stripe:
    api-key: ${STRIPE_API_KEY}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET}

kms:
  type: local  # local, aws, gcp, azure
```

## 扩展支付提供商

实现 `PaymentProvider` 接口：

```java
public interface PaymentProvider {
    PaymentResult createPayment(Order order);
    PaymentResult refund(String transactionId);
    boolean verifyWebhook(String payload, String signature);
}
```

## 许可证格式

签发的许可证采用 JWS 格式：

```
eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.
eyJsaWMiOiJBQkNELUVGR0gtSUpLTC1NTk9QIiwiY2lkIjoiNTUwZTg0MDAiLCJza3UiOiJQUk8tUExBTiIsImV4cCI6MTcwNjcyOTYwMH0.
<signature>
```

Payload 包含：
- `lic`: 许可证密钥
- `cid`: 客户 ID
- `sku`: 产品 SKU
- `iat`: 签发时间
- `exp`: 过期时间
- `meta`: 自定义元数据

## License

MIT License
