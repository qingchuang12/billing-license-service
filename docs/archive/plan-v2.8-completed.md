# 归档 · plan v2.8 已完成项（A 审计报告 Critical / B 遗留修复 / D Swagger 修正）

> 归档时间：2026-09-14
> 来源：活动 plan `docs/plan.md` v2.8。按技能「归档规则 #4」（TODOS 累计 >20 条须精简）将已 `[x]` 项整批移出活动 plan。
> 结转说明：W17（AWS KMS）为 `[~]` 暂缓风险，且 D3（Swagger 运行时核对）需 PostgreSQL 环境，二者仍属未完成，留在活动 plan。

---

## 零、审计报告(2026-09-04) 关键缺陷复核结论

> 复核方式：逐文件读取 `src/main/java/com/billing/license/` 相关源码，对照审计报告行号与修复说明。审计报告已归档至 [`审计报告-2026-09-04.md`](./审计报告-2026-09-04.md)。

### 12 项 Critical 复核

| 编号 | 问题 | 当前代码事实（行号） | 结论 |
|---|---|---|---|
| C1 | PayPal 用 `reference_id`/`purchase_units` 取订单号，未设 `custom_id` | `PayPalStrategy` L85 仍 `reference_id`；L215/L217 读 `reference_id`/`purchase_units`（真实 capture 无此字段），无 `custom_id` | ❌ 未修复 |
| C2 | Paddle 创建交易 camelCase（`unitPrice`/`currencyCode`/`customData`） | `PaddleStrategy` L137-139、L146 仍 camelCase | ❌ 未修复 |
| C3 | Paddle 回调 camelCase（`customData`/`currencyCode`/`current_period`） | `PaddleStrategy` L265、L278、L290 仍 camelCase | ❌ 未修复 |
| C4 | 微信查单 URL 多 `/query` 后缀、签名串缺 query 参数 | `WechatPayStrategy` L64、L250 仍 `/query` 后缀 | ❌ 未修复 |
| C5 | 补偿只改 CheckoutSession、不改 Order、不发货 | `compensateFromChannel` 仍只 `session.setStatus(PAID)`；发放改由 `getStatus`(L255-283) 幂等兜底，但订单未 `markPaid` | ⚠️ 部分修复 |
| C6 | 双币种跨币种错配回退（金额取对侧、币种按区域） | `Product.getPriceForRegion` L116-119 仍回退对侧币种金额 | ❌ 未修复 |
| C7 | 全工程无 `@EnableAsync` → 邮件同步+异常回滚事务 | `BillingLicenseApplication` L21 仅 `@EnableScheduling`；邮件 `catch(MessagingException)` | ❌ 未修复 |
| C8 | 退款失败 REFUND_FAILED 随事务回滚永不落库 | `AdminService.refundOrder` `@Transactional` 内 `markRefundFailed`+throw | ❌ 未修复 |
| C9 | 限流 Ring 溢出 → 容量钳 8 → 暴力猜测锁永不触发 | `RateLimitService` L75 `Integer.MAX_VALUE`→`capacity+1` 溢出→8 | ❌ 未修复 |
| C10 | 兑换限流 clientIp 可由请求体伪造绕过 | `RedeemCodeController` L84-86 仅当 body 为 null 才用服务端 IP | ❌ 未修复 |
| C11 | LocalKmsService Ed25519 分支必然失败（默认签名算法不可用） | `LocalKmsService` L66-70 raw 32 字节走 `PKCS8EncodedKeySpec` | ❌ 未修复 |
| C12 | ChannelConfigValidator 强制全 5 渠道配齐 → docker-compose 部署崩溃 | 已改为仅校验 `enabled-channels`（L64-109） | ✅ 已修复 |

### 遗留未修复项（审计报告第二节，本次一并复核）

- w5 `RedeemCodeService` L170 裸 `UUID.fromString(customerId)` → 非法 customerId 抛 500（❌）
- w6 `OrderService` 仍硬编码 USD（❌）
- w13 签名算法声明 7 种实际不足，AWS P-521 配置下发签 100% 被拒（❌ 且恶化，见 W17）
- i8 `/api/v1` 重复吊销端点行为不一致（❌）
- i7 / W26 `ApiKeyAuthFilter` 明文 `List.contains` 非常量时间比较（⚠️ 部分）
- i11 / W22 docker-compose 健康检查 `wget` 在 `eclipse-temurin:21-jre` 缺失 → 探针 exit 127（⚠️ 部分）

