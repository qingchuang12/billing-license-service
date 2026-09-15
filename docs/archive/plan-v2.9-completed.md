# 归档 · plan v2.9 主题 G「文档-代码一致性收口」（已完成）

> 来源：活动 plan [`docs/plan-2.9.md`](../plan-2.9.md) 的第二阶段主题 G（v2.9）。
> 归档时间：2026-09-14
> 归档原因：G1–G7 全部完成，按归档规则「已完成项不留活动 plan」整批移出，活动 plan 只保留 T1–T4 与 W17。
> 复核方式：下列「核验证据」均可按路径/端点直接复核；测试数经 `mvn test` 实跑确认。

## 一、背景（问题清单）

对 `docs/` 与实现做全量核对，发现 **3 类 14 处不一致**：

- **A 类（索引/运维文档过期）**：`MAIN.md`（plan 版本 v2.5、归档范围 v2.0–v2.4、迁移 V1–V6）、`README.md`（发布状态「可发布候选」、多算法支持夸大、管理端 `X-API-Key`、退款端点带 `v1`、`payment.provider` 不存在、测试数 98、项目结构缺包）、`scripts/README.md`（迁移 V1–V6、healthcheck「actuator 待 H13」、引用已归档卡片）均落后于代码。
- **B 类（设计稿被当现状文档）**：`架构设计.md`、`业务流程.md` 实为方案建议稿（首句「下面给你…方案」），却被 `MAIN.md` 索引为「产品与架构文档」，且无「已实现/未实现」标注——其中 License payload 字段名与实现**完全不同**，客户端最易踩坑。
- **C 类（文档承诺被代码架空）**：文档四处宣称 DB 口令/管理密钥「缺失即启动失败（fail-fast）」，实际 `application.yml` 留有硬编码默认值；配置注释中还残留跳板机命令与服务器信息。

## 二、处置口径（2026-09-14 用户确认）

1. 两篇设计稿：**保留原文 + 文首加「设计建议 · 非实现现状」声明 + 与实现的偏离清单**（不按实现重写，保留路线图参考价值）；
2. A/C 类全修，含代码内注释；配置默认值**直接删除**使 fail-fast 真正生效；
3. 活动 plan 改名 `plan-2.9.md`（符合「`plan-<x.y>.md`」规范）；
4. `README.md` 客户端示例按实际算法（默认 Ed25519）修正。

## 三、完成记录

| 项 | 内容 | 产出（文件） |
|---|---|---|
| G1 | 索引类文档对齐实现 | `docs/MAIN.md`（plan v2.9 / 归档 v1.0–v2.8 / 迁移 V1–V8 / 命名规范 / 设计稿标注）；`docs/README.md`（发布状态、多算法集合、邮件通知、双 header 鉴权、`/api/admin/...` 退款端点、`payment.enabled-channels`、项目结构与 V1–V8、客户端示例、测试数）；`scripts/README.md`（V1–V8 清单含 V7/V8、H13 已完成、去掉已归档卡片引用） |
| G2 | 设计稿加状态声明 | `docs/架构设计.md`、`docs/业务流程.md` 文首声明 + 偏离清单（云闪付/银联、聚合支付、Redis、邮件已实现、License payload 字段名、兑换接口、命名风格） |
| G3 | 配置默认值收口 | `src/main/resources/application.yml`：删除 `${DB_PASSWORD:…}`、`${ADMIN_API_KEYS:…}` 默认值；删除注释中的跳板命令与服务器信息，默认 `url` 端口回到 5432（与 `scripts/README.md` 一致） |
| G4 | 代码内过期注释 | `application.yml` Flyway 注释「跑 V2-V7」→ V2–V8；`scripts/ops/healthcheck.sh` 去掉「待 H13 落地」「建议补齐 H13」 |
| G5 | 客户端示例对齐算法 | `docs/README.md`：JS 改为 `crypto.createPublicKey` + `crypto.verify(null,…)`（Ed25519）并按 header `alg` 分支；Java 示例按 `alg` 分支；「多算法支持」收敛为实际集合 |
| G6 | 测试数校正 | 实跑 `mvn test`：**126 tests, 0 failures, 0 errors, BUILD SUCCESS**（2026-09-14），回填 README（原文写 98） |
| G7 | plan 改名 | `docs/plan.md` → `docs/plan-2.9.md`，`MAIN.md` 索引同步 |

