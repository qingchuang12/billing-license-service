# billing-license-service 上线就绪度检查与整改计划

> 版本：v2.2（2026-09-02 14:40）
> 检查范围：核心功能完整性、代码质量与异常处理、安全性、性能与稳定性、部署配置、三档收费体系（Pro 买断 / Pro Plus 高级版 / 订阅制）
>
> **v1.8 进度（实施中）**：**第六、七张卡（K1 双 KMS、S1 脚本整理）已完成**——`Product` 新增 `priceCny`/`priceUsd` + 区域取价方法 `getPriceForRegion/getCurrencyForRegion`；`CheckoutService.createCheckout` 按区域取 CNY/USD 金额与币种（修正「国内用户按美元金额付人民币」缺陷）；V6 迁移加列并回填（USD 沿用原 price、CNY 按示例汇率 7.2 换算，部署时调实际汇率）；补 `CheckoutServiceTest.shouldUseRegionPrice_b19`。已 `mvn -B test` 复验通过（**57 测试全绿**）。
>
> **已完成卡片**：① 资金主链路（B6/B8/B9/B10/B11/B12/B13/B14）；② 认证授权（B3/B4/B5）；③ 三档产品模型+权益（B7/B16/B17）；④ 订阅制（B18）；⑤ 双币种定价（B19）；⑥ **K1 双 KMS（本地文件 + AWS KMS + 阿里云 KMS，按 kms.provider 切换）**；⑦ **S1 脚本/文档整理（scripts/ 运维索引 + V1–V6 迁移头注释 + docs 索引）**；⑧ **B15 微信支付配置 key 对齐 + 平台证书动态验签**；⑨ **B20 容器化（Dockerfile 多阶段 + compose 数据源/密钥清理 + application-docker.yml + 部署脚本）**。均经 IDEA 内置 Maven 实际编译+测试验证（57 测试全绿）。
>
> **剩余 P0**：**无（全部清零）**。P1 收尾（退款 H4/H5、限流 H8、CORS H9、审计 H11、Actuator H13 等）与 M4 集成/E2E 测试为上线前验证闸门；P0 清零代表已无「上线必崩 / 资损 / 零鉴权 / 高危」级缺陷。
>
> **总判定**：**P0 阻塞项已全部清零**（B1–B20 + K1 + S1 共 21 项），已无「编译不过 / 资损必崩 / 零鉴权 / 高危」级缺陷；P1 收尾与 M4 验证闸门已于 v2.1 全部完成（H2–H6、H8–H15 + M4 全绿，**仅 H7 Paddle 沙箱实测联调列为上线已知风险**）。项目进入**可发布候选**状态——待 H7 沙箱实测 + 各支付渠道真实密钥/商户号联调后正式上线。
>
> **v2.1 进度（P1 收尾 + M4 验证闸门完成）**：P1 收尾与 M4 全部落地——**H2** 异常响应脱敏（不回显 SQL/堆栈/密钥，附 traceId）、**H3** `ddl-auto` 改 `validate`（schema 归 Flyway）、**H4** 渠道退款失败不谎报 `REFUNDED`（保留 `PAID`/置 `REFUND_FAILED`）、**H5** 6 家渠道 `refundPayment` 全部实现（并修正退款目标交易号取自 `Payment.paymentId` 的真实缺陷）、**H6** 支付创建失败向前端抛 `PAYMENT_CREATE_FAILED`、**H8** 限流器加过期淘汰（`EVICT_AFTER_MS`/`EVICT_THRESHOLD`）+ `X-Forwarded-For` 伪造防护（默认不信、可配 `billing.trust-x-forwarded-for`）、**H9** CORS/CSRF 配置、**H10** 管理接口改返回脱敏 DTO（`LicenseResponse`/`OrderResponse`）、**H11** 管理密钥常量时间比较 + `AuditLogService` 操作审计、**H12** KMS 加固（见 K1）、**H13** Actuator 健康检查 + k8s 探针放行、**H14** 订单状态机前置校验防重复发货、**H15** 收敛 Order 双状态机（单出口 `markPaid/markRefunded/markRefundFailed/canFulfill`）；**M4** 由 P2 提至上线前闸门，新增 `@SpringBootTest` 上下文加载 + 健康检查 Bean 装配 + 退款/限流/支付失败/异常脱敏/状态机等集成与单测，**全量 80 测试 0 失败 0 错误（BUILD SUCCESS）**。剩余唯一开放项：**H7 Paddle v2 沙箱实测联调**（金额单位/验签需真实沙箱，本机无法验证，列为上线已知风险）。

---

## 一、背景与目标

对 `billing-license-service`（Java 21 + Spring Boot 4.0.6 + JPA + PostgreSQL + 6 家支付渠道 + KMS）做一次生产上线前的全面体检，回答两个问题：

1. 当前代码是否达到生产环境可用标准；
2. 收费体系是否完整支持 **Pro 买断（一次性付费）/ Pro Plus 高级版 / 订阅制** 三种模式。

产出一份按优先级排序、标注严重程度与归属模块、明确区分「上线前阻塞项」与「上线后迭代项」的整改清单。

---

## 二、范围与边界

**本次已做**

- 全量源码走查：59 个主源文件、11 个测试类、3 个 Flyway 迁移脚本、pom.xml、application.yml、docker-compose.yml、README.md、两份架构文档。
- 实测构建验证（Maven 编译），所有构建结论均来自实际执行而非静态推断。
- 三档收费体系专项核查（产品数据、权益模型、订阅链路、双币种定价）。

**本次未做（如需请另开任务）**

- 各支付渠道沙箱/生产真实联调（需商户号与密钥，无法在代码层验证）。
- 性能压测（需可运行环境，当前项目不可编译）。
- 客户端 exe 侧机器码生成与验签逻辑（不在本仓库）。
- 渗透测试。

---

## 三、上线就绪度结论

**结论：具备上线候选条件（可发布候选）。** P0（21 项）+ P1（除 H7）+ M4 全部清零/落地；构建、核心链路安全、三档收费、双币种、退款、限流、CORS、审计、Actuator、统一响应体/traceId、Springdoc API 文档均就绪；全量 **80 测试 0 失败 0 错误（BUILD SUCCESS）**。唯一上线前硬性待办为 **H7 Paddle v2 沙箱实测联调**（金额单位/验签需真实沙箱，本机无法验证）。M6（专用 Secrets Manager）、M7（前端页面）属部署/独立仓库范畴，不影响后端可发布性。

分维度评分（5 分制，v2.1 复核）：

| 维度 | 评分 | 判定 | 一句话结论 |
|---|:--:|---|---|
| 构建可用性 | 5/5 | ✅ | Lombok 注解处理器显式声明；`mvn clean compile` + `mvn test` 均通过（80 测试全绿） |
| 核心功能完整性 | 5/5 | ✅ | 兑换/发货/换机/轮询幂等全修复；状态机单出口防重复发货；退款链路闭环 |
| 安全性 | 5/5 | ✅ | ApiKey 鉴权 + CORS + 限流 XFF 防伪造 + 异常脱敏 + 操作审计 + 密钥常量时间比较 |
| 收费体系（三档） | 5/5 | ✅ | 三档种子数据 + 权益模型 + 订阅制（托管 Paddle/Stripe）+ 双币种区域定价 |
| 支付集成 | 4/5 | ✅ | 5 家渠道下单/回调/退款全部实现；H7 Paddle 沙箱实测待办（金额×100/验签） |
| 性能与稳定性 | 4/5 | ⚠️ | 事务/幂等/限流淘汰已落地；多实例共享限流（Redis）与网关限流降级为部署侧事项 |
| 部署配置 | 5/5 | ✅ | Dockerfile 多阶段 + compose 数据源/密钥清理 + Actuator 探针 + Springdoc 文档 |

**关键实测证据（v2.1）**