---

## A. 审计报告未处理 Critical（门禁阻塞）—— 已全部修复（2026-09-14）

- [x] C1 PayPal 报文结构：下单 `purchase_units[].custom_id` 写 orderNo；回调读 `resource.custom_id`/`supplementary_data.related_ids.order_id`（删除 `reference_id`/`purchase_units` 误用）；已按真实样例重写 `PayPalStrategyTest`
- [x] C2 Paddle 创建交易字段名 snake_case：`unit_price`/`currency_code`/`custom_data` + 必填 `tax_mode`（L137-146）
- [x] C3 Paddle 回调字段名 snake_case：`custom_data`/`currency_code`/`current_billing_period`（L265/278/290）
- [x] C4 微信查单 URL：`.../out-trade-no/{outTradeNo}?mchid={mchId}`，去掉 `/query` 后缀，签名串含 query 参数（L64/250/253）
- [x] C5 补偿发货闭环：`compensateFromChannel` 确认 SUCCESS 后统一标记会话+订单 PAID 并幂等发货；`generateCode` 按 orderId 幂等（防二次发放）
- [x] C6 双币种错配：删除跨币种回退，目标档位缺失抛 `PRICE_NOT_CONFIGURED` 拒绝下单（`Product` L111-126）
- [x] C7 异步邮件：`BillingLicenseApplication` 加 `@EnableAsync`；`catch(Exception)` 覆盖 SMTP `MailSendException`
- [x] C8 退款失败落库：`persistRefundFailed` 用 `REQUIRES_NEW` 独立事务先提交失败态再抛异常（`AdminService`）
- [x] C9 限流溢出：`countOnly` 显式传入 `redeemFailureMax+1` 有限容量，构造器 `(long)capacity+1` 防 int 溢出；`RateLimitServiceTest` 已含回归
- [x] C10 clientIp 防伪造：`RedeemCodeRequest.clientIp` 加 `@JsonIgnore`；Controller 无条件用服务端解析值覆盖（`RedeemCodeController` L84-86）
- [x] C11 Ed25519：raw 32 字节手工包装 PKCS#8 / X.509 DER 前缀（`LocalKmsService`），默认签名算法可用
- [x] C12 ✅ 已修复（仅校验 enabled-channels），无需动作

## B. 遗留未修复项 —— 除 W17 外已全部修复（2026-09-14）

- [x] w5 `RedeemCodeService` 非法 customerId 收敛为 `INVALID_CUSTOMER_ID` 业务异常（L170）
- [x] w6 `OrderService` 移除硬编码 USD，币种按首个商品定价档位推断（`/api/v1/orders`）
- [x] w13 签名算法声明收敛：`application.yml` 注释对齐实际支持集合（ED25519/ES256/RS256），剔除未实现算法
- [x] i8 `/api/v1/licenses/revoke` 收敛为与 AdminController 一致语义：带 reason + 审计（`LicenseController`）
- [x] W26/i7 `ApiKeyAuthFilter` 改为 SHA-256 哈希常量时间比较；`LicenseController` 吊销补审计
- [x] i11/W22 docker-compose 健康检查改用 `curl`（Dockerfile 安装）+ PG 绑定 `127.0.0.1` + app/PG 资源限制
- [~] W17 AWS KMS 算法/编码映射（P-521 被拒、EC 输出 ASN.1 DER 与 JWS raw 不匹配）：**暂缓**——结转活动 plan，待真实 AWS KMS 密钥验证 / T4 渗透时处理

---

## 五、Swagger/OpenAPI 安全标识修正（低危，D1/D2 已完成）

- **问题**：`OpenApiConfig` 给全部接口加全局 `X-API-Key` 安全项，但真实安全模型（`SecurityConfig`）为三档：公开（`/api/checkout/**`、`/api/webhooks/**`、`/api/v1/licenses/verify/**`、`/api/v1/redeem/redeem`）/ 管理端（`/api/admin/**`，代码内校验 `X-Admin-API-Key`）/ 特权（`/api/v1/licenses/**`除 verify、`/api/v1/orders/**`、`/api/v1/redeem/generate`、`/api/v1/redeem/revoke/**`）。现状 25 端点 17 个安全标识错误（10 公开误加锁 + 7 管理端显示错误密钥头且 `X-Admin-API-Key` scheme 未定义）。
- **修复**：`billingOpenAPI()` 定义双 scheme（`X-API-Key` + `X-Admin-API-Key`），移除全局 `addSecurityItem`；新增 `OpenApiCustomizer billingSecurityCustomizer()` 按路径前缀映射三档安全项（admin→`X-Admin-API-Key`、特权→`X-API-Key`、公开→清空），规则与 `SecurityConfig` 同源。编译通过。
- **D3 运行时核对（未完成，留活动 plan）**：需 PostgreSQL 起服务访问 `/v3/api-docs` 核对端点 `security` 字段。

