# plan-8.0 · 全量审计整改 + 剩余收口

> 本文件只登记**未完成任务**。完成即删条目、不留痕迹；全绿即删文件。
> 自检：回复里出现「待决策 / 遗留 / 暂不处理 / 已知降级」，本文件必须有对应条目。
> 来源：2026-10-17 全量业务逻辑 / 性能 / 完整性审计（结论与长远建议落 `架构与业务流程设计.md` 末「附二：全量审计结论与长远建议」，其中 2026-10-18 整改状态见该节首）+ 承继原 `plan-7.0.md` 的两条 ③（现 #14 / #15）；原 `plan-7.0.md` 已删除，本仓维持**单一活动 plan**。

> 权威口径落点：契约与实现现状 → `README.md`（端点总览 65 个 / 凭证激活 / 二次因子 / 平台账务）、`接口调用时序图.md`（§1.6 端点全景 + §4 入参示例 + 横切机制）、`产品快速接入手册.md`（产品接入 / 订阅边界 / 错误码）；上线执行与验收 → `上线准备工作.md`（§0 Go/No-Go、§二 渠道与证书、§3.1 必填变量、§五 安全加固、§6.6 回滚、§6.7 静态页缓存版本号、§七 验收清单 A/B/C/D/E、§九 风险登记）；授权域「明确不做」与红线 → `授权设计方案.md` §十八；管理台 / MFA / 退款范围边界 → `架构与业务流程设计.md`「附：范围边界定案」；客户端复核节奏与红线 → `ai-tools/doc/授权定期联网复核设计.md`（§0.4 现行口径、§1.5 时钟回拨、§8 共享知识）。**测试数据与清理 SQL 全在 `D:\ProductSpace\.e1b-ledger.md`。**

## TODOS

### ②【需你决策】存在取舍，附选项与推荐

- [ ] **#27** **前端交互优化原型落地评审（2026-10-25 产出 `prototype/index.html`）**：**建议 先走查再分批落地** —— 25 条屏幕级 + 4 条跨屏优化点（P1–P25 / G1–G4）均不动接口、纯静态页改动；弃「暂不落地」：待支付订单缺「继续支付」等已暴露缺口继续留在线上。
  - 已落地（随 #10 / A2）：账号页注册表单 + 订阅自助取消（期末生效弹层 + 行内取消入口 + 「下次扣费」列），浏览器走查 17 条全过、console 零报错（2026-10-26）。
  - 现状：三站静态页（`static/checkout`、`static/account`、`static/admin`）交互口径见原型侧栏逐条对照；账号页订单表对待支付单无继续支付入口，toast / 骨架屏 / Tab 分组均未落地。
  - A：按原型分批改静态页（先收银台与账号页，再管理台）—— 利：交互缺口就近补上；弊：每次改动须按 `上线准备工作.md` §6.7 bump `?v=` 并走浏览器走查。
  - B：仅作评审参考、暂不改生产页 —— 利：零改动；弊：已暴露缺口继续存在。
  - 默认：必须等你拍板（涉及静态页改动排期）。

### ③【需你处理】AI 无法代做，须你在自己环境执行

- [ ] **#14**（承继原 `plan-7.0.md` #10）**E1 · 真人付款腿（唯一未跑的功能腿）**：★ 真人扫支付宝沙箱码付款 → 触发 `fulfillOrder` 直签 + 两封真邮件 + 客户端 60s 到账轮询循环本身（`purchase-poll.ts:58-63`、`constants.ts:107-108`；`pollOnce` 私有不可注入）。**须重新下单**：原备单 `ORD-1790691168309-616DF8FE`（pro-buyout ¥712.80，`machine_code=5E01-7EB8-3661-E06A`）二维码会话已于 2026-09-30 00:12 过期。核销：该订单在库中转 `PAID` 且 `licenses` 出现绑定该机器码的 ACTIVE 件、客户端到账轮询在窗口内命中并落盘。其余三条腿已实跑通过：服务端 curl 腿（V9/V10/V11 真库执行、pending 命中与一次性消费、`nextCheckAfterMs=1296000000`、换机 400 `MACHINE_MISMATCH`、同机幂等、解绑后再激活 200、频控第 46 次起 429 空响应体）、客户端真模块腿（真 Ed25519 验签 + 到账 + 31 天提醒态功能不减 + 61 天且断网才停用 + 复网自愈）、邮件腿（本机 SMTP sink 实收验证码邮件）。