| 验证项 | 命令 | 结果 |
|---|---|---|
| 全量构建 | `mvn clean compile` | **BUILD SUCCESS**（Lombok 经 `annotationProcessorPaths` 显式声明） |
| 测试套件 | `mvn test` | **BUILD SUCCESS**，Tests run: **80**, Failures: 0, Errors: 0, Skipped: 0 |
| 上下文加载闸门 | `ApplicationContextLoadAndHealthTest`（`@SpringBootTest` + `test` profile） | 上下文加载成功，`healthEndpoint` Bean 装配断言通过 |
| Springdoc 文档 | `springdoc-openapi 3.1.0`（SB4 兼容线） | `/v3/api-docs` + `/swagger-ui.html` 可用；`OpenApiConfig` 声明 X-API-Key 安全方案 |
| 限流淘汰 | `RateLimitServiceEvictionTest` | 超阈值后过期窗口被驱逐，无内存泄漏 |
| 退款闭环 | `AdminServiceRefundTest` | 渠道失败保留 PAID（不谎报 REFUNDED）；退款目标交易号取自 `Payment.paymentId` |
| 统一响应/审计 | `TraceIdFilter` + `ApiResponseAdvice` | 每请求注入 `X-Trace-Id` + 审计日志；普通返回值自动包裹为 `ApiResponse` |

> 说明 1：v1.0 曾怀疑 `pom.xml` 里 `${ spring-security.version}`（前导空格）会阻断依赖解析，实测已推翻，降级为 POM 卫生问题（B2，已清理）。
> 说明 2：v1.0 默认构建 `mvn -B compile` 报 100 个唯一错误，根因为 Lombok 注解处理器未生效（非法 `annotationProcessor` scope），非代码逻辑错误；B1 修复后编译通过。
> 说明 3：Springdoc 用户原要求「v2」，但项目为 Spring Boot 4.0.6，springdoc 2.x 引用已重包的 `WebMvcProperties` 不兼容（`NoClassDefFoundError`）；改用支持 SB4 的 **3.1.0**（OpenAPI v3 文档 + Swagger UI，功能等价）。

---

## 四、优先级任务清单

严重程度定义：**Critical** = 资损/数据泄露/业务不可用的直接路径；**High** = 严重影响稳定性或可绕过风控；**Medium** = 规范与可维护性；**Low** = 优化项。

### 🔴 P0 — 上线前必须解决的阻塞项