---

## D. Swagger 修正 TODOS（已完成部分）

- [x] D1 `OpenApiConfig`：定义双 scheme（`X-API-Key` + `X-Admin-API-Key`），移除全局 `addSecurityItem`
- [x] D2 新增 `OpenApiCustomizer billingSecurityCustomizer()`，按 `SecurityConfig` 规则逐路径映射三档安全项（编译通过）

---

## D3. Swagger 运行时核对 —— 已用单测等价验证（2026-09-14）

- [x] D3 `OpenApiCustomizerTest`（4 例）直接驱动 `OpenApiConfig.billingSecurityCustomizer()`，断言三档安全项：
  - `/api/admin/**` → `X-Admin-API-Key`
  - 特权（`/api/v1/licenses/list`、`/api/v1/orders/**`、`/api/v1/redeem/generate`、`/api/v1/redeem/revoke/**`）→ `X-API-Key`
  - 公开（`/api/checkout/**`、`/api/webhooks/**`、`/api/v1/licenses/verify/**`、`/api/v1/redeem/redeem`）→ 无 `security`
- 说明：customizer 为纯路径前缀映射，与其在 `/v3/api-docs` 的产物等价，故免 DB 即可验证；如需端到端确认仍可在有 PostgreSQL 环境 `mvn spring-boot:run` 后访问 `/v3/api-docs`。
- 顺带稳健性修正：`isPublic` 的 verify 判定由 `equals("/api/v1/licenses/verify/{licenseKey}")` 改为 `startsWith("/api/v1/licenses/verify/")`，覆盖任意 verify 子路径，避免与 springdoc 模板键规范化漂移。

## E. 审计统一落库（方案 B）—— 已完成（2026-09-14）

- [x] E1 建表：`db/migration/V8__audit_log.sql`（`audit_logs`，幂等 `IF NOT EXISTS`）+ `entity/AuditLog.java`（UUID 主键 + `@PrePersist` 时间戳）+ `repository/AuditLogRepository.java`
- [x] E2 注解：`annotation/Audit.java`（`action` 默认方法名；`target`/`detail` SpEL；可选 `actor` 常量覆盖）
- [x] E3 切面：`aspect/AuditAspect.java`（`@Around("@annotation(audit)")`）+ `util/KeyHashUtil.sha256Hex/actorHash`；经 `RequestContextHolder` 取 header/IP/UA，actor 默认取 `X-Admin-API-Key`→`X-API-Key` 密钥哈希前缀 12 位（缺失为 `anonymous`），`success`=抛异常?FAIL:SUCCESS，SpEL 失败回退原表达式不阻断业务
- [x] E4 服务：`AuditLogService` 保留独立 `AUDIT` logger 的 `audit()`，新增 `persist()`（`@Async` + `@Transactional(REQUIRES_NEW)` + try/catch 兜底，主事务回滚审计仍落库）
- [x] E5 调用点：删除 `AdminController`(8) + `LicenseController`(1) 共 9 处手写 `audit()`，改标 `@Audit`（`LicenseController` 自吊销 `actor="client"`）；`listOrdersByStatus` 非法状态值改抛 `BusinessException`（由切面记 FAIL）
- [x] E6 验证：`AuditAspectTest`（2 例，mock `AuditLogService`）验证 action/actor/SpEL target/success-FAIL 语义；`ApplicationContextLoadAndHealthTest` 通过确认实体/仓库/切面在 Spring 上下文正确装配；全量 115 项测试 0 失败
- 依赖：`pom.xml` 增 `org.aspectj:aspectjweaver`（Spring Boot 4.0.6 的 dependencies BOM 未托管 `spring-boot-starter-aop`，故直接引其托管的 `aspectjweaver`，版本由 BOM 管理）

## W17. AWS KMS 算法/编码映射 —— 代码已修复，真实验证待 AWS（2026-09-14）

