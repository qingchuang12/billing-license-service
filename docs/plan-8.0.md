# plan-8.0 · 全量审计整改 + 剩余收口

> 本文件只登记**未完成任务**。完成即删条目、不留痕迹；全绿即删文件。
> 自检：回复里出现「待决策 / 遗留 / 暂不处理 / 已知降级」，本文件必须有对应条目。
> 来源：2026-10-17 全量业务逻辑 / 性能 / 完整性审计（结论与长远建议落 `架构与业务流程设计.md` 末「附二：全量审计结论与长远建议」，其中 2026-10-18 整改状态见该节首）+ 承继原 `plan-7.0.md` 的两条 ③（原 #10 / #11）；原 `plan-7.0.md` 已删除，本仓维持**单一活动 plan**。

> 权威口径落点：契约与实现现状 → `README.md`（端点总览 59 个 / 凭证激活 / 二次因子 / 平台账务）、`接口调用时序图.md`（§1.6 端点全景 + §4 入参示例 + 横切机制）；上线执行与验收 → `上线准备工作.md`（§0 Go/No-Go、§二 渠道与证书、§3.1 必填变量、§五 安全加固、§6.6 回滚、§6.7 静态页缓存版本号、§七 验收清单 A/B/C/D/E、§九 风险登记）；授权域「明确不做」与红线 → `授权设计方案.md` §十八；管理台 / MFA / 退款范围边界 → `架构与业务流程设计.md`「附：范围边界定案」；客户端复核节奏与红线 → `ai-tools/doc/授权定期联网复核设计.md`（§0.4 现行口径、§1.5 时钟回拨、§8 共享知识）。**测试数据与清理 SQL 全在 `D:\ProductSpace\.e1b-ledger.md`。**

## TODOS

### ①【可直接做】AI 现在即可落地，范围明确无待决策

- [ ] **#16** `selectProvider` 腿仍在事务内发外部 HTTP（承自 #5，同因未一并改动）：`CheckoutService#selectProvider`（`CheckoutService.java:170` 一带的 `@Transactional`）内调 `paymentService.createPayment(...)` → 各策略向渠道真实下单（支付宝/微信/Paddle/PayPal 均为外部请求；Stripe 走 SDK）。#5 已把**验签**（`WebhookController#processWebhook`）与**查单**（`CheckoutService#getStatus` → `compensateFromChannel`）挪到事务外，本腿未动。后果与 #5 同源：渠道下单卡住即同时占住请求线程与一根 DB 连接。修法照 #5 已落的先例做三段式：`selectProvider` 不开事务 → 事务外读会话/订单与调渠道 → 结果落库收敛进一个 `@Transactional` 短方法（经 `self` 代理调用，`CheckoutService` 已因 #5 持有 `self` 字段）。⚠️ 保持既有语义不动：渠道创建失败必须仍落 `FAILED` 会话/抛 `PAYMENT_CREATE_FAILED`（`CheckoutServicePaymentFailureTest` 钉住）、同渠道重复选择仍幂等返回既有支付入口（B4 `buildResumeResponse`）。
- [ ] **#17** 审计留痕里的 IP 仍是直连地址：`AuditAspect.java:87-88` 用 `attrs.getRequest().getRemoteAddr()` 记审计操作者 IP。#7 已把**限流**口径统一为 `ClientIpResolver`，但审计侧没改 → 反向代理部署后 `audit_logs` 的 IP 列恒为代理 IP，「谁在什么地址退的款」查不到真值（与 §五「真实 IP」的排查预期不符）。修法：`AuditAspect` 注入 `ClientIpResolver` 并改用它解析（口径与限流一致，`BILLING_TRUST_XFF=false` 时仍取 remoteAddr，行为不劣化）。

### ②【需你决策】存在取舍，附选项与推荐

- [ ] **#10** **前端未覆盖后端能力（管理台运维面 + 账号页注册）**：**建议 补管理台最小界面** —— 补发/退款是事故期唯一的人工补偿路径，只能 curl 则响应慢且无界面留痕；弃「维持 API 运维」：事故窗口被放大。
  - 现状：`/api/admin/orders`、`/api/admin/orders/{n}/issue`、`/{n}/refund`、`/api/admin/redeem-codes/**`、`/api/admin/payment-channels` 五组端点**前端零入口**（三站 JS 全文检索 `admin/orders|redeem-codes|payment-channels` 无命中；`admin/index.html` 分区仅 账务 / License 处置 / 用户管理 / 安全设置），账号页亦无 `/api/account/register` 入口。
  - ⚠️ 2026-10-18 起 `GET /api/admin/orders` 与 `GET /api/admin/licenses` 返回 `Page`（#9），补界面时按 `content` + `totalElements` 渲染翻页；`admin.js` 已有的两处 `licenses?customerEmail=` 调用点已改为读 `Page.content` 并带 `size=200`。
  - A：管理台补「订单查询/签发/退款 + 兑换码生成撤销 + 渠道自查」三个分区，账号页补注册 —— 利：运维闭环与操作留痕；弊：前端工作量（`admin.js` 现 103KB）。
  - B：维持现状，管理动作只走 API/curl —— 利：零改动；弊：事故补偿无界面、无二次确认、无审计可见性。
  - 默认：必须等你拍板（属范围扩张）。