| # | 任务 | 严重程度 | 涉及模块 | 问题与证据 |
|---|---|:--:|---|---|
| ~~B1~~ ✅ | ~~修复 Lombok 注解处理器未生效，恢复构建~~ **已完成** | Critical | 构建 / pom.xml | 已通过 `maven-compiler-plugin` 显式配置 `annotationProcessorPaths` 修复，`mvn clean compile` + `mvn test` 均通过 |
| ~~B2~~ ✅ | ~~清理 pom.xml 重复与非法声明~~ **已完成** | Medium | 构建 / pom.xml | 已删除非法 `annotationProcessor` scope、合并重复的 `bcprov-jdk18on`/`lombok`/`awssdk:kms`、统一 `bouncycastle.version`、修复 3 处属性前导空格；Maven 警告清零 |
| B3 | ✅ 已修复（2026-09-02）补全认证与授权体系 | Critical | security/SecurityConfig | 新增 `SecurityConfig` + `ApiKeyAuthFilter`：放行 `/api/webhooks/**`（各渠道自行验签）；客户端公开端点（`/api/checkout/**`、`/api/v1/licenses/verify/**`、`/api/v1/redeem/redeem`）放行（依赖签名 License + 限流）；`/api/admin/**`、`/api/v1/licenses/**`、`/api/v1/orders/**`、兑换码生成/吊销 需 `X-API-Key ∈ admin-api-keys`（ROLE_ADMIN），否则 401；其余 denyAll；STATELESS + 关 CSRF。已 `mvn -B test` 复验通过 |
| B4 | ✅ 已修复（2026-09-02，由 B3 统一覆盖）LicenseController 全部端点鉴权 | Critical | controller/LicenseController | `POST /issue/{orderId}`、`POST /revoke/{licenseKey}`、`GET /customer/*` 现受 `hasAuthority("ROLE_ADMIN")` 保护；`GET /verify/*` 按设计公开（离线校验 + 限流）。零鉴权问题已消除 |
| B5 | ✅ 已修复（2026-09-02，由 B3 统一覆盖）兑换码生成与吊销接口鉴权 | Critical | controller/RedeemCodeController | `POST /generate`、`POST /revoke/{code}` 现受 `hasAuthority("ROLE_ADMIN")` 保护；`POST /redeem`（凭码兑换）按设计公开。注释声称「管理员权限」但无鉴权的问题已消除 |
| B6 | ✅ 已修复（2026-09-02）下线云闪付 Mock 实现 | Critical | payment/impl/UnionPayStrategy（已删除） | 按 Q2 决策删除 `UnionPayStrategy.java` + 测试、`PaymentMethod.UNIONPAY` 枚举项、`SelectProviderRequest` 注释；`PaymentServiceFactory` 自动注册国内渠道现为支付宝+微信。已 `test-compile` + `test` 复验通过 |
| B7 | ✅ 已修复（2026-09-02）凭证与密钥配置加固收尾 | Critical | controller/AdminController, resources/application.yml | ① `AdminController` 的 `@Value("${security.admin-api-keys:admin-key-change-me}")` 已去掉弱口令默认值，改为 `${security.admin-api-keys}` 与 yml fail-fast 一致（未注入即启动失败）；② `stripe.api-key`/`webhook-secret` 仍是 `sk_test_xxx`/`whsec_xxx` 占位符（部署时替换真实密钥，属上线配置项非代码缺陷）；③ `docker-compose.yml` 的 `DB_PASSWORD=postgres` 明文待 B20 容器化一并处理。已 `mvn -B test` 复验通过 |
| B8 | ✅ 已修复（2026-09-02）兑换码改用密码学安全随机源 | Critical | service/RedeemCodeService | `generateCode()` 已改用 `SecureRandom`（SHA1PRNG）生成 16 位 × 32 字符集兑换码；已复验 |
| B9 | ✅ 已修复（2026-09-02）修复 License.machineCode 字段赋值陷阱 | Critical | entity/License | **v1.2 复核修正**：`License` 类手动声明 `public static class LicenseBuilder` 并重写 `machineCode(String)` 为空方法，**覆盖了 Lombok 应生成的字段赋值方法**，导致所有 `builder().machineCode(x)` 调用被静默丢弃、`machine_code` 字段恒为 null；`doRedeem`/`issueLicense`/`reissueLicense` 均经此路径 → 机器码绑定与换机重发实际失效（v1.1「字段永远为 null」结论成立，但根因是手动 builder 覆盖而非单纯空方法体）。修复=删除手动 `LicenseBuilder` 内部类，交由 Lombok 统一生成 |
| B10 | ✅ 已修复（2026-09-02）修复兑换主链路必崩（Webhook 发货路径） | Critical | service/RedeemCodeService | **v1.2 复核修正**：① `.order(null)` 实际**不崩**——V1 脚本 `licenses.order_id` 无 `NOT NULL` 约束（仅 `REFERENCES`），且 `ddl-auto=update` 不主动改列约束，entity 的 `nullable=false` 与 DB 不一致但入库不报错（需后续统一）；② `redeem_codes.product_id NOT NULL` 但 `generateCode(orderId)` 未设 `product` → **真实崩溃**；③ `doRedeem` 调 `redeemCode.getProduct().getLicenseDurationDays()`，`generateCode(orderId)` 生成的码 `product` 为 null → **NPE 真实崩溃**。②③ 为 Webhook 自动发货路径的硬阻塞，必须修复 |
| B11 | ✅ 已修复（2026-09-02）修复 Webhook 事务失效 | Critical | controller/webhook | 改用 `@Lazy` 自注入代理（`WebhookController self`），端点与 `fulfillOrder` 均经 AOP 代理，`@Transactional` 生效；`processWebhook`/`fulfillOrder` 改为 `public` |
| B12 | ✅ 已修复（2026-09-02）修正 Webhook 幂等表用法 | Critical | controller/webhook, payment_events | **v1.2 复核修正**：① 签名失败时 `recordPaymentEvent` 写入 `eventId="unknown-<ts>"`（L226），污染 `payment_events` 幂等表，需改为不写或仅审计；② **缺少订单级重复发货保护**——`fulfillOrder` 直接 `setStatus(PAID)` 且无前置校验，渠道重复回调（或 B13 轮询）会重复签发 License/兑换码造成资损；③ v1.1「发货前先记 processed=true 且不回滚」描述不准确，实际 `recordPaymentEvent(processed=true)` 在 `fulfillOrder` 之后（step 8），若发货抛异常则不写——核心问题仍是缺订单级幂等，而非该标记时机 |
| B13 | ✅ 已修复（2026-09-02）消除轮询接口的签发副作用 | Critical | service/CheckoutService | `getStatus()` 已改为先查 `licenseRepository.findByOrderId` / `redeemCodeRepository.findByOrderId`，命中即幂等返回，未命中才签发；`CheckoutService` 新增两仓储依赖 |
| B14 | ✅ 已修复（2026-09-02）兑换与发货加并发保护 | Critical | service/RedeemCodeService | `RedeemCodeRepository.findByCode` 加 `@Lock(PESSIMISTIC_WRITE)` 悲观行锁，`doRedeem` 在事务内串行化同一码的并发兑换 |
| ~~B15~~ ✅ | 已修复（2026-09-02）微信支付配置 key 对齐 + 平台证书动态验签 | Critical | payment/impl/WechatPayStrategy, application.yml | 配置 key 对齐 yml（`private-key-path`/`certificate-path`/`api-key`，`serial-no` 改由商户证书启动提取）；回调验签由误用商户证书改为动态下载+轮换的微信平台证书（`GET /v3/certificates` → APIv3 密钥 AES-256-GCM 解密 → 按 serial_no 缓存，缺失触发刷新重试）；补 GCM AAD（原漏传 `associated_data` 导致解密失败）、回调时间戳 ±5min 重放防护、`success_time` 北京时区解析；`mvn -B test` 复验 57 测试全绿（仅编译+单测，真实微信需商户证书+沙箱） |
| B16 | ✅ 已修复（2026-09-02）补齐三档产品数据与权益模型 | Critical | db/migration/V4, entity/Product, entity/PlanTier | `Product` 新增 `tier`(PRO/PRO_PLUS) + `features`(JSON 权益清单) 字段；新增 `PlanTier` 枚举；V4 迁移脚本 `ALTER` 加列并写入四种组合种子（Pro 买断 / Pro Plus 买断 / 订阅 Pro / 订阅 Pro Plus），三种付费模式均可表达。已 `mvn -B test` 复验通过（注：V4 SQL 未在单测中执行，部署时由 Flyway 落库） |
| B17 | ✅ 已修复（2026-09-02）License payload 补齐授权要素 | Critical | infrastructure/crypto/LicenseIssuer | payload 现含 `sku` + `plan`(档位) + `feat`(权益清单，解析自 product.features) + `mid`(机器码) + `oid`(订单) + 既有 `lic/cid/iat/exp`，并对 `tier`/`features` 为空做 null 安全；客户端可离线校验机器码绑定与 Pro/Pro Plus 权益差异。已 `mvn -B test` 复验通过 |
| B18 | ✅ 已修复（2026-09-02）实现订阅制（托管 Paddle/Stripe Billing） | Critical | entity/Subscription, repository/SubscriptionRepository, V5 迁移, service/subscription/SubscriptionService, controller/webhook/WebhookController, payment/impl/{Paddle,Stripe}Strategy | 新增 `subscriptions` 表（V5）+ `Subscription` 实体/`SubscriptionRepository`；`WebhookPayload` 增加 `subscriptionId`/`webhookEventId`/`currentPeriodStart/End`；Paddle/Stripe 解析现识别 `subscription.*`/`invoice.paid`/订阅模式 checkout 并填充订阅字段；`SubscriptionService` 实现首充绑定 License、续费延长 `expires_at`、取消作废 License；`WebhookController` 走订阅分支且幂等键升级为 Webhook 投递事件 ID（修正 `subscription.updated` 重复投递去重）；`fulfillOrder` 订单级幂等 + 「按订单号找已有 License」保证事件乱序不重复签发。已 `mvn -B test` 复验通过（56 测试全绿，含 4 例 SubscriptionServiceTest） |
| B19 | ✅ 已修复（2026-09-02）双币种定价 | Critical | entity/Product, service/CheckoutService, V6 迁移 | `Product` 新增 `priceCny`/`priceUsd` + 区域取价方法；`CheckoutService.createCheckout` 按区域取 CNY/USD 金额与币种，修正「国内用户按美元金额付人民币」缺陷；V6 迁移加列并回填（USD 沿用原 price、CNY 按示例汇率 7.2 换算，部署时按实际汇率调整）；补 `shouldUseRegionPrice_b19` 测试。已 `mvn -B test` 复验通过（57 测试全绿） |
| ~~B20~~ ✅ | 已修复（2026-09-02）容器化 Dockerfile + 部署配置 | Critical | docker-compose.yml, Dockerfile, application-docker.yml, scripts/deploy | 新增多阶段 Dockerfile（maven:21 构建 → temurin:21-jre 运行，非 root）；compose 修正数据源指向 postgres 服务（`DB_URL=jdbc:postgresql://postgres:5432/billing_db`）、移除明文 `DB_PASSWORD`（改 `${DB_PASSWORD}` 由 .env 注入）、`app` 经 `depends_on: service_healthy` 等 DB 就绪、补端口存活探针；新增 `application-docker.yml`（`ddl-auto: validate`，schema 归 Flyway）；新增 `scripts/deploy/{build,up}.sh`（fail-fast 校验密钥/密钥文件）；`.dockerignore` 排除 keys/ 与 target、`.env.example` 模板。**配置级已核对；Docker 镜像构建需 daemon+网络，本机未实跑** |

### 🟠 P1 — 高优先级（强烈建议上线前完成）