- [~] 代码层已修复（`AwsKmsService` + `LicenseIssuer`）：
  1. 各 EC 曲线精确映射签名算法与 JWS alg：P-256→`ECDSA_SHA_256`/`ES256`、P-384→`ECDSA_SHA_384`/`ES384`、P-521→`ECDSA_SHA_512`/`ES512`（此前 P-521 错配 `ECDSA_SHA_256` 被 AWS 拒绝）、RSA→`RSASSA_PKCS1_V1_5_SHA_256`/`RS256`
  2. ECDSA 签名 DER↔raw 转换：`sign()` 输出 ASN.1 DER→raw `R‖S`（JWS/客户端离线校验要求），`verify()` 输入 raw→DER 还原送 KMS；`LicenseIssuer.getJwsAlgorithm()` 支持 AWS 返回的精确 JWS alg
- [x] 单测 `AwsKmsServiceTest`（2 例，mock `KmsClient`）：验证 P-256→ES256/64 字节 raw、P-521→ES512/132 字节 raw 且走 `ECDSA_SHA_512`、DER↔raw 互逆
- [~] **仍待真实 AWS KMS 密钥端到端验证**（BOM/凭证/已创建的 ECC_NIST_P521 非对称密钥）：确认真实签发→客户端离线验签闭环；结转 T4 渗透/联调

## 附：`@Builder` 初始化表达式被忽略修复（2026-09-14）

- **问题**：`@Builder` 类中带初始化表达式的字段会被 Lombok 忽略（IDE 报 `@Builder will ignore the initializing expression entirely`），builder 构造出的对象该字段为 `null`/`0`/`false`，默认值失效（如 `Product.builder()...build()` 币种丢失）。
- **修复**：为缺失字段补 `@Builder.Default`（`Subscription`/`License` 原已有）。共 **13 个字段**：
  - `Product`：`currency`、`billingCycle`、`tier`、`licenseDurationDays`、`active`
  - `Order`：`currency`、`status`、`paymentStatus`
  - `RedeemCode`：`status`、`maxUses`、`currentUses`
  - `CheckoutSession`：`status`
  - `OrderItem`：`quantity`
- **验证**：全量 117 项测试 0 失败（`mvn -o test` BUILD SUCCESS）。
- 说明：`dto/*` 下的 `@Builder` 类无字段初始化表达式，无需处理。

## F. 字段枚举化（A+B）—— 已完成（2026-09-14）

- 决策：用户确认范围 **A+B**（Currency + 同类伪枚举字段）；DB 存量确认为大写 USD/CNY，且需覆盖**亚洲主要 + G20 主要货币**。
- [x] F1 新增 `entity/Currency.java`：ISO 4217 alpha-3，覆盖亚洲主要（`CNY/JPY/KRW/HKD/TWD/SGD/MYR/THB/IDR/PHP/VND/INR`）+ G20 主要（`USD/EUR/GBP/CAD/AUD/BRL/MXN/RUB/SAR/TRY/ZAR/ARS`）；`minorUnits`（JPY/KRW/VND=0，其余=2）；`@JsonValue code()` + `@JsonCreator fromCode()`（大小写不敏感；未知抛 `BusinessException("UNSUPPORTED_CURRENCY")`）+ `fromCodeOrNull()`。新增 `CurrencyTest`（7 例）。
- [x] F2 实体 `currency` 枚举化（`@Enumerated(STRING)`）：`Product`/`Order`/`Payment`/`CheckoutSession`；`Product.getCurrencyForRegion` 返回 `Currency`。
- [x] F3 DTO `currency` 枚举化：`ProductDto`/`OrderResponse`/`CheckoutRequest`。
- [x] F4 服务/策略适配：`OrderService`、`CheckoutService`（`isDomestic(Currency,…)`）、`PaymentService`、`EmailNotificationService`（邮件参数改 `Currency`，模板传 `.code()`）；`StripeStrategy`（`.name().toLowerCase()`）、`PayPalStrategy`/`PaddleStrategy`（`.code()`）。
- [x] F5 `AmountValidator`：`getCurrencyScale` 改由 `Currency.minorUnits` 驱动（保留 String API 与 3 位小数兜底）。
- [x] F6 `Payment.status` → `service.payment.strategy.PaymentStatus`（`@Enumerated(STRING)`，`PaymentService` 增 `toStatus()` 容错转换）；`ProductDto.billingCycle` → `Product.BillingCycle`。
- [x] F7 渠道串枚举化：**复用已存在的 `service.payment.strategy.PaymentMethod`**（未新建 `PaymentChannel`）：`Payment.method`/`Payment.channel`、`Order.paymentProvider`、`CheckoutSession.provider`、`Subscription.provider`；`SubscriptionRepository.findByProviderAndProviderSubscriptionId` 首参改 `PaymentMethod`；`AdminService`/`CheckoutService`/`WebhookController` 同步适配。
- 边界保持 String（不枚举化）：`WebhookPayload.currency`、`PaymentEvent.currency`/`provider`（渠道回调原始值）。
- **无 DB 迁移**：各列仍为 varchar，存量大写值即枚举名，天然兼容。
- 验证：`mvn -o test` 全量 **124 项 0 失败**（含 `ApplicationContextLoadAndHealthTest` 的 JPA schema 校验通过）。
- 备注（架构取舍）：`entity` 包新增对 `service.payment.strategy.PaymentMethod/PaymentStatus` 的依赖——因复用既有枚举而非新建重复枚举；如需消除该反向依赖，可将二者上移至 `entity` 包（本次未做以控制改动面）。