## 四、核验证据（关键事实，供复核）

- **迁移脚本实为 8 个**：`V1__initial_schema` … `V8__audit_log`（含 `V7__webhook_idempotency_and_cooldown.sql`：`payment_events(provider,event_id)` 唯一约束 + `checkout_sessions.last_compensated_at`）。
- **管理端鉴权**：`AdminController` 自校验 `X-Admin-API-Key`（`AdminController.java:40,52,97`）；`SecurityConfig.java:66` 对 `/api/admin/**` 放行；特权端点为 `X-API-Key`（`SecurityConfig.java:36,67-69`）。
- **退款端点**：`POST /api/admin/orders/{orderNumber}/refund`（`AdminController.java:151`）。
- **渠道配置**：`payment.enabled-channels`（留空=按配置齐全度自动启用），不存在 `payment.provider`；渠道标识 `alipay/wechat_pay/stripe/paddle/paypal`，配置块名 `alipay/wechat/stripe/paddle/paypal`。
- **邮件已实现**：`service/notification/EmailNotificationService`（`@Async`，未配置 SMTP 时跳过）+ pom `spring-boot-starter-mail`。
- **License 签名 payload 字段**：`lic/cid/sku/plan/feat/mid/oid/iat/exp/meta`（`infrastructure/crypto/LicenseIssuer#issueLicense`）；JWS `alg` 映射：`Ed25519→EdDSA`、`EC→ES256`、`RSA→RS256`，AWS 路径可为 `ES256/ES384/ES512/RS256`（`LicenseIssuer#getJwsAlgorithm`、`AwsKmsService`）。
- **兑换接口**：`POST /api/v1/redeem/redeem`，请求体 `{code, customerId, machineId}`（`controller/RedeemCodeController.java:96`、`dto/RedeemCodeRequest.java`）。
- **G3 后测试仍全绿**：测试走 `src/test/resources/application-test.yml`（H2 + `security.admin-api-keys`），不受默认值移除影响 —— 已用 `mvn test` 实跑复核。

## 五、遗留（未纳入本次范围）

1. `架构设计.md` / `业务流程.md` **未按实现重写**（刻意保留原始建议稿）；对外交付前需重写或明确标注为设计建议。
2. **单实例限制未显著强调**：限流与发放锁均为进程内实现，水平扩容即击穿；`审计报告-2026-09-04.md` 已提出「部署文档需显著强调」，`scripts/README.md` 目前仅有关联排错条目，尚未显著标注（历史遗留，未随本次清理）。
3. 客户端示例仅覆盖 Java / Node.js，未提供 .NET、C++ 版本。
4. `docs/archive/` 内历史文档中的过期引用（如 `审计报告-2026-09-02.md` 的 `plan.md` 链接）按「归档不追改」原则保持原样。

---

# 主题 H · 契约分歧判定与双侧修复（2026-09-14）

> 触发：用户指出 `docs` 与代码在 API 契约层不一致（例：`POST /api/orders`），并要求**先判定文档与代码哪一侧更合理**，不得只改文档。
> 结论：9 处分歧 → **6 处改代码、2 处改文档、1 处两侧都有问题**；用户拍板 D1–D4 后全部落地。

## 一、判定表（附可复核依据）