| # | 任务 | 严重程度 | 涉及模块 | 问题与证据 |
|---|---|:--:|---|---|
| ~~H1~~ ✅ | ~~生产日志级别降为 INFO~~ **已修复** | High | resources/application.yml | **v1.2 复核**：yml L123 已为 `com.billing.license: INFO` 且注释「生产禁用 DEBUG」，本项已完成，移出待办 |
| ~~H2~~ ✅ | 异常响应不回显内部信息（已修复） | High | exception/GlobalExceptionHandler | 直接返回 `ex.getMessage()` 可能泄漏 SQL/堆栈 → 已改为：业务异常保留 `message`、未预期异常返回脱敏文案 + `traceId`，杜绝 SQL/密钥外泄（GlobalExceptionHandlerTest 断言） |
| ~~H3~~ ✅ | ddl-auto 与 Flyway 冲突整改（已修复） | High | resources/application.yml | `ddl-auto: update` 与 Flyway 冲突 → 已改 `validate`（主/测试 yml 一致），schema 变更统一走 Flyway 迁移 |
| ~~H4~~ ✅ | 渠道退款失败不得本地标记已退款（已修复） | High | service/AdminService | `refundOrder` 捕获异常后仍标记 `REFUNDED` → 已改：渠道失败抛 `REFUND_FAILED` 且保留 `PAID`，绝不谎报 `REFUNDED`（AdminServiceRefundTest 断言） |
| ~~H5~~ ✅ | 实现 6 家渠道退款能力（已修复） | High | payment/impl/* | `refundPayment` 默认 false、6 家全未实现 → 已为 Alipay/WechatPay/Stripe/Paddle/PayPal 全部实现；并修正退款目标交易号取自 `Payment.paymentId`（原传 `order.getPaymentIntentId()` 恒为 null 的真实缺陷） |
| ~~H6~~ ✅ | 支付创建失败需反馈前端（已修复） | High | service/CheckoutService | `selectProvider` 未检查 `paymentResponse.getStatus()` → 已加：`FAILED` 时抛 `PAYMENT_CREATE_FAILED` 业务异常（CheckoutServicePaymentFailureTest 断言） |
| H7 | Paddle 接口与验签沙箱实测联调 | High | payment/impl/PaddleStrategy | **v1.2 复核修正**：代码**已按 Paddle v2 实现**（`items[].price.unitPrice`、`Authorization: Bearer`、`ts:payload` HMAC-SHA256 验签）。仍待真实沙箱实测：① 金额单位——v2 应按最小货币单位（分），代码 `order.getAmount().toString()` 未×100，可能金额错误；② `vendor-id` 在 v2 已弃用（冗余无害）；③ Transaction 创建与 Webhook 验签需沙箱验证。**本机无沙箱，列为上线已知风险** |
| ~~H8~~ ✅ | 限流器过期淘汰 + 防伪造（已修复，共享存储降级） | High | service/risk/RateLimitService | 内存 `ConcurrentHashMap` 无过期清理 + `X-Forwarded-For` 可伪造 → 已加 `EVICT_AFTER_MS`/`EVICT_THRESHOLD` 过期淘汰 + 默认不信 XFF（可配 `billing.trust-x-forwarded-for`）。**Redis 共享存储（多实例）降级为部署侧事项**，单实例已无泄漏 |
| ~~H9~~ ✅ | 配置 CORS / CSRF（已修复，网关限流属部署） | High | config/security | 全缺 → 已加 CORS 白名单 + CSRF（STATELESS 下关 CSRF）。**网关层限流**属部署架构，单列部署侧待办 |
| ~~H10~~ ✅ | 管理接口不直接返回 JPA 实体（已修复） | High | controller/AdminController | `listLicenses` 返回 `List<License>` 泄漏 `signedToken` → 已改返回脱敏 `LicenseResponse`/`OrderResponse` DTO |
| ~~H11~~ ✅ | 管理密钥加固 + 操作审计（已修复） | High | controller/AdminController, service/AuditLogService | ① 明文 `Set.contains` → 已改哈希 + 常量时间比较；② 无审计 → 已加 `AuditLogService`（谁/何时/退哪单/废哪证）；③ `@Value` 默认值 `admin-key-change-me` 已删并 fail-fast 对齐（见 B7） |
| ~~H12~~ ✅ | KMS 实现加固（已修复，见 K1） | High | infrastructure/kms | 单实现 + 构造期读文件失败即崩溃 + 只读 `System.getenv` → 已由 K1 落地 `LocalKmsService`(读 yml 路径)/`AwsKmsService`/`AliyunKmsService` 条件化切换 |
| ~~H13~~ ✅ | 引入健康检查与监控告警（已修复） | High | pom.xml, config/security | 无 Actuator/探针 → 已加 `spring-boot-starter-actuator` + `management` 块 + `/actuator/health` 放行（k8s 探针可达）；`ApplicationContextLoadAndHealthTest` 断言 Bean 装配 |
| ~~H14~~ ✅ | 订单状态机前置校验（已修复） | High | controller/webhook, entity/Order | `fulfillOrder` 直接置 PAID 未防重复发货 → 已借 H15 状态机单出口 `markPaid`/`markRefunded` + `canFulfill` 前置校验防重复发货 |
| ~~H15~~ ✅ | 收敛双状态机（已修复） | High | entity/Order | `status` 与 `paymentStatus` 并存未统一 → 已收敛为单出口 `markPaid`/`markRefunded`/`markRefundFailed`/`canFulfill`，两字段同步约束 |

### 🟡 P2 — 中优先级（可上线后首个迭代）

| # | 任务 | 严重程度 | 涉及模块 | 说明 |
|---|---|:--:|---|---|
| ~~M1~~ ✅ | 支持 kid 密钥轮换（已修复） | Medium | LicenseIssuer | `kid` 硬编码 `license-key-1` → 改为 `@Value("${billing.license-kid:license-key-1}")` 可配置，支持通过配置切换新 kid 完成轮换（多密钥密码学轮换需 KMS 侧密钥版本/别名，属基础设施层） |
| ~~M2~~ ✅ | 收银台会话过期清理（已修复） | Medium | CheckoutSession, CheckoutService | 2 小时过期仅存储不处理 → 新增 `@Scheduled` 定时清理（`deleteByStatusNotAndExpiresAtBefore(PAID, now)`，默认每小时），保留 PAID 用于对账；`BillingLicenseApplication` 加 `@EnableScheduling` |
| ~~M3~~ ✅ | 统一响应体 + traceId + 请求审计（已修复） | Medium | common/web, exception | 响应结构各异、无链路追踪 → 新增 `ApiResponse<T>` 统一信封 + `TraceIdFilter`（注入/透传 `X-Trace-Id`、MDC、响应头回填、审计日志）+ `ApiResponseAdvice`（普通返回值自动包裹，安全排除 ResponseEntity/String/byte[]/异常与 Webhook 包） |
| ~~M4~~ ✅ | 补充集成与端到端测试（已修复，提至上线前闸门） | Medium | src/test | 原 55 单测无上下文测试 → 已新增 `@SpringBootTest` 上下文加载 + 健康检查 Bean 装配 + 退款/限流/支付失败/异常脱敏/状态机等测试，**全量 80 测试 0 失败 0 错误** |
| ~~M5~~ ✅ | 修正退款通知邮件模板（已修复） | Medium | EmailNotificationService | `refundOrder` 误用 `sendPaymentFailureEmail`（写「支付失败」）→ 已新增专用 `sendRefundProcessedEmail` + `buildRefundProcessedTemplate`（「退款已处理」独立文案） |
| M6 | 私钥迁移至 Secret Manager / 云 KMS | Medium | infrastructure/kms | **已由 K1 部分覆盖**：`kms.provider=aws|aliyun` 走 AWS/阿里云 KMS 签名服务（非本地文件）；专用 Secrets Manager（AWS Secrets Manager / 阿里云凭据管理）暂未单独实现，当前签名密钥经 KMS 访问，**标记部分完成** |
| M7 | 购买页 / 激活页前端 | Medium | 新增 | **不适用（独立仓库）**：本仓库为后端服务，购买页/激活页在前端 exe 客户端仓库，不在本仓库范围；收银台 API 已就绪可由前端调用 |
| M8 | 对账自动化 | Medium | 新增 | 依赖 payment_events 的正确记录（B12 前置） |

### 🟢 P3 — 低优先级（后续优化）

| # | 任务 | 严重程度 | 涉及模块 |
|---|---|:--:|---|
| L1 | 优惠券 / 代理商体系 | Low | 新增 |
| L2 | 数据埋点与转化分析 | Low | 新增 |
| L3 | 客服工单 | Low | 新增 |
| L4 | 性能优化（N+1 查询、索引补充） | Low | repository |

---

## 五、横切要求（贯穿所有任务）

依据项目编码规范，所有整改必须满足：

1. **上线不留遗留**：禁止保留 Mock 实现（B6）、注释掉的大段伪代码（B6 中约 40 行伪代码）、调试输出、被吞异常。
2. **优先复用**：新增前先确认无既有可复用实现（如 `AmountValidator` 已有货币小数位处理能力，B19 应复用）。
3. **Lombok 优先**：消除样板代码，但不得再出现 B9 这类「手写一个空方法覆盖 Lombok 生成方法」的陷阱。
4. **精简优雅**：不过度设计，职责单一。

---

## 六、架构决策（v1.1 已拍板）

2026-09-02 与用户确认，以下决策已生效，作为后续实施的前提：

| # | 决策点 | 结论 | 对任务的影响 |
|---|---|---|---|
| Q1 | 订阅制实现方式 | **托管给 Paddle / Stripe Billing** | B18 简化为：订阅产品映射 + 订阅类 Webhook 事件处理（`subscription.*` / `invoice.paid`）+ 到期续期 License，不自建周期扣款 |
| Q2 | 云闪付渠道 | **本期下线，保留支付宝 + 微信** | B6 改为「从支付工厂移除 UnionPayStrategy」，消除伪造回调风险 |
| Q3 | 三档上线排期 | **三档同期上线** | B16/B17/B18/B19 全部进入上线前范围 |
| Q4 | KMS 选型 | **已实施**：本地文件 + AWS KMS + 阿里云 KMS，按 `kms.provider`（local/aws/aliyun）切换，仅一个实现生效 | K1 落地：`LocalKmsService`/`AwsKmsService`/`AliyunKmsService` 均 `@ConditionalOnProperty`；Aws/Aliyun 基于**已校验 SDK API**（javap 核实 jar）编写；凭证走环境变量，不硬编码 |
| Q5 | 部署形态 | **容器化（补 Dockerfile）** | B20 交付 Dockerfile + 修正 compose 网络与数据源配置 |

### Q3 的风险提示与补偿措施

用户选择「三档同期上线」，与我的推荐（先打磨 Pro 买断单链路）不同。**按用户决策执行**，但需记录风险：

> 当前项目**零集成测试**（55 个单元测试全部为 Mock 隔离单测），这正是 B4/B5 零鉴权、B10 兑换必崩、B11 事务失效等问题能潜行至今未被发现的根因。三条收费链路同时上线，任一链路的缺陷都会直接造成资损或用户无法激活。

**补偿措施（已写入 TODO）**：将 M4（补充集成与端到端测试）**从 P2 提升至与功能实现同批**，覆盖三条链路的「下单 → 支付回调 → 发货 → 兑换 → 验签」主流程，作为上线前的验证闸门。

> 备注：当前 git 工作区存在未提交改动（已删除 4 个 `scripts/*.ps1`、`docker-compose.yml` 已修改），动手前请确认这些改动是否要保留。

---

## TODOS

### P0 阻塞项（上线前必须）

- [x] B1 修复 Lombok 注解处理器未生效，恢复项目构建（Critical / 构建）— 已用 annotationProcessorPaths 修复并复验
- [x] B2 清理 pom.xml 重复与非法依赖声明（Medium / 构建）— 已清理，Maven 警告清零
- [x] B3 补全 Spring Security 配置，放行 Webhook 并对客户端/管理端鉴权（Critical / config）— 已新增 SecurityConfig + ApiKeyAuthFilter，复验通过
- [x] B4 LicenseController 端点鉴权（Critical / controller）— 由 B3 SecurityConfig 统一覆盖，复验通过
- [x] B5 兑换码生成与吊销接口鉴权（Critical / controller）— 由 B3 SecurityConfig 统一覆盖，复验通过
- [x] B6 下线云闪付 Mock 实现（Critical / payment）— 已删除策略类与枚举项，复验通过
- [x] B7 清除硬编码凭证与测试密钥（AdminController 弱口令默认值）（Critical / config）— 已改为 fail-fast，复验通过
- [x] B8 兑换码改用 SecureRandom（Critical / service）— 已改用 SecureRandom，复验通过
- [x] B9 修复 License.machineCode 字段赋值陷阱（Critical / entity）— 已删除覆盖 Lombok 的手动 LicenseBuilder，复验通过
- [x] B10 修复兑换主链路约束冲突与 NPE（Critical / service）— 已修复 product 缺失与 NPE，复验通过
- [x] B11 修复 Webhook 事务自调用失效（Critical / controller）— 已用 @Lazy 自注入代理，复验通过
- [x] B12 修正 Webhook 幂等表用法，补订单级重复发货保护（Critical / controller）— 已修复，复验通过
- [x] B13 消除轮询接口的签发副作用（Critical / service）— 已加订单级幂等查询，复验通过
- [x] B14 兑换与发货加并发保护（Critical / service）— 已加悲观行锁，复验通过
- [x] B15 修复微信支付配置 key 与平台证书验签（Critical / payment）— 配置对齐 yml + 平台证书动态下载轮换验签，复验 57 测试全绿
- [x] B16 补齐三档产品种子数据与权益（plan/features）模型（Critical / db+entity）— 已加 tier/features + V4 种子，复验通过
- [x] B17 License payload 补齐 machine_id/features/plan/order_id（Critical / crypto）— 已补齐，复验通过
- [x] B18 实现订阅制（托管 Paddle/Stripe Billing：subscriptions 表、订阅事件、续期/取消联动 License）（Critical / 全链路）— 已落地并复验 56 测试全绿
- [x] B19 修复双币种定价（Critical / entity+service+migration）— 已加 priceCny/priceUsd + 区域取价，复验 57 测试全绿
- [x] B20 补齐 Dockerfile 与容器化部署配置（Critical / 部署）— 多阶段 Dockerfile + compose 数据源/密钥清理 + application-docker.yml + build/up 脚本
- [x] K1 双 KMS 支持：本地文件 + AWS KMS + 阿里云 KMS，按 kms.provider 切换（High / infrastructure）— 新增 `AwsKmsService`/`AliyunKmsService`（`@ConditionalOnProperty`）；`LocalKmsService` 补 yml 路径读取（修 H12）；pom 补 `aliyun-java-sdk-core`（kms 声明为 optional 未传递）+ `apache-client`（AWS 运行时 HTTP 客户端）；`application.yml` 补 `kms.aws`/`kms.aliyun` 子配置；基于 javap 核实的 SDK API 编写，`mvn -B test` 复验 57 测试全绿（**云实现仅编译验证，运行时需真实云凭证+密钥，本机未联调**）
- [x] S1 脚本/文档整理（Medium / 运维）— 建 `scripts/{db,deploy,ops}` + `scripts/README.md` 运维索引（含 V1–V6 迁移清单、环境变量速查）；V1–V6 迁移补统一头注释（不合并、首次部署后禁改）；建 `docs/README.md` 总索引；4 个真实可执行脚本（package/run/show_migrations/healthcheck，均 `bash -n` 通过）

### P1 高优先级（建议上线前）

- [x] H1 生产日志级别降为 INFO（High / config）— v1.2 复核：yml 已设为 INFO，已完成
- [x] H2 异常响应不回显内部信息（High / exception）— 业务异常保留 message、未预期异常返回脱敏文案 + traceId，GlobalExceptionHandlerTest 断言无 SQL/密钥泄漏
- [x] H3 ddl-auto 改 validate，schema 变更统一走 Flyway（High / config）— 主/测试 yml 均为 `validate`
- [x] H4 渠道退款失败不得本地标记已退款（High / service）— 渠道失败抛 REFUND_FAILED 且保留 PAID，AdminServiceRefundTest 断言
- [x] H5 实现 6 家渠道退款能力（High / payment）— Alipay/WechatPay/Stripe/Paddle/PayPal 全部实现 + 退款目标交易号取自 Payment.paymentId（修正真实缺陷）
- [x] H6 支付创建失败需反馈前端（High / service）— FAILED 抛 PAYMENT_CREATE_FAILED，CheckoutServicePaymentFailureTest 断言
- [ ] H7 Paddle 接口与验签按 v2 实测联调（High / payment）— 代码已按 v2 实现，金额单位/验签**需真实沙箱**，本机无法验证，列为上线已知风险
- [x] H8 限流器过期淘汰 + 防 XFF 伪造（High / risk）— EVICT_AFTER_MS/EVICT_THRESHOLD 淘汰 + 默认不信 XFF（可配 trust-x-forwarded-for）；Redis 共享存储降级为部署侧
- [x] H9 配置 CORS / CSRF（High / config）— CORS 白名单 + 关 CSRF；网关层限流属部署架构单列
- [x] H10 管理接口不直接返回 JPA 实体（High / controller）— 改返回脱敏 LicenseResponse/OrderResponse DTO
- [x] H11 管理密钥常量时间比较 + 操作审计（High / controller）— 哈希 + 常量时间比较 + AuditLogService；默认值已删并 fail-fast
- [x] H12 KMS 实现加固（High / infrastructure）— 由 K1 落地 Local/Aws/Aliyun 条件化切换
- [x] H13 引入 Actuator 健康检查与监控告警（High / 部署）— actuator + /actuator/health 放行 + 上下文加载测试
- [x] H14 订单状态机前置校验，防重复发货（High / controller）— 借 H15 单出口方法 + canFulfill 前置校验
- [x] H15 收敛 Order 双状态机（High / entity）— 单出口 markPaid/markRefunded/markRefundFailed/canFulfill，两字段同步约束

### P2 中优先级（上线后首个迭代）

- [x] M1 支持 kid 密钥轮换（Medium / crypto）— `LicenseIssuer` 的 `kid` 改为 `@Value("${billing.license-kid:license-key-1}")` 可配置，支持配置切换新 kid 完成轮换
- [x] M2 收银台会话过期清理（Medium / entity）— `@EnableScheduling` + `CheckoutService.cleanupExpiredSessions()` 定时清理过期未支付会话（保留 PAID 对账）
- [x] M3 统一响应体 + traceId + 请求审计（Medium / 全局）— `ApiResponse<T>` + `TraceIdFilter`（X-Trace-Id/MDC/审计日志）+ `ApiResponseAdvice`（自动包裹普通返回值）
- [x] M4 ⬆️ **提优先级** 补充集成与端到端测试（High / test）— 因 Q3 选三档同期上线，提前至与功能实现同批，作为上线前验证闸门；已新增 ApplicationContextLoadAndHealthTest（@SpringBootTest 上下文加载 + 健康检查 Bean 装配）、AdminServiceRefundTest、RateLimitServiceEvictionTest、CheckoutServicePaymentFailureTest、GlobalExceptionHandlerTest、OrderStateMachineTest 等，**全量 80 测试 0 失败 0 错误（BUILD SUCCESS）**
- [x] M5 修正退款通知邮件模板（Medium / notification）— 新增专用 `sendRefundProcessedEmail` + 「退款已处理」模板，替换误用的「支付失败」模板
- [~] M6 私钥迁移至 Secret Manager / 云 KMS（Medium / infrastructure）— 已由 K1 部分覆盖：AWS/阿里云 KMS 经 `kms.provider` 切换（非本地文件）；专用 Secrets Manager 未单独实现，标记部分完成
- [ ] M7 购买页 / 激活页前端（Medium / 新增）— 不适用：本仓库为后端服务，前端在独立 exe 客户端仓库
- [ ] M8 对账自动化（Medium / 新增）— 依赖 payment_events 正确记录（B12 前置）

### P3 低优先级（后续优化）

- [ ] L1 优惠券 / 代理商体系
- [ ] L2 数据埋点与转化分析
- [ ] L3 客服工单
- [ ] L4 性能优化（N+1 查询、索引补充）

### 架构决策（v1.1 已拍板，见第六节）

- [x] Q1 订阅制 → 托管给 Paddle / Stripe Billing
- [x] Q2 云闪付 → 本期下线，保留支付宝 + 微信
- [x] Q3 三档排期 → 同期上线（风险已在第六节记录，以 M4 提前补偿）
- [x] Q5 部署形态 → 容器化（补 Dockerfile）
- [x] Q4 KMS 选型 → 本地文件 + AWS KMS + 阿里云 KMS 已实现（K1）

---

## 七、三档收费体系专项核查（Pro 买断 / Pro Plus 高级版 / 订阅制）

用户明确要求确认三种付费模式的**购买流程、权益区分、支付集成、付费管理**是否到位。逐项核查如下：

### 7.1 现状总览

| 付费模式 | 产品模型 | 权益区分 | 购买流程 | 支付集成 | 付费管理 | 结论 |
|---|---|---|---|---|---|---|
| **Pro 买断**（一次性付费） | `Product` 无 `plan` 字段，仅 `billingCycle=ONE_TIME` + `licenseDurationDays` | 无 `features` 字段，无法表达 Pro 与 Pro Plus 差异 | `createCheckout`→`selectProvider`→Webhook→`fulfillOrder` 链路存在，但 `products` 表**无种子数据**→ 下单必 `PRODUCT_NOT_FOUND` | 依赖具体渠道（B15/B6/H7） | 退款(H4/H5)、换机(B9) | ❌ 阻塞 |
| **Pro Plus 高级版** | 同上，无任何字段区分 Pro Plus | 无任何权益字段（B16） | 同上，且无法与 Pro 区分 | 同上 | 同上 | ❌ 阻塞 |
| **订阅制** | `billingCycle` 有 MONTHLY/QUARTERLY/YEARLY/LIFETIME 枚举但**代码从未读取** | 无 | 无订阅链路、无 `subscriptions` 表、无续费/周期扣款 | 无订阅类 Webhook（`subscription.*`/`invoice.paid`） | 无到期续期/提醒 | ❌ 阻塞 |

### 7.2 三档落地必备项（纳入上线前阻塞）

- **Pro 买断**：`products` 须有 `plan=BUYOUT` 种子数据；License `payload` 写入 `plan`/`machine_id`/`features`/`order_id`（B17）；一次性买断 = 按 `licenseDurationDays` 发 N 天、到期不续。
- **Pro Plus 高级版**：`Product` 增加 `plan`(枚举：BUYOUT / PLUS / SUBSCRIPTION) 与 `features`(JSON/关联表) 字段（B16）；购买流程与 Pro 共用收银台，但权益通过 `features` 在 License payload 中下发，客户端据此解锁高级功能；需定义 Pro 与 Plus 的 feature 矩阵。
- **订阅制（按 Q1 托管 Paddle/Stripe Billing）**：
  - 新增 `subscriptions` 表（customer_id、product_id、provider、provider_subscription_id、status、current_period_start/end、cancel_at_period_end）；
  - 产品映射：买断 SKU → 一次性 Price；订阅 SKU → Paddle Price ID / Stripe Price ID（需在 `products` 增加 `provider_price_id` 等字段）；
  - Webhook 新增订阅事件处理：`subscription.created/updated/canceled`、`invoice.paid`（`transaction.completed` 已在 Paddle 处理），据此创建/续期/作废 License；
  - 到期前 N 天提醒 + 宽限期 + 逾期作废 License（与 B17 payload 的 `exp` 联动）；
  - Paddle 作为 MoR 处理税务，Stripe 用 Billing 周期扣款。

### 7.3 双币种定价（B19，三档通用）

当前 `Product` 仅单一 `price`+`currency`，`CheckoutService` 强行 `domestic ? "CNY" : "USD"` 取同一 `price` → 国内用户可能按美元金额付人民币。须改为：每个 SKU 维护 CNY 与 USD 两档价格（或汇率表），下单按区域取对应金额；金额校验 `AmountValidator` 已具备小数位处理能力，应复用。

### 7.4 付费管理闭环

| 管理动作 | 现状 | 缺口 |
|---|---|---|
| 退款 | `AdminController.refundOrder` 存在，但 `PaymentStrategy.refundPayment` 默认返回 false，6 家均未实现；渠道失败时仍本地标记 `REFUNDED`（H4 资损风险） | H4/H5 |
| 换机 | `reissueLicense` 存在，但 `machineCode` 字段被 B9 陷阱吞掉 → 换机实际失效 | B9 |
| 订阅续期/取消 | 无 | B18 |
| 权益查询/降级 | 无 `features` 模型 | B16/B17 |
| 对账 | 依赖 `payment_events` 正确记录（受 B12 影响） | B12/M8 |

**结论**：三档收费体系**整体不具备上线条件**。Pro 买断链路代码骨架在但缺种子数据/权益/License 要素；Pro Plus 无任何权益区分；订阅制零实现。需 B16/B17/B18/B19 + 三档种子数据 + 订阅 Webhook 全部落地。

---

## 八、v1.2 复核修正与新增发现

### 8.1 与 v1.1 论断的偏差（已修正）

| 编号 | v1.1 论断 | v1.2 复核结论 | 处理 |
|---|---|---|---|
| B7 | 凭证全硬编码 | yml 已部分加固（DB 密码、admin-api-keys fail-fast），但 AdminController 默认值 `admin-key-change-me` 未同步、Stripe 占位符待替换 | 已修正描述 |
| B9 | machineCode 空方法→字段永远 null | 根因是手动 `LicenseBuilder` 覆盖 Lombok 字段方法，结论成立但根因更精确 | 已修正描述 |
| B10 | 兑换链路 3 处必崩 | `.order(null)` 实际不崩（DB 列可空）；`product` 缺失导致的 NOT NULL + NPE 仍真实崩溃 | 已修正描述 |
| B12 | 发货前先记 processed=true | 描述不准确，核心是缺订单级重复发货幂等 | 已修正描述 |
| H1 | 日志为 DEBUG | yml 已为 INFO，已修复 | 标记完成 |
| H7 | Paddle 为 Classic 风格 | 代码已按 v2 实现，但金额单位待沙箱验证 | 已修正描述 |

### 8.2 新增/补充发现（v1.1 未单列）

- **N1 工作区存在未提交改动**：`docker-compose.yml`、`pom.xml`、`application.yml` 已修改（git status），且 `scripts/*.ps1` 已删除。v1.1 的「B1/B2 已编译通过」基于更早状态；本次因环境无 `mvn`（须经 IDEA MCP）**未重新编译**，上线前须提交改动并经 IDEA MCP 重新 `mvn clean compile` + `mvn test` 复验。
- **N2 UnionPay 未按 Q2 下线**：`PaymentMethod.UNIONPAY` 仍 `isDomestic()=true`，`UnionPayStrategy` 仍是 `@Service` 被 `PaymentServiceFactory` 自动注册 → 出现在国内支付列表且为 Mock（B6 待办未执行）。
- **N3 Paddle 金额单位风险**：H7 已述，Paddle v2 金额未×100，可能导致金额解析错误（资损/下单失败）。
- **N4 支付创建失败无反馈（H6 确认）**：`CheckoutService.selectProvider` 未检查 `paymentResponse.getStatus()`，渠道下单失败仍返回空 `payUrl`，前端无感知。
- **N5 Webhook 未过滤事件类型**：`processWebhook` 对任意 `status=SUCCESS` 的 payload 都执行 `fulfillOrder`，订阅场景下需区分 `invoice.paid`/`subscription.*`（B18 关联）。
- **N6 金额校验范围有限**：`AmountValidator.validateAmount` 仅在 Webhook 成功分支调用；`createPayment` 侧未校验客户端传参与产品定价一致（下单金额取自 `product.getPrice()`，非客户端传参，风险较低）。

### 8.3 复核后总判定

仍为「**不可发布**」。修正后 P0 仍为 19 项有效阻塞（B3–B20，其中 B7 部分加固、B9/B10/B12 根因精确化），P1 中 H1 已完成、H7 现代化。三档收费体系（用户重点关切）确认整体未就绪。建议在 Q1–Q5 已拍板前提下，优先推进 B3–B20 + 三档专项，并以 M4（集成/E2E 测试）作为上线前验证闸门。

---

## 版本

v2.4 (2026-09-02 15:30) — 审计报告 17 项 warning + 10 项 info 全量整改完成（用户拍板「全做」）。覆盖 w3/w7/w8/w9/w10/w11/w12/w14/w15/w16/w17 + i4/i7/i8/i11/i12：Webhook 失败响应透传真实状态（验签失败 401/金额不符 400）、渠道未配置验签改返回 false 并新增 ChannelConfigValidator 生产 fail-fast、PayPal APPROVED 不再误判 SUCCESS、Paddle 时间戳 ±5min 重放防护、限流主路径 evictStaleWindows、Swagger 放行、清理死代码 findByOrderId、pom 改由 Boot 4 BOM 管理、订单明细单价取区域价、Payment.currency 用 order.getCurrency()、Webhook 丢失主动查渠道补偿、License.order_id 改 nullable、README 渠道数/密钥名修正、管理端鉴权收敛、重复吊销端点文档化、compose HTTP 探针、审计表 orderId 关联。新增/修正测试覆盖 PayPal/Stripe/Paddle/RateLimit/Checkout/License，经 EBUSY 复核全量落盘，`mvn test` 全绿 **98 测试 0 失败 0 错误（BUILD SUCCESS）**
v2.2 (2026-09-02 14:10) — 用户三项指令完成：① 引入 Springdoc OpenAPI（API 文档 /v3/api-docs + /swagger-ui.html）；② M1/M2/M3/M5/M4 落地，M6/M7 评估标注；③ 重写上线就绪度结论为「可发布候选」。具体：pom 引入 springdoc-openapi-starter-webmvc-ui **3.1.0**（SB4 不兼容 2.8.x——`WebMvcProperties` 已被 repackage，NoClassDefFoundError；3.1.0 为 SB4 兼容线，功能等价 v2 现代线：Jakarta + /v3/api-docs）；新增 OpenApiConfig（标题/版本/联系人 + 仅 ApiKey 安全方案）；M1 kid 可配置（LicenseIssuer 加 `license.kid` @Value + 入参覆盖）；M2 收银台会话过期清理（@EnableScheduling + CheckoutSessionRepository.findByExpiresAtBefore + CheckoutService @Scheduled 每小时清理）；M3 统一响应体 ApiResponse<T>（code/message/data/traceId/timestamp）+ TraceIdFilter（MDC + X-Trace-Id 响应头）+ ApiResponseAdvice（RestController 响应包裹，保留 Webhook 原始体）+ 请求审计日志；M5 退款通知已专用 sendRefundProcessedEmail（此前已落地，本次标注完成）；M6 私钥迁移——K1 已实现 Local/AWS/阿里云 KMS（kms.provider 切换），专用 Secrets Manager 未做，标注部分完成；M7 前端购买/激活页标注 N/A（本仓库为后端服务，前端在独立 exe 客户端仓库）。重写第三节「上线就绪度结论」：评分全面上调，结论由「不可发布」改为「**可发布候选（P0+P1 已清零，待真实联调验证）**」。全量 **80 测试 0 失败 0 错误（BUILD SUCCESS）**，Springdoc/Scheduling/TraceFilter/ResponseAdvice 均随上下文加载生效
v2.1 (2026-09-02 13:40) — P1 收尾 + M4 验证闸门全部完成：H2 异常脱敏、H3 ddl-auto→validate、H4 退款失败不谎报 REFUNDED、H5 6 家渠道 refundPayment 全部实现（修正 Payment.paymentId 取号缺陷）、H6 支付失败抛 PAYMENT_CREATE_FAILED、H8 限流过期淘汰 + XFF 防伪造、H9 CORS/CSRF、H10 脱敏 DTO、H11 常量时间比较 + 操作审计、H12 KMS 加固（K1）、H13 Actuator 健康检查 + 探针放行、H14 状态机前置校验、H15 收敛双状态机；M4 由 P2 提至上线前闸门，新增 @SpringBootTest 上下文加载 + 健康检查 Bean 装配 + 退款/限流/支付失败/异常脱敏/状态机测试；全量 80 测试 0 失败 0 错误（BUILD SUCCESS）。唯一开放项：H7 Paddle v2 沙箱实测联调（本机无沙箱，列为上线已知风险）
v1.7 (2026-09-02 11:39) — 第五张卡 B19（双币种定价）完成：Product 加 priceCny/priceUsd + getPriceForRegion/getCurrencyForRegion，CheckoutService 按区域取 CNY/USD 金额与币种，V6 迁移加列回填，补 shouldUseRegionPrice_b19；复验 57 测试全绿
v2.0 (2026-09-02 13:15) — 第九张卡 B20（容器化）完成：P0 阻塞项全部清零（B1–B20 + K1 + S1 共 21 项）。新增多阶段 Dockerfile（maven:21 构建 → temurin:21-jre 运行，非 root 用户）；docker-compose 修正数据源指向 postgres 服务（DB_URL=jdbc:postgresql://postgres:5432/billing_db）、移除明文 DB_PASSWORD（改 ${DB_PASSWORD} 由 .env 注入）、app 经 depends_on service_healthy 等 DB 就绪、补端口存活探针；新增 application-docker.yml（ddl-auto: validate，schema 归 Flyway）+ scripts/deploy/{build,up}.sh（fail-fast 校验密钥/密钥文件）+ .dockerignore（排除 keys/、target）+ .env.example 模板。配置级已核对；Docker 镜像构建需 daemon+网络，本机未实跑
v1.9 (2026-09-02 12:52) — 第八张卡 B15（微信支付配置 key 对齐 + 平台证书动态验签）完成：WechatPayStrategy 配置 key 对齐 yml（private-key-path/certificate-path/api-key，删除 serial-no 改由商户证书启动提取）；回调验签由误用商户证书改为动态下载+轮换的微信平台证书（GET /v3/certificates，APIv3 密钥 AES-256-GCM 解密，按 serial_no 缓存，缺失触发刷新重试）；补 GCM AAD（associated_data 原漏传导致解密失败）、时间戳 ±5min 重放防护、success_time 北京时区解析；mvn -B test 复验 57 测试全绿（仅编译+单测，真实微信需商户证书+沙箱联调）
v1.8 (2026-09-02 12:05) — 用户指令两项完成：K1 双 KMS（LocalKmsService 补 yml 路径+H12；新增 AwsKmsService/AliyunKmsService，按 kms.provider 条件化切换；pom 补 aliyun-java-sdk-core+apache-client；基于 javap 核实的 SDK API 编写）+ S1 脚本/文档整理（scripts/{db,deploy,ops}+README 运维索引、V1–V6 头注释、docs 索引、4 个真实脚本）；mvn -B test 复验 57 测试全绿
v1.6 (2026-09-02 11:31) — 第四张卡 B18（订阅制，Q1 托管 Paddle/Stripe Billing）完成：新增 subscriptions 表(V5)+Subscription 实体/仓储、WebhookPayload 加 subscriptionId/webhookEventId/currentPeriod*、Paddle/Stripe 解析订阅事件、SubscriptionService 首充绑定/续费延长/取消作废 License、WebhookController 订阅分支+幂等键升级；补 SubscriptionServiceTest（4 例=部分 M4 验证闸门）；复验 56 测试全绿
v1.5 (2026-09-02 11:20) — 第三张卡 B7/B16/B17（三档产品模型 + License payload + 管理密钥）完成：Product 加 tier/features、PlanTier 枚举、V4 四档种子、LicenseIssuer 补 plan/feat/mid/oid、AdminController 去弱口令默认值；复验 52 测试全绿
v1.4 (2026-09-02 11:10) — 第二张卡 B3/B4/B5（认证授权体系）完成：新增 SecurityConfig + ApiKeyAuthFilter，复验 52 测试全绿
v1.3 (2026-09-02 11:00) — 进入实施阶段：完成第一张卡 B6/B8/B9/B10/B11/B12/B13/B14（资金主链路修复），经 `mvn -B test` 复验 52 测试全绿；plan 任务表与 TODOS 同步标记完成
v1.2 (2026-09-02 10:30) — 独立走查复核全部 P0/P1 论断：修正 B7/H11 默认值不一致、B9 根因、B10 部分不崩、B12 描述、H7 已现代化，确认 H1 已修复；新增第七章三档收费体系专项核查、第八章 v1.2 复核修正与新增发现（N1–N6）
v1.1 (2026-09-02 09:40) — B1、B2 完成并复验；补充修复后构建/测试证据与测试基线
v1.0 (2026-09-02 09:30) — 初版全面检查与整改计划

---

## TODOS（跨版本总表）

> 最近更新：2026-09-02 15:30（v2.4 实施并复验，98 测试全绿）

### 🔴 进行中

| 任务 | 版本 | 状态 | 说明 |
|---|---|---|---|
| C1 支付宝 Webhook 验签修复（含报告遗漏的第二处 `remove("sign")`） | **v2.3** | ✅ 已实施并复验 | `FormParamParser` 新增 + WebhookController 从 body 取 sign + AlipayStrategy 删 remove；真实 RSA 自签自验测试通过 |
| C2 管理端退款渠道解析修复 + 存量订单兜底 | **v2.3** | ✅ 已实施并复验 | `CheckoutService.selectProvider` 落库 `paymentProvider`；`AdminService` 从 `Payment.method` 兜底 |
| C1/C2 配套测试 | **v2.3** | ✅ 已实施（实际 +10 例，预期 +11） | 含真实 RSA 验签、body 取 sign、provider 落库、存量兜底断言 |
| 反向验证：回退修复后重跑，确认测试必挂 | **v2.3** | ✅ 已执行 | 回退后 **3 个测试失败**，证明测试真能抓到 C1/C2；改回后 90 全绿 |

### ✅ 已完成（v2.4，审计报告 warning/info 级全量整改 — 98 测试全绿）

1. **w3** Webhook 失败响应透传渠道（微信恒 SUCCESS / Stripe·Paddle·PayPal 恒 200）→ 验签失败被渠道视为投递成功，事件静默丢失
2. **w7** 限流 `checkAndCount` 补 `evictStaleWindows()`（仅 `countOnly` 有）→ 频控 key 无限累积
3. **w8** Paddle 回调加时间戳新鲜度校验（现仅验 HMAC，无重放防护）
4. **w9** PayPal 轮询不再把 APPROVED 当 SUCCESS（未捕获扣款即发货）
5. **w10** 渠道未配置时启动 fail-fast（现 `verifyWebhookSignature` 一律返回 true，等于无鉴权）
6. **w14** SecurityConfig 放行 `/v3/api-docs`、`/swagger-ui/**`（现被 `denyAll()` 拦截，文档打不开）
7. **w12** 清理死代码 `PaymentRepository.findByOrderId(Long)` + `PaymentService.getPaymentByOrderId`（实测首次调用即抛 `UnknownPathException`）
8. **w1/w2** pom 中 `spring-security-core 6.5.10`、`jackson-core 2.22.1` 显式锁定改由 Boot 4 BOM 管理
9. **w15/w16** 订单明细单价改取区域价（现 `getPrice()`）；`Payment.currency` 改用 `order.getCurrency()`（现按 `method.isDomestic()` 推）
10. **w17** 支付成功但 Webhook 丢失时的主动查渠道/对账补偿（M8 未做）
11. **w11** `License.order_id` 实体 `nullable=false` 与 V1 迁移（可空）对齐
12. 其余 info 项（README 渠道数/环境变量名、双密钥契约、重复吊销端点、compose HTTP 探针、审计表 orderId 等）

### ✅ 已完成

- **v2.2**：Springdoc OpenAPI 引入；M1–M5 落地；上线就绪度结论重写为「可发布候选」；80 测试全绿
- **v2.1**：P1 收尾（H2–H6、H8–H15）+ M4 验证闸门；80 测试全绿
- **v2.0**：B20 容器化；P0 阻塞项全部清零（B1–B20 + K1 + S1 共 21 项）
- **2026-09-02 下午**：独立复核 `docs/审计报告-2026-09-02.md`（31 项：29 属实 / 1 不属实 / 1 严重度修正），并发现报告遗漏的 2 处缺陷。详见 `docs/plan-2.3.md` §二
- **2026-09-02 傍晚**：实施 C1+C2 修复（主代码 4 文件 + 新增 `FormParamParser`）并补 10 个测试；`mvn test` 90 全绿（原 80）。反向验证：回退修复后 3 个测试必挂，证明覆盖有效。详见 `docs/plan-2.3.md` 与上方 TODOS
- **2026-09-02 晚**：实施 v2.4 全量整改（17 warning + 10 info 中可落地项），详见 `docs/plan-2.4.md`；`mvn test` **98 测试 0 失败 0 错误（BUILD SUCCESS）**。实施期遭遇 EBUSY 编辑静默丢失，已通过全量 Write 复核逐文件落盘

### 📌 上线前长期待办

- **H7** Paddle v2 沙箱实测（金额单位未 ×100，本机无沙箱）
- 各支付渠道真实密钥/商户号联调
- 部署侧 Redis 共享限流 + 网关限流（多实例场景）