- [ ] **#15**（承继原 `plan-7.0.md` #11）**E3 + SEC-0 · 实机走查合批（同一趟跑完）**：★ 逐项核销 `上线准备工作.md` §七 的 A9（跨仓登录后自动到账，客户端代码已落地 `ai-tools`：`account.ts` + `redeem.ts` + `claim.ts`）、A10 自助解绑、A11/A12 MFA 全流程与反绕过、A13 License 处置、B1/B4 真实小额与退款吊销、B7 渠道部分退款、B8 Paddle 续费 payload、E 段 1–5 管理台与账户走查 + 报错文案 GUI 复测；含 SEC-0 收尾（用管理员真实凭据 + 动态码走一遍完整登录）。核销：§七 上述各行由你打勾（AI 无法代做：需认证器 App 真扫码、真实渠道小额付款、浏览器里看渲染结果）。⚠️ E 段第 1 项须额外覆盖 #9 改形态后的两处 License 列表渲染（`Page.content`）与 `admin` 页产品配置两层界面。⚠️ 静态页任一改动须按 `上线准备工作.md` §6.7 逐页 bump `?v=`（admin 页现值 `?v=20261025-4`，checkout 页 `?v=20261025-2`，account 页 `?v=20261026-1`）。

- [ ] **#18** **V12/V13/V15 真库迁移冒烟（本机无 PG，AI 侧跑不了）**：`V12__hot_path_query_indexes.sql`（4 条索引）、`V13__product_profiles.sql`（建产品档案表 + 按 product_code 回填 + 删除 `products.features` + 新增 `recommended`）与 `V15__retention_indexes.sql`（保留期清理的 3 条索引，配 #13 的 `RetentionCleanupService`）均未在真实 PostgreSQL 执行；`mvn -o test` 走 H2 且 `flyway.enabled=false`（`application-test.yml`）。核销：目标环境启动日志出现 Flyway `Current version of schema "public": 15` 且无 checksum/语法报错；随后 `product_profiles` 每个 product_code 有一行、`products.features` 列已不存在、`pro-plus-buyout` 的 `recommended=true`，`audit_logs.created_at` 等新索引存在。⚠ V13 的 `gen_random_uuid()` 需 PostgreSQL 13+。

- [ ] **#28** **#25/#26 的真实渠道端到端验证（本机无渠道凭据，AI 侧跑不了）**：真订阅（#25）与首充回调丢失自动重修（#26）的代码与单测已完成（全量 607 绿），但两家渠道都需真实凭据才能闭环。核销：① Stripe 测试模式下一笔月付订阅下单，渠道后台该会话为 `mode=subscription` 且 `subscription_data.metadata.order_id` 有值；② 第二期 `invoice.paid` 到达后该订阅绑定的 License 有效期被延长；③ Paddle 沙箱订阅套餐（已配 `paddle_price_id`）下单成功；把 price id 清空后下单返回 `PADDLE_PRICE_ID_MISSING`；④ 人为丢弃首充回调再发一笔续费事件，日志出现「已按渠道查询自动重修」且订阅记录被补建并绑到原订单。⚠ ③ 需先在 Paddle 后台建 catalog price 并回填套餐的 `paddle_price_id`。

## 硬约束（勿回头改）