| # | 分歧 | 判定 | 依据 | 处置 |
|---|---|---|---|---|
| 1 | webhook 聚合入口 `POST /api/webhooks/domestic` | 代码更合理 | 支付宝 RSA2 表单验签 vs 微信 APIv3 AES-GCM 解密，机制不同，聚合无意义 | 改文档 |
| 2 | `/api/licenses/{id}/reissue` 客户端自助 | 代码更合理 | 自助换机需身份验证（当前无机制）；admin + `checkMachineReissue` 频控 + 重发上限更稳 | 改文档 |
| 3 | 订单单状态机 `draft/pending_payment/…` | 代码更合理 | 履约态/支付态分离是标准做法（H15 已收敛唯一出口） | 改文档 |
| 4 | `GET /api/licenses/{license_id}` 不存在 | **代码缺能力** | `verify/{licenseKey}` 对 EXPIRED/REVOKED 直接 400，售后查不到失效件 | 改代码 |
| 5 | README `domestic` 字段 | **文档对、代码错（资损级）** | `CreateOrderRequest` 无区域入参 → `OrderService.java:58-75` 靠 `priceCny != null` 推断币种、金额仍取 `product.getPrice()`（USD 基准）→「币种 CNY + 金额 USD」 | 改代码 |
| 6 | License 枚举 | **两侧都有问题** | 代码无 `REISSUED`，`reissueLicense` 注释写 REISSUED、实现写 `REVOKED`（矛盾）；`SUSPENDED`/`PENDING_ACTIVATION`/`activatedAt` 全库无写入点 | 改代码 + 文档 |
| 7 | 响应壳 | **代码有瑕疵** | Swagger 声明 `CheckoutResponse`、实际 `{success,code,data,…}` → SDK 按文档生成必错；`ApiResponseAdvice` 的 `body instanceof ResponseEntity` 分支不可达 | 改代码 + 文档 |
| 8 | 端点版本前缀混用 | 代码自身不一致 | v1: orders/licenses/redeem；无 v1: checkout/admin/webhooks | D1 决策 |
| 9 | 客户端自吊销端点 | 代码设计可疑 | 要求 `X-API-Key`＝管理密钥，客户端不可能持有；与管理端重复（审计 i8） | D2 决策 |

> **判断更正记录**：上一轮曾判定 README 的 `domestic` 为「无效字段、应从示例删除」，**该结论作废**——实为代码缺失该功能导致的资损级缺陷。

## 二、用户决策（2026-09-14）

- **D1 = 全部去版本**：删除 `/api/v1`，统一 `/api/**`（含 checkout/admin/webhooks）。
- **D2 = 删除**客户端自吊销端点，吊销收敛到管理端。
- **D3 = 完整修复** License 枚举（加 `REISSUED` + 清理死状态/死字段）。
- **D4 = 完整修复** 下单入口（**废弃** `POST /api/orders`，收敛到收银台；不做"保留缺陷方法打补丁"的折中）。

## 三、完成记录

| 项 | 内容 | 改动文件 |
|---|---|---|
| H-C1/H-C6 | 随 D4 根除资损：删除 `OrderService.createOrder`（含 `generateOrderNumber` 与相关字段/import），删除 `OrderController.createOrder` | `service/OrderService.java`、`controller/OrderController.java` |
| H-C2 | 管理端新增 `GET /api/admin/licenses/{licenseKey}`（含失效件，`@Audit` + 脱敏视图） | `controller/AdminController.java`、`service/AdminService.java` |
| H-C3 | 响应壳与 OpenAPI 一致：新增 `billingEnvelopeCustomizer` 把响应 schema 包为 `ApiResponse` 结构（webhook 除外、已包不重复包）；清理 `ApiResponseAdvice` 不可达分支 | `config/OpenApiConfig.java`、`common/web/ApiResponseAdvice.java`、新增 `test/.../OpenApiEnvelopeCustomizerTest.java` |
| H-C4（D1） | 6 个 controller 去 `/api/v1`；`SecurityConfig` 与 `OpenApiConfig`(isPublic/requiresApiKey) 路径同源更新；测试路径同步 | `controller/*`、`security/SecurityConfig.java`、`config/OpenApiConfig.java`、`test/.../OpenApiCustomizerTest.java` |
| H-C5（D2） | 删除 `LicenseController` 客户端自吊销端点（原 `POST /api/licenses/revoke/{licenseKey}`）及 `@Audit` 标注 | `controller/LicenseController.java` |
| H-C6（D3） | `LicenseStatus` 新增 `REISSUED`、删除 `SUSPENDED`/`PENDING_ACTIVATION`；`reissueLicense` 原证置 `REISSUED`（不再置 `REVOKED`、不写 `revokedAt`）；删除死字段 `activatedAt`（实体/DTO + `V9` drop 列）；`LicenseResponse` 补 `lastVerifiedAt`/`reissuedFrom` | `entity/License.java`、`service/LicenseService.java`、`dto/LicenseResponse.java`、新增 `db/migration/V9__license_status_semantics.sql` |
| H-D1 | README 补 **24 个端点**总览表（分组 + 鉴权档位）+ 下单语义修正 | `docs/README.md` |
| H-D2 | 两篇设计稿补「逐端点映射表」（含已删除/不存在端点） | `docs/架构设计.md`、`docs/业务流程.md` |
| H-D3 | README 补「状态枚举（以代码为准）」表 | `docs/README.md` |
| H-D4 | README 补「响应结构（统一响应壳）」+ 常见业务错误码清单 | `docs/README.md` |
| H-D5 | 示例可执行化：SKU `PRO-PLAN` → 真实种子 SKU；下单示例改为收银台三步流程；标注需先注入环境变量 | `docs/README.md` |

