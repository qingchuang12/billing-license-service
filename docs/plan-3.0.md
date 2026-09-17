# plan v3.0 · 上线验证 + 授权硬化（合并活动 plan）

> 版本：v3.0（2026-09-17）
> 前序：v1.0–v2.10 各主题与专项 plan 已归档至 `archive/`。
> **合并说明**：本 plan 由原 `plan-2.9`（上线验证闸门）与 `plan-3.0`（授权设计方案落地 / 免费生产硬化）**合并而成**，现为本目录**唯一活动 plan**（遵循「未完成项合并进最新一份」纪律）。
> 来源：v2.9 的 T1–T4 上线闸门 + [`授权设计方案.md`](./授权设计方案.md) §十七「免费生产的关键注意点」及文首偏离清单（待补强项）。
> 当前状态：v2.9 部分（T1–T4、L2、W17）**阻塞于外部资源**（渠道测试密钥、公网回调端点、客户端仓库、生产 SMTP 凭据）；授权硬化项（SEC、A1–A5）可独立启动、不阻塞上线闸门。
> 算法结论（已符合，无需改动）：实际密钥为 **Ed25519 裸 32 字节**（`private.key`/`public.key` 各 32B，前导非 DER），与 `LocalKmsService` 的 Ed25519 分支及 `LicenseIssuer#getJwsAlgorithm` 的 `EdDSA` 映射一致，属「免费生产最佳」选型。
> 契约基线（G/H/I 之后）：API 统一 `/api/**`、鉴权两档（公开 / `X-API-Key`）、下单唯一入口 `POST /api/checkout/create`、管理动作在 `/api/admin/**`、共 21 个端点。**注（v2.10 后）**：账号体系新增 7 个 `/api/account/**` 端点并引入第三档鉴权（用户令牌），端点总数 28、鉴权三档；原 21 个端点路径与权限未变。

---

## 一、背景与目标

两条线合并管理：

1. **上线验证闸门（原 v2.9）**：P0/P1 阻塞项已清零、5 渠道代码层审计通过，目标为完成依赖外部资源的 4 项上线验证闸门（T1–T4）。
2. **授权硬化（原 v3.0）**：`授权设计方案.md` 偏离清单与 §十七 标出若干「待补强」项（服务端校验未重验签、管理端批量签发未绑 `mid`、缺 `update_until`/`max_major_version`、`feat` 解析静默丢弃、缺多 `kid` 公钥轮换），并发现**私钥入库风险**（根级 `private.key` 不在 `.gitignore` 覆盖内）。目标：把授权子域从「可用」补到「生产可硬化」，覆盖**安全（SEC）、正确性（A1/A2）、商业化（A3/A4）、密钥轮换（A5）**。

## 二、范围与边界

**做**：
- 上线线：T1 全渠道联调 / T2 压测 / T3 客户端 exe 验签 / T4 渗透。
- 授权线：SEC 私钥防入库；A1 服务端补验签；A2 管理端批量签发绑 `mid`；A3 `update_until`/`max_major_version` 落 payload；A4 `feat` 解析强约束；A5 多 `kid` 公钥验证/轮换。

**暂不做**：新支付渠道、Redis 共享限流、客户端仓库改造（§十六 客户端逻辑由客户端团队落地，本 plan 仅定义契约）、Vault 开源 KMS 接入（A5 若选 Vault 另立子项）。

> 文档口径：授权线设计依据为 `授权设计方案.md`；实现以代码为准。

## 三、实现思路

### SEC 私钥防入库（P0，安全）