- [ ] **#11** **订阅「取消即失效」的语义**：**建议 改为期末失效** —— 用户已付费至 `currentPeriodEnd`，取消即断权益属商业缺陷；弃「维持立即失效」：退费与口碑成本更高。
  - 现状：`SubscriptionService#expireLicense`（`:171-181`）对**任何** `canceled` 事件一律置 `EXPIRED` 且 `expiresAt=now`，不区分「立即取消」与「期末取消」。
  - A：canceled 时把 `expiresAt` 收敛到 `subscription.currentPeriodEnd`（未到期则保留权益，仅停止续费） —— 利：与渠道口径一致；弊：需按渠道事件类型区分取消语义。
  - B：维持立即失效 —— 利：无改动；弊：已付期间被剥夺，易生投诉。
  - 默认：不表态即按建议执行。

- [ ] **#12** **OSIV（open-in-view）与懒加载治理**：**建议 关 OSIV 并把事务外懒加载纳入事务** —— 每请求全程占一条 DB 连接（含发邮件、渠道 HTTP 期间），在小池下最先打满；弃「保持默认」：池压力与隐藏 N+1 长期不可见。
  - 现状：`application.yml` 未设 `spring.jpa.open-in-view`（Spring Boot 默认 `true`，启动期会打 WARN）；`RefundPolicy#isSubscriptionOrder`、`SubscriptionService#processSubscriptionEvent`、`AccountingService#getByProduct` 均在事务边界之外/之内读懒集合（后者按订单逐个触发 `orderItems` 懒加载）。
  - ⚠️ 2026-10-18 #5 落地后减轻但未消除：webhook 验签与轮询查单已移到事务外（`getStatus` 已整体不开事务、写库收敛进 `applyCompensatedPayment` 短事务），OSIV 仍为其余请求占连接。
  - A：显式 `open-in-view: false` + 逐处补事务或 `join fetch` —— 利：连接占用与 N+1 显性化；弊：漏改即 `LazyInitializationException`，需全量回归。
  - B：维持 `true` —— 利：零风险；弊：连接占用与隐藏 N+1 持续存在。
  - 默认：不表态即按建议执行。

- [ ] **#13** **流水与审计表的保留期**：**建议 定保留期 + 定时分批清理（审计 365 天 / 支付事件 365 天 / 领证事件 180 天，均可配置）** —— 三张表只增不减，长期膨胀直接恶化备份窗口与查询；弃「永久保留」：体积无界。
  - 现状：`audit_logs`、`payment_events`、`license_events` 既无清理任务也无查询索引（`AuditLogRepository` 只有 `JpaRepository` 空接口，无任何审计查询端点）；`CheckoutSession` 已有同类清理先例（`CheckoutService#cleanupExpiredSessions`）。
  - A：加保留期配置 + `@Scheduled` 分批删除，并给 `audit_logs` 补查询索引 —— 利：体积可控；弊：需先定合规保留期。
  - B：永久保留 —— 利：合规最稳；弊：体积与备份成本无界增长。
  - 默认：必须等你拍板（合规取值不能由 AI 代为设定）。

### ③【需你处理】AI 无法代做，须你在自己环境执行