**验证**：`mvn -B test` = **129 tests, 0 failures, 0 errors, BUILD SUCCESS**（2026-09-14 17:11，原 126 + 新增 3 个响应壳测试）。

## 四、遗留

1. **`dto/CreateOrderRequest.java` 物理删除未完成**：该 DTO 已无任何引用（代码/测试均不引用，编译与测试不受影响），但删除操作需用户批准执行（工具对该 workspace 外路径的删除受限）。一条命令即可清掉：
   `Remove-Item 'd:\workspace\billing-license-service\src\main\java\com\billing\license\dto\CreateOrderRequest.java'`
2. `models` 层未同步的后端遗留：`Product.getPrice()` 与区域价 `getPriceForRegion()` 并存，`price` 列保留为 USD 基准（`V6` 回填），未在本主题清理。
3. 端点去版本属**破坏性契约变更**：客户端 exe（独立仓库）与前端若已按 `/api/v1/**` 实现，需同步改为 `/api/**`（项目尚未上线，无外部调用方）。

---

# 主题 I · 接口合并简化（2026-09-14）

> 触发：用户要求「检查接口是否过多、能复用的复用、简化使用流程；确认方案后给 plan」（连问三次，故按推荐默认方案直接执行）。
> 结果：**24 → 21 端点**、**鉴权 3 档 → 2 档**、收银台支持一步下单、兑换码生成闭环。

## 一、冗余清单（已核，附证据）

| # | 冗余 | 证据 |
|---|---|---|
| 1 | 鉴权三档实为两档：`X-API-Key`（→ROLE_ADMIN）与 `X-Admin-API-Key`（`AdminController` 自校验）校验**同一份** `security.admin-api-keys` | `SecurityConfig.java:36,67-69`、`AdminController.java:52,58-89` |
| 2 | 订单查询 4 入口：同一资源两个键 + 同一列表两个端点 | `OrderController`、`AdminController.java:94,128` |
| 3 | License 查询 4 入口：customer / 订单下 / 按 key / verify | `LicenseController.java:68`、`AdminController.java:165` |
| 4 | 签发入口 2 处：手动 issue 与自动发放同源 | `LicenseController.java:40`、`CheckoutService.java:268` |
| 5 | 收银台固定两步：`create` 返回 `payUrl=""`，必须再调 select-provider | `CheckoutService.java:151,160` |
| 6 | 兑换码生成后拿不到明文：只返回数量，仓库无按 SKU/状态查询 | `RedeemCodeService.java:89`、`RedeemCodeRepository.java:16-26` |

## 二、完成记录