1. 2.x Jackson 三件套按 minor `2.22` 对齐：databind/core 钉 `2.22.3`、annotations 钉 `2.22`；不得钉成 `2.22.3`/`2.23.x`（annotations 2.x 线无 patch 版本，仓库不存在）。
2. 3.x Jackson（`tools.jackson.core`，钉 `3.2.3`）与 2.x Jackson（`com.fasterxml.jackson.core`）并存是既定结构，勿整体替换其中一套。
3. `jackson-annotations` 必须在 `<dependencies>` 显式声明（不写 version），版本由 `dependencyManagement` 统一钉 `2.22`，确保进 runtime classpath。
4. 迁移文件受 Flyway checksum 保护：现存 V1–V15（V14 为套餐的 `stripe_price_id` / `paddle_price_id`，V15 为保留期清理索引），任何变更一律新增 **V16** 起，勿改已有文件。
5. SMTP 三个超时（`application.yml:57-59`：连接 8s / 读 8s / 写 15s）不得删除，JavaMail 默认无超时会挂死 `@Async` 发送线程。
6. 管理端鉴权只有「管理员 JWT + `users.role`」一套；`X-API-Key` / `X-Admin-API-Key` 通道已整通道移除，不得恢复。
7. 契约以代码为准：`README.md` API 段与 `接口调用时序图.md` 是实现现状；`架构与业务流程设计.md` / `授权设计方案.md` 是设计建议稿，不可作接口/字段契约依据。
8. 仓库内不得留默认支付渠道（`enabled-channels` 默认空）与沙箱网关默认值（网关默认一律生产：`application.yml` 的 `payment.paddle.environment` / `payment.paypal.environment` 与两处 `@Value` 默认已同步为生产，沙箱只在 `.env` 显式配）；⚠️ 只改 `@Value` 默认无效——属性在 `application.yml` 已定义，生效值来自 yml。
9. 未开 MFA 的 ADMIN 保持提示式横幅，不做拦截式过滤器（2026-10-16 定案，理由见 `架构与业务流程设计.md`「附：范围边界定案」）。
10. 签名密钥只认「裸 32 字节 Ed25519」或 DER，不收 PEM；生成命令唯一权威源是 `上线准备工作.md` §1.2，其余文档只引用不复制。
11. `app.base-url` 保持默认 `http://localhost:8000`（不 fail-fast，2026-10-16 定案）；生产公网 HTTPS 靠 §0/§10-3 人工核对，文档不得再写「无默认值」。
12. 账务聚合口径（不跨币种求和、已支付类才计实收、退款仅取 `REFUNDED`、分页封顶 200、跨度 ≤366 天）由 `AccountingServiceTest` / `AccountingControllerTest` 钉住；改口径必须同步改这两个测试。
13. `licenses.machine_code` 是设备绑定的唯一真相，不得回落 `orders.machine_code`（回落会重新引入「解绑清不掉生效绑定」的死锁）。
14. 一切可能资损的「先查后改」路径（发货、退款、兑换码核销）必须有 DB 级并发保护（行锁或唯一约束），不得只靠状态判断。
15. **退款串行化令牌必须是该订单的 `payments` 行，不得改回订单行**：`AdminService#refundOrder` 的渠道失败态由 `persistRefundFailed` 在 `REQUIRES_NEW` 内层事务里 UPDATE 同一订单行；外层若持订单行 `FOR UPDATE`，内层永远等不到该锁而外层又在等内层返回 → 无语句超时则**永久挂起**并占死一根连接。发货两腿（`fulfillOrder` / `revokeOnChannelRefund` / `applyCompensatedPayment`）无此内层事务，故仍用订单行锁。
16. 渠道外部调用一律「有超时、在事务外」：`HttpClient` 只能取 `PaymentHttpClientConfig`（connectTimeout 5s）且每个 `HttpRequest` 必须带 `PaymentHttpClientConfig.REQUEST_TIMEOUT`（10s）；不得再在策略里 `HttpClient.newHttpClient()`，也不得把外部调用移回 `@Transactional` 方法体内。收银台侧的既定形态是三段式（`createCheckout` / `selectProvider` / `getStatus` 三者**都不开事务**，写库各自收敛进 `persistOrderAndSession` / `recordPaymentChoice` / `applyCompensatedPayment` 短事务，经 `self` 代理调用）；`PaymentService` 相应拆成 `requestPayment`（事务外调渠道）+ `recordPayment`（落库），**不得**再合并回一个 `createPayment`——那会把渠道 HTTP 与 Payment 行写库重新捆进同一事务，并让「渠道失败不落 Payment 行」退化为靠回滚兜底。
17. 订阅周期下单口径（#25）：Stripe 走 `mode=subscription`（配 `stripe_price_id` 用 catalog price，未配则按周期内联 recurring price）；Paddle 必须带 catalog `price_id`，**缺 id 直接拒单 `PADDLE_PRICE_ID_MISSING`，不得静默降级为一次性收款**（用户以为买了订阅却不续费＝静默资损）。订单号必须写进渠道订阅对象（Stripe `subscription_data.metadata.order_id` / Paddle 交易 `custom_data.order_id`），否则后续续费事件回查不到订单。
18. 订阅事件的渠道反查（#26）必须在事务外：`SubscriptionService.processSubscriptionEvent` **不开事务**，反查拿到订单号后再由 `TransactionOperations` 包裹 `applyEvent` 落库（承继硬约束 16，避免渠道 HTTP 占用 DB 连接）。
19. **OSIV 已显式关闭**（`application.yml` `spring.jpa.open-in-view: false`，#12）：任何在事务外读实体懒集合（`orderItems` 等）的代码都会 `LazyInitializationException`——新增读取路径必须落在 `@Transactional` 内（列表/详情查询用 `readOnly = true`），或仓库层用 `join fetch` / `findWithItems*` 取数；已有 `OrderRepository#findWithItemsByOrderNumber` / `#findWithItemsById` 是既定取数姿势，勿回退。
20. **保留期清理（#13）**：`RetentionCleanupService` 批删默认批量 1000（`MIN_BATCH_SIZE=100` 兜底），短批即停；默认保留期审计 365 / 支付事件 365 / 领证事件 180 天（`billing.retention.*` 可配，0 天＝立即清理仅测试用）；管理台「维护」分区手动触发走 `POST /api/admin/retention/purge`，勿绕过 service 直接 `deleteAll`。

