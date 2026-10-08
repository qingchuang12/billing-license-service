# plan-7.0 · 剩余收口（实机验证 · 外部待办）

> 本文件只登记**未完成任务**。完成即删条目、不留痕迹；全绿即删文件。
> 自检：回复里出现「待决策 / 遗留 / 暂不处理 / 已知降级」，本文件必须有对应条目。
> 合并来源：原 `docs/plan-1.0.md`（Jackson 缺类启动失败）的未完成项与硬约束于 2026-10-16 并入本文件，原文件已删除；本仓自此恢复**单一活动 plan**。

> 权威口径落点：契约与实现现状 → `README.md`（端点总览 59 个 / 凭证激活 / 二次因子 / 平台账务）、`接口调用时序图.md`（§1.6 端点全景 + §4 入参示例 + 横切机制）；上线执行与验收 → `上线准备工作.md`（§0 Go/No-Go、§二 渠道与证书、§3.1 必填变量、§五 安全加固、§6.6 回滚、§6.7 静态页缓存版本号、§七 验收清单 A/B/C/D/E、§九 风险登记）；授权域「明确不做」与红线 → `授权设计方案.md` §十八；管理台 / MFA / 退款范围边界 → `架构与业务流程设计.md`「附：范围边界定案」；客户端复核节奏与红线 → `ai-tools/doc/授权定期联网复核设计.md`（§0.4 现行口径、§1.5 时钟回拨、§8 共享知识）。**测试数据与清理 SQL 全在 `D:\ProductSpace\.e1b-ledger.md`。**

## TODOS

### ③【需你处理】AI 无法代做，须你在自己环境执行

- [ ] **#10** **E1 · 真人付款腿（唯一未跑的功能腿）**：★ 真人扫支付宝沙箱码付款 → 触发 `fulfillOrder` 直签 + 两封真邮件 + 客户端 60s 到账轮询循环本身（`purchase-poll.ts:58-63`、`constants.ts:107-108`；`pollOnce` 私有不可注入）。**须重新下单**：原备单 `ORD-1790691168309-616DF8FE`（pro-buyout ¥712.80，`machine_code=5E01-7EB8-3661-E06A`）二维码会话已于 2026-09-30 00:12 过期。核销：该订单在库中转 `PAID` 且 `licenses` 出现绑定该机器码的 ACTIVE 件、客户端到账轮询在窗口内命中并落盘。其余三条腿已实跑通过：服务端 curl 腿（V9/V10/V11 真库执行、pending 命中与一次性消费、`nextCheckAfterMs=1296000000`、换机 400 `MACHINE_MISMATCH`、同机幂等、解绑后再激活 200、频控第 46 次起 429 空响应体）、客户端真模块腿（真 Ed25519 验签 + 到账 + 31 天提醒态功能不减 + 61 天且断网才停用 + 复网自愈）、邮件腿（本机 SMTP sink 实收验证码邮件）。
- [ ] **#11** **E3 + SEC-0 · 实机走查合批（同一趟跑完）**：★ 逐项核销 `上线准备工作.md` §七 的 A9（跨仓登录后自动到账，客户端代码已落地 `ai-tools`：`account.ts` + `redeem.ts` + `claim.ts`）、A10 自助解绑、A11/A12 MFA 全流程与反绕过、A13 License 处置、B1/B4 真实小额与退款吊销、B7 渠道部分退款、B8 Paddle 续费 payload、E 段 1–5 管理台与账户走查 + 报错文案 GUI 复测；含 SEC-0 收尾（用管理员真实凭据 + 动态码走一遍完整登录）。核销：§七 上述各行由你打勾（AI 无法代做：需认证器 App 真扫码、真实渠道小额付款、浏览器里看渲染结果）。⚠️ 静态页任一改动须按 `上线准备工作.md` §6.7 逐页 bump `?v=`（admin 页现值 `?v=20260930-1`，account 页 `?v=20260925-1`）。

## 硬约束（勿回头改）