| 项 | 内容 | 改动文件 |
|---|---|---|
| I1 | 鉴权收敛为单 header `X-API-Key`：`/api/admin/**` 由 `SecurityConfig` 统一 `hasAuthority("ROLE_ADMIN")`；删除 `AdminController` 的密钥哈希/常量时间比较/`ensureAuthorized` 与全部 `@RequestHeader("X-Admin-API-Key")` 参数；`OpenApiConfig` 删第二个 scheme 并把 admin 纳入 `X-API-Key` 分支；CORS 头去 `X-Admin-API-Key` | `security/SecurityConfig.java`、`controller/AdminController.java`、`config/OpenApiConfig.java`、`test/.../OpenApiCustomizerTest.java` |
| I2 | 订单查询收敛为 `GET /api/admin/orders?status=&orderNumber=&orderId=`；`OrderController` 下线（无端点、无类型声明）；`OrderService` 保留为查询/映射服务被 `AdminService` 复用 | `service/AdminService.java`、`controller/OrderController.java` |
| I3 | License 查询收敛为 `GET /api/admin/licenses?customerId=&orderNumber=&status=`；删 `/api/licenses/customer/**`、`/api/admin/orders/{n}/licenses`；删除因之失去引用的 `LicenseService.getLicensesByCustomer` 与 `AdminService.listLicensesByOrder`（不留死代码） | `controller/LicenseController.java`、`service/LicenseService.java`、`service/AdminService.java` |
| I4 | 签发归口：`POST /api/admin/orders/{orderNumber}/issue`（复用 `issueLicensesForOrder`，幂等），删 `/api/licenses/issue/{orderId}` | `controller/AdminController.java`、`controller/LicenseController.java` |
| I5 | 兑换码管理归口 + 明文：`POST /api/admin/redeem-codes/generate`（`generateCodes` 改返回 `List<String>` 明文）、`POST /api/admin/redeem-codes/revoke/{code}`；删 `/api/redeem/generate|revoke`；`/api/redeem/redeem` 保持公开 | `service/RedeemCodeService.java`、`controller/RedeemCodeController.java`、`controller/AdminController.java` |
| I6 | 新增导出/对账 `GET /api/admin/redeem-codes?productSku=&status=`（新增 `RedeemCodeView` DTO + 仓库 `search(sku,status)` 查询） | `dto/RedeemCodeView.java`、`repository/RedeemCodeRepository.java`、`controller/AdminController.java` |
| I7 | 收银台一步下单：`CheckoutRequest` 增可选 `provider`；`createCheckout` 带 provider 时复用 `selectProvider` 建支付逻辑并回填 `paymentMethods` | `dto/CheckoutRequest.java`、`service/CheckoutService.java` |
| I8 | 文档同步：README 端点总览改 21 个 + 鉴权两档 + 各段 curl 更新（订单参数化、签发归口、License 参数化、兑换码管理端、收银台一步）；两篇设计稿映射表补「主题 I 后的管理面」 | `docs/README.md`、`docs/架构设计.md`、`docs/业务流程.md` |

**验证**：`mvn -B test` = **129 tests, 0 failures, 0 errors, BUILD SUCCESS**（2026-09-14 17:42）。

## 三、遗留

1. **两个空壳文件待物理删除**（当前不含任何类型声明，不暴露端点、不影响编译；删除需用户执行，工具对该 workspace 外路径受限）：
   ```powershell
   Remove-Item 'd:\workspace\billing-license-service\src\main\java\com\billing\license\controller\OrderController.java'
   Remove-Item 'd:\workspace\billing-license-service\src\main\java\com\billing\license\dto\CreateOrderRequest.java'
   ```
2. **破坏性契约变更**（客户端/前端需同步）：`X-Admin-API-Key` → `X-API-Key`；订单查询 `/api/orders/**` → `/api/admin/orders?...`；签发 `/api/licenses/issue/{orderId}` → `/api/admin/orders/{orderNumber}/issue`；兑换码生成/撤销 → `/api/admin/redeem-codes/**`（且生成返回明文）。
3. 未做（本期范围外）：`GET /api/licenses/verify` 为 GET 但会写 `lastVerifiedAt`（REST 语义瑕疵，可考虑改 POST）；多实例限流/锁仍为单实例实现。

---

# 主题 J/K · DTO 契约文档化与时序图入参示例（2026-09-14 完成，2026-09-15 归档）

> 来源：活动 plan `docs/plan-2.9.md` 的主题 J、K（TODOS 中原记为已完成 `[x]`）。
> 归档时间：2026-09-15
> 归档原因：J1–J3、K1–K3 全部完成，按归档规则「已完成项不留活动 plan」整批移出。