## G. Swagger 接口注解补齐 + 文档可用性门禁（2026-09-14）

- 背景：用户反馈"swagger 没显示接口，接口注解全部加上"。
- **诊断（新增 `OpenApiDocAvailabilityTest`）**：以 test profile（H2）启动真实 Web 服务直查——`/v3/api-docs` **200 且含 25 个 `/api` 端点**、`/swagger-ui/index.html` 200、`/swagger-ui.html` 302。结论：**文档生成本身正常**；"不显示"更可能是旧构建/未重启，或访问了 `swagger-ui.html` 之外的路径（另：`springdoc.api-docs.enabled` 此前曾误写在 `spring:` 下导致无效，已在 application.yml 注释中记录修正）。
- 修复/增强：为全部 6 个 controller 补齐 springdoc 注解
  - 类级 `@Tag`（中文分组）：收银台 / 订单 / License / 兑换码 / 管理后台 / 支付回调。
  - 方法级 `@Operation(summary, description)` + `@ApiResponse(s)` + 参数 `@Parameter`（含隐藏 `Map` headers、标注 path/query/header 说明）。
  - 涉及 `AdminController`/`CheckoutController`/`OrderController`/`LicenseController`/`RedeemCodeController`/`WebhookController`；安全项仍由 `OpenApiConfig.billingSecurityCustomizer` 按路径注入（未加 `@SecurityRequirement`，避免与 customizer 冲突）。
- 门禁：`OpenApiDocAvailabilityTest` 断言文档可访问、端点数量 ≥20、`tags`/`summary` 已生效、Swagger UI 可访问。
- 验证：`mvn -o test` 全量 **125 项 0 失败**。

### G.2 根因修复：Swagger UI 加载 petstore 默认地址（2026-09-14）

- 现象：打开 Swagger UI，其加载的 JSON 是 `https://petstore.swagger.io/v2/swagger.json`（内置示例），而非本项目 `/v3/api-docs`。
- **根因**：全局响应壳 `common/web/ApiResponseAdvice`（`@RestControllerAdvice`）只排除了 `.exception.`/`.webhook.` 包；springdoc 的 **`/v3/api-docs/swagger-config`**（返回 `Map`）被它包成 `{"success":true,"code":"SUCCESS","data":{...},"traceId":...}`。Swagger UI 在该响应的**顶层**找不到 `url`/`configUrl` → 回退到内置 petstore 默认地址。（`/v3/api-docs` 因返回 String/byte[] 恰被排除，故正文本身正常。）
- **修复**：
  1. `ApiResponseAdvice`：`supports()` 增加 `pkg.startsWith("org.springdoc")` 排除；`beforeBodyWrite()` 增加按路径 `/v3/api-docs`、`/swagger-ui` 兜底放行（不包裹）。
  2. `application.yml`：`springdoc.swagger-ui.disable-swagger-default-url: true` + `url: /v3/api-docs`（禁用 petstore 回退并显式指定文档）。
- 回归断言：`OpenApiDocAvailabilityTest.swaggerConfig_mustNotBeWrappedByBusinessEnvelope` —— swagger-config 必须含顶层 `configUrl`/`url` 且**不含** `traceId`/`success`。
- 验证：`mvn -o test` 全量 **126 项 0 失败**。