- **触点**：`.gitignore`、仓库根 `private.key`/`public.key`、`docker-compose.yml:15-18`。
- **现状（2026-09-17 23:10 实测更正，推翻原文）**：`.gitignore` 仅忽略 `keys/` 目录（第 41–42 行），但真实密钥文件落在**仓库根** `private.key`/`public.key`（各 32B）。**原文称「未跟踪 ??、尚未入库」为误判**——`git ls-files` 命中两者（**已在版本库索引内，即私钥已入库**），`git check-ignore` 无输出（未被忽略）。另实测 `keys/` 目录**并不存在**（`Test-Path keys` = False），而 compose 挂载源为 `./keys` → 容器内 `/keys` 为空，首次签名必崩。
- **处置范围（用户 2026-09-17 拍板：仅止血，不轮换密钥、不清历史）**：① 新建 `keys/` 并把根级两密钥**移动**进 `keys/`（gitignore 已覆盖 `keys/`）；② `.gitignore` 追加 `*.key` 兜底规则，并清掉首行误留的 markdown 围栏 `` ``` ``；③ 执行 `git rm --cached private.key public.key` 移出索引；④ compose 保留 `./keys:/keys:ro`，与移动后的实际路径对齐。
- **未做（显式记录）**：密钥**未轮换**（入库过的 Ed25519 私钥按泄露风险看待，轮换待排期）；历史**未清理**（`git filter-repo` + force push 属高风险，待用户协调远端与协作方后另开任务）。
- **回归**：`git add .` 不应纳入任何 `.key` 文件。

### A1 服务端校验端点补验签（P1，纵深防御）

- **触点**：`service/LicenseService.java:124-156`（`verifyLicense` 仅查库状态 + 过期，未调 `licenseIssuer.verifyLicense`）。
- **步骤**：在 `verifyLicense(String licenseKey)` 查得 License 后，对其 `signedToken` 调用 `licenseIssuer.verifyLicense(token)` 重验签名；验签失败按 `VERIFY_FAILED` 记录事件并抛 `LICENSE_INVALID`。公开 `GET /api/licenses/verify/{key}` 返回前亦走同一校验。
- **价值**：防止私钥轮换/数据被篡改后库内旧记录与签名不一致；客户端离线验签仍是主强制点，此处为服务端兜底。
- **测试**：新增单测——篡改 `signedToken` 后 `verifyLicense` 必拒；正常 token 通过。

### A2 管理端批量签发绑 `mid`（P1，正确性）

- **触点**：`service/LicenseService.java:263-283`（`createLicense` 未设 `machineCode`，`issueLicensesForOrder` 路径产出的 License token 无 `mid`）。
- **步骤（2026-09-17 用户拍板，偏离原文）**：`createLicense` 若 `order.getMachineCode()` 非空则注入 `machineCode`（与 `issueLicense(orderId, machineCode)` 路径一致）；**订单无 `machineCode` 时仍照常签发 License**（token 不含 `mid`），后续由兑换码激活路径补绑。
- **为何偏离原文**：原文要求「无 mid 则仅产兑换码、不直发 License」，但实测 `issueLicensesForOrder` 不只是管理端入口——`CheckoutService.java:269/345`（支付成功后签发）与 `AdminController.java:138` 均调用它。照原文执行会**砍掉支付成功主流程的 License 产出**，属破坏性变更，故收敛为最小改动：只补绑、不改签发与否。
- **价值**：管理端补发/补偿签发的 License 才能被客户端离线校验设备。
- **测试**：单测覆盖「订单带 machineCode → token 含 mid」「订单无 machineCode → 不绑 mid 且仍可用兑换路径」。

### A3 `update_until` / `max_major_version` 落 payload（P2，商业化）

- **触点**：`infrastructure/crypto/LicenseIssuer.java:51-85`（payload 仅 `exp`，无更新门槛字段）；`entity/Product`；种子 `V4__product_tiers_and_seed.sql`。
- **步骤**：① `Product` 增 `updateUntilDays` / `maxMajorVersion` 字段（可空）；② `LicenseIssuer.issueLicense` 依据二者计算 `update_until`（`iat + updateUntilDays`）与 `max_major_version` 并写入 payload（保持 `meta` 之外的顶级短键，如 `umu`/`mmv` 或沿用 `meta` 内嵌，需与客户端契约统一）；③ 种子补默认档位值；④ `授权设计方案.md` §三/§十 字段示例同步为实际键名。
- **价值**：大版本升级收费、更新截止的离线强制得以落地（当前设计空窗）。
- **注意**：字段名须与 §十六 客户端逻辑一致，定下后客户端按名取值。
- **本轮键名定稿（2026-09-17）**：payload **顶级全名** `update_until`（epoch 秒，Unix 时间戳）/ `max_major_version`（Integer）。**不采用**原文备选的 `umu`/`mmv` 短键——客户端尚未对接，无省字节诉求，全名可读性显著更优且免维护映射表。二者均在对应配置为 null 时**不写入 payload**（缺失即不限制），避免用 0 值误伤旧客户端。

### A4 `feat` 解析强约束（P2，一致性）

- **触点**：`infrastructure/crypto/LicenseIssuer.java:61-66`（`product.getFeatures()` 非法 JSON 时 `catch` 静默忽略 → 客户端拿不到权益清单）。
- **步骤**：在落库/种子层约束 `features` 为合法 JSON 数组（Flyway 种子校验或 `@ColumnTransformer`/校验器）；签发时对非法值**不再静默忽略**，改为记录告警日志并置空 `feat`（或抛错，视产品容忍度），避免「部分环境有权益、部分没有」的不一致。
- **测试**：种子含一个非法 `features` 行 → 启动/签发时明确告警而非静默。

### A5 多 `kid` 公钥验证 / 密钥轮换（P3，基础设施）

- **触点**：`infrastructure/kms/KmsService` 单密钥（所有 `kid` 映射同一公钥，`授权设计方案.md` §九.2）。
- **步骤**：① `KmsService` 支持密钥版本/别名，提供「按 `kid` 选公钥」的 `verify(data, sig, kid)` 重载；② 客户端内置多公钥（按 `kid` 选），`LocalKmsService`/云 KMS 实现匹配；③ 轮换流程文档化：生成新密钥 → 配 `billing.license-kid` → 客户端发版内置新公钥 → 旧证仍由旧公钥验。
- **价值**：买断 License 有效期数年，轮换后旧证必须仍能验。
- **边界**：本项改动面较大，可先只做接口与客户端多公钥预留，密钥版本管理留待基础设施迭代。
- **本轮范围（2026-09-17）**：① `KmsService` 新增 `verify(data, sig, kid)` 默认方法（默认委派无 kid 版本，保证现有实现零破坏）；② `LocalKmsService` 维护 `Map<String, keyPath>`——主密钥沿用现路径，额外公钥由 `PUBLIC_KEY_PATH_<KID>` 环境变量或 `billing.public-keys.<kid>` 注入，按 kid 选钥；③ 轮换流程写入 `scripts/README.md`。**不做**：多版本并存签名选择、自动轮换调度。

### T1 全渠道真实沙箱/生产联调（高，上线线）

- **触点**：各 `PaymentStrategy` + `WebhookController` + `ChannelConfigValidator`。
- **前置阻塞**：① 各渠道测试密钥（建议先 Stripe）② 公网 Webhook 端点 ③ 后台配置回调 URL/签名密钥。
- **步骤**：按 `archive/plan-v2.5-t1-channel-verify.md` §2.3 的 5 步清单逐渠道执行；Paddle 重点核金额为最小货币单位整数串（`"999"` 非 `"9.99"`）。
- **风险回滚**：渠道以 `enabled=false` 关停；验签异常先反向复核配置/代码。

### T2 性能压测（中，上线线）

- **触点**：`CheckoutService.getStatus` 补偿、`RateLimitService`、并发兑换/发放。
- **步骤**：真实 PG + 单渠道密钥，压测记录 P95/P99、限流淘汰行为。独立环境，不触生产。

### T3 客户端 exe 验签（高，需客户端仓库）

- **关注点**：算法白名单、机器码绑定、过期/吊销/篡改样本 5 类验证。
- **前置**：客户端需按 G/H/I 后的契约对接（`/api/**`、单 header `X-API-Key`、License 状态含 `REISSUED`）。

### T4 渗透测试（中，上线线）

- **范围**：Webhook 验签 / admin 鉴权（现为单 header `X-API-Key`）/ 限流 / CORS / 异常脱敏 / Actuator 暴露面。

## 四、依赖与顺序

SEC（先消除入库风险）→ A1 / A2（授权正确性与兜底，改动小、价值高，可并行启动）→ T1（先 Stripe）→ T2 / T3 并行 → T4 最后 → A3 / A4（商业化，需定字段契约）→ A5（基础设施，可后置）。任一闸门发现问题回流本 plan 修复。

> ⚠️ 生效于 2026-09-14：`DB_PASSWORD` 与 `ADMIN_API_KEYS` **已无默认值**，本地联调/压测前必须先注入环境变量（容器走 `.env`，单测走 `application-test.yml` 覆盖）。
>
> 所有改动须保持 `mvn test` 全绿；A1–A4 建议各带单测，A5 至少带接口与客户端契约说明。

---

## TODOS（仅未完成项）

### N. 本轮新增未完项（2026-09-17 QA 验证后登记）
- [ ] **N1 签名密钥轮换（P0，安全）**：QA 实测 commit `2a193b9` 已把 `private.key`/`public.key` 推到 **origin/main**（已上远端），`git hash-object` 与当前 `keys/` 下密钥 blob **逐字节相同**（移动≠轮换）。在线签名私钥视为已泄露。用户 2026-09-17 决策「本轮仅止血、不轮换」，故列为待排期项；须重新生成 Ed25519 密钥对 → 旧 License 重签 → 客户端内置新公钥。
- [ ] **N2 git 历史清理**：`private.key`/`public.key` 仍在历史与远端（`git filter-repo` + force push 方可清除），高风险，需先协调远端与协作方。用户决策「本轮不清」。
- [ ] **N3 V11 迁移真实执行验证（2026-09-18 复核：仍阻塞）**：V11 在 `git status` 中仍为 `??`（**从未提交、未进入任何部署**），且 test profile 下 `flyway.enabled=false` + H2，故至今**仍未被真实执行过**，仅静态审查通过。首次带真 PG 启动前必须验证两条 `ALTER TABLE` 与 `ddl-auto: validate` 一致。本机无 docker/psql，无法自证。

### C. 上线前验证闸门（待外部资源）
- [ ] T1 全渠道真实沙箱/生产联调（前置：各渠道测试密钥 + 公网回调端点；建议先打通 Stripe）
- [ ] T2 性能压测：getStatus 补偿 / 限流淘汰 / 并发兑换，记录 P95/P99 与限流行为
- [ ] T3 客户端 exe 验签验证：算法白名单、机器码绑定、过期/吊销/篡改样本 5 类（需客户端仓库配合）
- [ ] T4 渗透测试：Webhook 验签 / admin 鉴权 / 限流 / CORS / Actuator 暴露面，发现项回流本 plan

### L. v2.10 结转遗留项（2026-09-15 并入）
- [ ] L2 上线前配置真实 SMTP 并实测「注册验证码 / 找回密码」两封邮件可达（`account.code-log-only` 仅联调兜底）——**阻塞于生产凭据，非仓库内可完成**

### 已关闭（不再保留条目）
- A. 授权子域硬化 A1–A5：2026-09-17 全部落地并通过 QA 验证（`mvn test` **169/169 全绿**，基线 152 + 新增 17）。A1 服务端重验签、A2 订单带 machineCode 才绑 `mid`（无码仍照常签发）、A3 payload 顶级 `update_until`/`max_major_version`（统一「NULL 或 <=0 = 不限制、不写入」）、A4 非法 `features` 改 `log.warn` 且不写 `feat`、A5 `KmsService.verify(data,sig,kid)` default 方法 + `LocalKmsService` 按 kid 选公钥（未知 kid 回退主公钥），轮换流程写入 `scripts/README.md`。
- W17 AWS KMS 算法/编码映射：AWS/阿里云/Azure KMS 实现与依赖已于 2026-09-17 全部移除，当前为**纯本地文件 KMS（Ed25519）**方案，无云 KMS DER↔raw 转换路径，风险关闭。
- **N4 V11 注释口径（2026-09-18 完成）**：`V11__product_update_entitlement.sql` 原注释「0=不含更新」与实现矛盾，已改为「NULL 或 <=0 = 不限制（键不写入 payload），不可用 0 表达不含更新」，文件头注释同步。**放行依据**：V11 在 git 中仍为未跟踪（`??`），从未提交也从未在任何环境执行，改文件无 Flyway checksum 风险。
- **N5 `授权设计方案.md` 偏离清单同步（2026-09-18 完成）**：顶部偏离清单中 A1（服务端重验签）、A2（批量签发绑 `mid`）、A3（版本门槛字段）三条「待补强项」已更新为已落地并写明实际语义；§三 待补强提示、§十六 客户端校验步骤第 7 步（待落地）同步为已落地。
- SEC 私钥防入库（止血部分）：密钥已移入 `keys/`（gitignore 覆盖）、`git rm --cached` 移出索引、`.gitignore` 追加 `*.key` 并清掉首行误留围栏、compose `./keys:/keys:ro` 对齐。**注意：止血≠安全闭环**，轮换与历史清理见 N1/N2。