## 一、完成记录

| 项 | 内容 | 产出（文件） |
|---|---|---|
| J1 | 10 个接口出入参 DTO 补齐 `@Schema`（类级描述 + 字段级 `description`/`example`/`allowableValues`，枚举取值与实体枚举逐一对齐） | `dto/` 下 10 个 DTO |
| J1a | `RedeemCodeRequest.clientIp` 同步标 `@Schema(hidden = true)`（服务端强制覆盖、不反序列化） | `dto/RedeemCodeRequest.java` |
| J2 | 验证：`javac --release 21` 编译 10 个 DTO 通过；`javap -v` 确认 `@Schema` 以 `RuntimeVisibleAnnotations` 落入字节码（springdoc 反射可读） | — |
| J3 | 两处匿名 `Map<String, Object>` 响应类型化为 `RedeemResponse`、`GenerateRedeemCodesResponse`，字段名/取值逐一对齐，对外契约不变 | `dto/RedeemResponse.java`、`dto/GenerateRedeemCodesResponse.java`、`controller/RedeemCodeController.java`、`controller/AdminController.java` |
| K1 | `接口调用时序图.md` 新增 §1.7「接口调用顺序（端到端总览）」：2 张 Mermaid 时序图 + 管理端 7 条运维调用次序表 | `docs/接口调用时序图.md` |
| K2 | 新增第四章「接口入参示例」：21 个端点全覆盖（字段表 + curl + webhook 各渠道签名位置说明） | `docs/接口调用时序图.md` |
| K3 | 原「四、附录」顺延为「五、附录」，并补交叉导航 | `docs/接口调用时序图.md` |

## 二、后续变更（v2.10）

- 账号体系新增 7 个 `/api/account/**` 端点，端点总数 **21 → 28**，鉴权扩为三档（公开 / 用户令牌 / `X-API-Key`）；既有 21 个端点路径与权限未变。
- `docs/接口调用时序图.md` 相应新增 §1.7.3 账号链路图、§2.1 过滤链加入 `JwtAuthFilter`、§4.8 账号相关端点入参示例。

---

# 主题 L1 · 限流滑动窗口内存重构（2026-09-15 完成）

> 来源：账号体系（v2.10）收尾验证时发现的**既有隐患**，随 v2.10 归档结转为活动 plan 的 L1 项。
> 归档时间：2026-09-15

## 一、问题

`RateLimitService.TimestampRing` 构造器按 `capacity+1` **预分配** `AtomicLongArray`：内存占用与配置的 `max` 成正比，而非与实际事件数成正比。`max` 配到 10 万时单个 key 就是 800KB，`RateLimitServiceEvictionTest`（5000 个 key）必然 OOM（约 4GB）——实测 `-Xmx1g` / `-Xmx2g` 均无效，说明是真实内存膨胀而非堆大小不足。

**注意**：该隐患非 v2.10 引入（本次对该文件的改动只有 `peekCount` 与抽取 `countWithin`），属既有缺陷被大参数测试放大。

## 二、改动

`TimestampRing` 改为 `ArrayDeque<Long>` + `synchronized`：

- `record`：入队后按上界 `capacity+1` 从队头淘汰（等价于原环形缓冲的覆盖写）；
- `countWithin`：队头过期即出队，返回队列长度（原实现恒为 O(capacity) 地扫描含空闲槽位的数组，现为 O(窗口内事件数)）。

**语义保持**：上界计算沿用 C9 的 `(long) capacity + 1` 防溢出钳位；`lastAccess` 仍为 `AtomicLong`（EvictionTest 的反射断言不受影响）；公开 API、限流触发阈值与驱逐行为均不变。

## 三、验证

`RateLimitServiceEvictionTest` 的阈值**恢复为 100000**（此前为绕过 OOM 临时下调到 10），作为该隐患的长期回归防护。

`mvn -B test` = **134 tests, 0 failures, 0 errors, BUILD SUCCESS**（2026-09-15），其中 EvictionTest 2/2 通过、耗时 1.239 s（重构前必 OOM）。