- [ ] **#18** **V12 真库迁移冒烟（本机无 PG，AI 侧跑不了）**：`V12__hot_path_query_indexes.sql` 新增 4 条 `CREATE INDEX IF NOT EXISTS`（`licenses(order_id)` / `orders(customer_id)` / `payments(order_id)` / `orders(status)`）。列名与表名已逐条比对 `V1__baseline_schema.sql`，但 `mvn -o test` 走 H2 且 `flyway.enabled=false`（`application-test.yml`），**迁移脚本本身未被测试覆盖**；本机 Docker 守护进程与 5432 均不可用，2026-10-16 那次真库冒烟只到 V11。核销：目标环境启动日志出现 Flyway `Current version of schema "public": 12` 且无 checksum/语法报错；随后 `Select-String -Path src/main/resources/db/migration/*.sql -Pattern 'CREATE (UNIQUE )?INDEX'` 的索引集能覆盖 `repository/*Repository` 的全部查询键。
- [ ] **#14**（承继原 `plan-7.0.md` #10）**E1 · 真人付款腿（唯一未跑的功能腿）**：★ 真人扫支付宝沙箱码付款 → 触发 `fulfillOrder` 直签 + 两封真邮件 + 客户端 60s 到账轮询循环本身（`purchase-poll.ts:58-63`、`constants.ts:107-108`；`pollOnce` 私有不可注入）。**须重新下单**：原备单 `ORD-1790691168309-616DF8FE`（pro-buyout ¥712.80，`machine_code=5E01-7EB8-3661-E06A`）二维码会话已于 2026-09-30 00:12 过期。核销：该订单在库中转 `PAID` 且 `licenses` 出现绑定该机器码的 ACTIVE 件、客户端到账轮询在窗口内命中并落盘。其余三条腿已实跑通过：服务端 curl 腿（V9/V10/V11 真库执行、pending 命中与一次性消费、`nextCheckAfterMs=1296000000`、换机 400 `MACHINE_MISMATCH`、同机幂等、解绑后再激活 200、频控第 46 次起 429 空响应体）、客户端真模块腿（真 Ed25519 验签 + 到账 + 31 天提醒态功能不减 + 61 天且断网才停用 + 复网自愈）、邮件腿（本机 SMTP sink 实收验证码邮件）。
- [ ] **#15**（承继原 `plan-7.0.md` #11）**E3 + SEC-0 · 实机走查合批（同一趟跑完）**：★ 逐项核销 `上线准备工作.md` §七 的 A9（跨仓登录后自动到账，客户端代码已落地 `ai-tools`：`account.ts` + `redeem.ts` + `claim.ts`）、A10 自助解绑、A11/A12 MFA 全流程与反绕过、A13 License 处置、B1/B4 真实小额与退款吊销、B7 渠道部分退款、B8 Paddle 续费 payload、E 段 1–5 管理台与账户走查 + 报错文案 GUI 复测；含 SEC-0 收尾（用管理员真实凭据 + 动态码走一遍完整登录）。核销：§七 上述各行由你打勾（AI 无法代做：需认证器 App 真扫码、真实渠道小额付款、浏览器里看渲染结果）。⚠️ E 段第 1 项须额外覆盖 #9 改形态后的两处 License 列表渲染（`Page.content`）与 `admin` 页 `?v=20261018-1`。⚠️ 静态页任一改动须按 `上线准备工作.md` §6.7 逐页 bump `?v=`（admin 页现值 `?v=20261018-1`，account 页 `?v=20260925-1`）。

## 硬约束（勿回头改）

1. 2.x Jackson 三件套按 minor `2.22` 对齐：databind/core 钉 `2.22.3`、annotations 钉 `2.22`；不得钉成 `2.22.3`/`2.23.x`（annotations 2.x 线无 patch 版本，仓库不存在）。
2. 3.x Jackson（`tools.jackson.core`，钉 `3.2.3`）与 2.x Jackson（`com.fasterxml.jackson.core`）并存是既定结构，勿整体替换其中一套。
3. `jackson-annotations` 必须在 `<dependencies>` 显式声明（不写 version），版本由 `dependencyManagement` 统一钉 `2.22`，确保进 runtime classpath。
4. 迁移文件受 Flyway checksum 保护：现存 V1–V12，任何变更一律新增 **V13** 起，勿改已有文件。
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
16. 渠道外部调用一律「有超时、在事务外」：`HttpClient` 只能取 `PaymentHttpClientConfig`（connectTimeout 5s）且每个 `HttpRequest` 必须带 `PaymentHttpClientConfig.REQUEST_TIMEOUT`（10s）；不得再在策略里 `HttpClient.newHttpClient()`，也不得把外部调用移回 `@Transactional` 方法体内（`selectProvider` 腿见 #16）。

## 暂不做（勿回头扩范围）

云 KMS 接入 · Redis 分布式限流与共享票据存储 · 多 kid 强制轮换与自动调度 · 管理员口令加严 · 历史签名密钥 git 历史清理 · 渠道 SDK 生产凭据接入 · 管理端 `AdminService#resolveMethod` 死代码清理 · Stripe / Alipay **SDK 内部**超时显式配置（#5 只覆盖 JDK `HttpClient` 的 13 处；两家 SDK 走各自默认，改动面与依赖面更大，随 #16 一并评估）

## 验证命令