1. 2.x Jackson 三件套按 minor `2.22` 对齐：databind/core 钉 `2.22.3`、annotations 钉 `2.22`；不得钉成 `2.22.3`/`2.23.x`（annotations 2.x 线无 patch 版本，仓库不存在）。
2. 3.x Jackson（`tools.jackson.core`，钉 `3.2.3`）与 2.x Jackson（`com.fasterxml.jackson.core`）并存是既定结构，勿整体替换其中一套。
3. `jackson-annotations` 必须在 `<dependencies>` 显式声明（不写 version），版本由 `dependencyManagement` 统一钉 `2.22`，确保进 runtime classpath。
4. 迁移文件受 Flyway checksum 保护：现存 V1–V11，任何变更一律新增 **V12** 起，勿改已有文件。
5. SMTP 三个超时（`application.yml:57-59`：连接 8s / 读 8s / 写 15s）不得删除，JavaMail 默认无超时会挂死 `@Async` 发送线程。
6. 管理端鉴权只有「管理员 JWT + `users.role`」一套；`X-API-Key` / `X-Admin-API-Key` 通道已整通道移除，不得恢复。
7. 契约以代码为准：`README.md` API 段与 `接口调用时序图.md` 是实现现状；`架构与业务流程设计.md` / `授权设计方案.md` 是设计建议稿，不可作接口/字段契约依据。
8. 仓库内不得留默认支付渠道（`enabled-channels` 默认空）与沙箱网关默认值（`gateway-url` 默认生产网关）；沙箱联调只在 `.env` 显式配。
9. 未开 MFA 的 ADMIN 保持提示式横幅，不做拦截式过滤器（2026-10-16 定案，理由见 `架构与业务流程设计.md`「附：范围边界定案」）。
10. 签名密钥只认「裸 32 字节 Ed25519」或 DER，不收 PEM；生成命令唯一权威源是 `上线准备工作.md` §1.2，其余文档只引用不复制。
11. `app.base-url` 保持默认 `http://localhost:8000`（不 fail-fast，2026-10-16 定案）；生产公网 HTTPS 靠 §0/§10-3 人工核对，文档不得再写「无默认值」。
12. 账务聚合口径（不跨币种求和、已支付类才计实收、退款仅取 `REFUNDED`、分页封顶 200、跨度 ≤366 天）由 `AccountingServiceTest` / `AccountingControllerTest` 钉住；改口径必须同步改这两个测试。

## 暂不做（勿回头扩范围）

云 KMS 接入 · Redis 分布式限流与共享票据存储 · 多 kid 强制轮换与自动调度 · 管理员口令加严 · 历史签名密钥 git 历史清理 · 渠道 SDK 生产凭据接入 · 管理端 `AdminService#resolveMethod` 死代码清理

## 验证命令

- 全量测试：`mvn -o test`（2026-10-16 实跑：`Tests run: 592, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，2:19）
- 真库启动冒烟：`mvn -o spring-boot:run` 后 `curl -fsS http://127.0.0.1:8000/actuator/health`（2026-10-16 实跑：Flyway `Successfully validated 11 migrations` + `Current version of schema "public": 11`、`Initialized JPA EntityManagerFactory`（`ddl-auto: validate` 无报错）、`Tomcat started on port 8000`、`Started ... in 22.084 seconds`、health 与 readiness / liveness 均 `UP`；跑完已停进程、8000 无监听）
- 端点数第三方复核：`curl -fsS http://127.0.0.1:8000/v3/api-docs` 后数 `paths` 与操作数（2026-10-16 实跑：路径 59 / 操作 59，与注解统计及文档口径一致）
- 文档锚点全量校对：`Get-ChildItem docs/*.md, scripts/README.md` 抽取所有 `文件:行号` 后逐个比对存在性与行范围（2026-10-16 实跑：104 个唯一锚点，无越界）
- 账务专项：`mvn -o test "-Dtest=AccountingServiceTest,AccountingControllerTest"`（实跑 22 用例全绿：服务层 10 + 控制层 12）
- Jackson 解析版本核对：`mvn -o dependency:tree` 再看 jackson 行（实跑解析到 `tools.jackson.core:jackson-databind 3.2.3` / `com.fasterxml.jackson.core:jackson-databind 2.22.3` / `jackson-annotations 2.22`）
- 端点计数核对：`Get-ChildItem src/main/java/com/billing/license/controller -Recurse -Include *.java | Select-String -Pattern '@(Get|Post|Put|Delete|Patch)Mapping'`（2026-10-16 实跑＝59）
- 迁移目录与基线表数：`Get-ChildItem src/main/resources/db/migration -Name`（V1–V11）、`Select-String -Path src/main/resources/db/migration/*.sql -Pattern 'CREATE TABLE'`（15 张）
- 真库迁移状态（需目标环境有 psql；本机无 psql，改由上述启动日志的 Flyway 两行交叉核对）：`./scripts/db/show_migrations.sh`
- ⚠️ 本机 Maven 已可用（`C:\Users\qingc\scoop\apps\maven\current\bin\mvn.cmd`），旧 plan 里「本地无 mvn、构建统一走 IDEA MCP」的口径作废；`mvn` 校验类命令禁止接管道（会吞掉失败输出）。