## 暂不做（勿回头扩范围）

云 KMS 接入 · Redis 分布式限流与共享票据存储 · 多 kid 强制轮换与自动调度 · 管理员口令加严 · 历史签名密钥 git 历史清理 · 渠道 SDK 生产凭据接入 · 管理端 `AdminService#resolveMethod` 死代码清理 · Stripe / Alipay **SDK 内部**超时显式配置（#5 只覆盖 JDK `HttpClient` 的 13 处；两家 SDK 走各自默认，改动面与依赖面更大，随 #16 一并评估）

## 验证命令

- 全量测试：`mvn -o test`（2026-10-26 实跑 `Tests run: 624, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`；相对 607 新增：#13 保留期清理 5 条、#12 OSIV 懒加载边界 DbTest 2 条、订阅自助取消控制器 17 条、静态页契约 2 条、管理台契约等）
- 真订阅专项：`mvn -o test "-Dtest=StripeStrategyTest,PaddleStrategyTest,SubscriptionServiceTest"`（2026-10-25 实跑 33 条全绿）
- 订阅专项：`mvn -o test "-Dtest=SubscriptionServiceTest,RedeemCodeServiceTest,CheckoutServiceTest,ProductControllerTest"`（2026-10-25 实跑全绿；覆盖期末失效、乱序复活、兑换码挂订单、并发建收银台唯一、目录排序/续费标识）
- 渠道能力专项：`mvn -o test "-Dtest=PaymentServiceFactoryTest,CheckoutServiceTest,ProductControllerTest"`（2026-10-25 实跑全绿：国内渠道不能卖订阅周期、目录 `providers` 按矩阵映射、选渠道能力不匹配返回 `PROVIDER_NOT_SUPPORTED_FOR_PLAN`）
- 收银台能力展示走查（2026-10-25 实跑）：本地静态服务 + mock `/api/products` 浏览器走查——中文态买断卡「支付宝 · 微信支付」可选、月付卡「当前区域暂不可售」且 radio 禁用；切英文后买断「Stripe · Paddle · PayPal」、月付「Stripe · Paddle」均可选；console 无报错
- 事务边界核对（#5 / #16）：`Select-String -Path src/main/java/com/billing/license/service/CheckoutService.java -Pattern '@Transactional' -Context 0,1` 后逐条核对——`createCheckout` / `selectProvider` / `getStatus` 三个入口**不应**带 `@Transactional`，写库腿 `persistOrderAndSession` / `recordPaymentChoice` / `applyCompensatedPayment` 应带；`PaymentService#requestPayment` 不应带、`recordPayment` 应带（2026-10-18 实跑符合）
- ⚠️ 迁移脚本**不在**单测覆盖内（测试用 H2 + `spring.flyway.enabled=false`）→ V12/V13 真库冒烟见 ③ #18
- 真库启动冒烟（V12 前最后一次，2026-10-16）：`mvn -o spring-boot:run` 后 `curl -fsS http://127.0.0.1:8000/actuator/health`（实跑：Flyway `Successfully validated 11 migrations` + `Current version of schema "public": 11`、`Initialized JPA EntityManagerFactory`（`ddl-auto: validate` 无报错）、`Tomcat started on port 8000`、`Started ... in 22.084 seconds`、health 与 readiness / liveness 均 `UP`；跑完已停进程、8000 无监听）
- 端点数第三方复核：`curl -fsS http://127.0.0.1:8000/v3/api-docs` 后数 `paths` 与操作数（2026-10-25 静态计数 65；`curl` 复核需重启服务后执行）
- 文档锚点全量校对：`Get-ChildItem docs/*.md, scripts/README.md` 抽取所有 `文件:行号` 后逐个比对存在性与行范围（2026-10-16 实跑：104 个唯一锚点，无越界；⚠️ 本轮改了 `AdminService` / `WebhookController` / `CheckoutService` / `LicenseController` 的行号，重跑前文档内行锚需重新校对）
- 索引覆盖率核对：`Select-String -Path src/main/resources/db/migration/*.sql -Pattern 'CREATE (UNIQUE )?INDEX'` 后逐条比对 `repository/*Repository` 的查询键（2026-10-18 静态核对：审计点名的 4 条已由 V12 补齐；真库执行结果待 ③ #18）
- 渠道 HTTP 治理核对（#5）：`Get-ChildItem src/main/java -Recurse -Include *.java | Select-String -Pattern 'newHttpClient\(\)' | Where-Object Line -notmatch '^\s*\*'` 应为 **0 命中**（唯一残留文本是 `PaymentHttpClientConfig` 注释里对原状的描述）；`Select-String -Pattern 'HttpRequest.newBuilder\(\)'` 命中数必须等于 `Select-String -Pattern 'PaymentHttpClientConfig.REQUEST_TIMEOUT'` 命中数（2026-10-18 实跑：两侧均 **13**，Paddle 3 / PayPal 6 / Wechat 4 全覆盖，main 内不再各自建客户端）
- 限流 IP 口径核对（#7 / #8）：`grep -rn "getRemoteAddr()" src/main/java` 只应命中 `ClientIpResolver`（兜底）与 `AuditAspect`（审计日志记录来源 IP）
- 账务专项：`mvn -o test "-Dtest=AccountingServiceTest,AccountingControllerTest"`（2026-10-16 实跑 22 用例全绿：服务层 10 + 控制层 12；2026-10-18 解除编译阻断后已随全量套件再次全绿）
- 改动面专项：`mvn -o test "-Dtest=CheckoutServiceTest,CheckoutServicePaymentFailureTest,AdminServiceRefundTest,WebhookControllerTest,CustomerIdentityServiceTest,RedeemCodeServiceTest,LicenseControllerTest"`（2026-10-18 实跑全绿）
- Jackson 解析版本核对：`mvn -o dependency:tree` 再看 jackson 行（实跑解析到 `tools.jackson.core:jackson-databind 3.2.3` / `com.fasterxml.jackson.core:jackson-databind 2.22.3` / `jackson-annotations 2.22`）
- 端点计数核对：`Get-ChildItem src/main/java/com/billing/license/controller -Recurse -Include *.java | Select-String -Pattern '@(Get|Post|Put|Delete|Patch)Mapping'`（2026-10-25 实跑＝65）
- 迁移目录与基线表数：`Get-ChildItem src/main/resources/db/migration -Name`（V1–V13）、`Select-String -Path src/main/resources/db/migration/*.sql -Pattern 'CREATE TABLE'`（16 张）
- 静态页缓存版本号（§6.7）：`grep -rn "?v=" src/main/resources/static --include=*.html`（2026-10-26 实跑：admin `20261025-4`、account `20261026-1`、checkout.js `20261025-2`、checkout.css/qrcode `20260929-1` 未动）
- 管理台三分区 + 账号页走查（#10 / #27 已落地部分，2026-10-26 实跑 17 条全过）：仓库外脚本 + 静态服务 + Playwright + `page.route` mock——账号页注册（发码倒计时 / 注册即登录 / 内容视图）、订阅取消（仅 ACTIVE 且未挂标记的行有按钮 / 弹层明示产品与周期 / 确认后刷新）、管理台 orders / codes / channels 三分区渲染与 `Page.content` 翻页；两页 console 零报错。⚠ 走查脚本为临时产物已删，复跑需重写
- 产品配置专项：`mvn -o test "-Dtest=AdminProductControllerTest,ProductControllerTest,LicenseIssuerTest,AdminStaticPageContractTest"`（2026-10-25 实跑全绿：12 + 5 + 9 + 7 用例；浏览器另走查产品档案/套餐新增编辑与收银台半年套餐渲染）
- 真库迁移状态（需目标环境有 psql；本机无 psql，改由上述启动日志的 Flyway 两行交叉核对）：`./scripts/db/show_migrations.sh`
- ⚠️ 本机 Maven 已可用（`C:\Users\qingc\scoop\apps\maven\current\bin\mvn.cmd`），旧 plan 里「本地无 mvn、构建统一走 IDEA MCP」的口径作废；`mvn` 校验类命令禁止接管道（会吞掉失败输出）。