- 全量测试：`mvn -o test`（2026-10-18 实跑：`Tests run: 592, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`；本轮 #2/#4/#5/#6/#7/#8/#9 落地后复跑，此前一轮暴露 7 处测试桩与新增锁查询/分页不符，已同步：`WebhookControllerTest` 补 `findByOrderNumberForUpdate` 桩、`AdminServiceRefundTest` 改用 `findByOrderIdStrForUpdate` 桩、`CheckoutServiceTest` 注入 `self`）
- ⚠️ 迁移脚本**不在**单测覆盖内（测试用 H2 + `spring.flyway.enabled=false`）→ V12 真库冒烟见 ③ #18
- 真库启动冒烟（V12 前最后一次，2026-10-16）：`mvn -o spring-boot:run` 后 `curl -fsS http://127.0.0.1:8000/actuator/health`（实跑：Flyway `Successfully validated 11 migrations` + `Current version of schema "public": 11`、`Initialized JPA EntityManagerFactory`（`ddl-auto: validate` 无报错）、`Tomcat started on port 8000`、`Started ... in 22.084 seconds`、health 与 readiness / liveness 均 `UP`；跑完已停进程、8000 无监听）
- 端点数第三方复核：`curl -fsS http://127.0.0.1:8000/v3/api-docs` 后数 `paths` 与操作数（2026-10-16 实跑：路径 59 / 操作 59；#9 只改响应形态与查询参数，端点数不变）
- 文档锚点全量校对：`Get-ChildItem docs/*.md, scripts/README.md` 抽取所有 `文件:行号` 后逐个比对存在性与行范围（2026-10-16 实跑：104 个唯一锚点，无越界；⚠️ 本轮改了 `AdminService` / `WebhookController` / `CheckoutService` / `LicenseController` 的行号，重跑前文档内行锚需重新校对）
- 索引覆盖率核对：`Select-String -Path src/main/resources/db/migration/*.sql -Pattern 'CREATE (UNIQUE )?INDEX'` 后逐条比对 `repository/*Repository` 的查询键（2026-10-18 静态核对：审计点名的 4 条已由 V12 补齐；真库执行结果待 ③ #18）
- 渠道 HTTP 治理核对（#5）：`Get-ChildItem src/main/java -Recurse -Include *.java | Select-String -Pattern 'newHttpClient\(\)' | Where-Object Line -notmatch '^\s*\*'` 应为 **0 命中**（唯一残留文本是 `PaymentHttpClientConfig` 注释里对原状的描述）；`Select-String -Pattern 'HttpRequest.newBuilder\(\)'` 命中数必须等于 `Select-String -Pattern 'PaymentHttpClientConfig.REQUEST_TIMEOUT'` 命中数（2026-10-18 实跑：两侧均 **13**，Paddle 3 / PayPal 6 / Wechat 4 全覆盖，main 内不再各自建客户端）
- 限流 IP 口径核对（#7 / #8）：`grep -rn "getRemoteAddr()" src/main/java` 只应命中 `ClientIpResolver`（兜底）与 `AuditAspect`（即 ① #17）
- 账务专项：`mvn -o test "-Dtest=AccountingServiceTest,AccountingControllerTest"`（2026-10-16 实跑 22 用例全绿：服务层 10 + 控制层 12；2026-10-18 解除编译阻断后已随全量套件再次全绿）
- 改动面专项：`mvn -o test "-Dtest=CheckoutServiceTest,CheckoutServicePaymentFailureTest,AdminServiceRefundTest,WebhookControllerTest,CustomerIdentityServiceTest,RedeemCodeServiceTest,LicenseControllerTest"`（2026-10-18 实跑全绿）
- Jackson 解析版本核对：`mvn -o dependency:tree` 再看 jackson 行（实跑解析到 `tools.jackson.core:jackson-databind 3.2.3` / `com.fasterxml.jackson.core:jackson-databind 2.22.3` / `jackson-annotations 2.22`）
- 端点计数核对：`Get-ChildItem src/main/java/com/billing/license/controller -Recurse -Include *.java | Select-String -Pattern '@(Get|Post|Put|Delete|Patch)Mapping'`（2026-10-16 实跑＝59）
- 迁移目录与基线表数：`Get-ChildItem src/main/resources/db/migration -Name`（V1–V12）、`Select-String -Path src/main/resources/db/migration/*.sql -Pattern 'CREATE TABLE'`（15 张）
- 静态页缓存版本号（§6.7）：`grep -rn "?v=" src/main/resources/static --include=*.html`（2026-10-18 实跑：admin 两处已 bump 至 `20261018-1`，account `20260925-1`、checkout `20260929-1` 未动）
- 真库迁移状态（需目标环境有 psql；本机无 psql，改由上述启动日志的 Flyway 两行交叉核对）：`./scripts/db/show_migrations.sh`
- ⚠️ 本机 Maven 已可用（`C:\Users\qingc\scoop\apps\maven\current\bin\mvn.cmd`），旧 plan 里「本地无 mvn、构建统一走 IDEA MCP」的口径作废；`mvn` 校验类命令禁止接管道（会吞掉失败输出）。
