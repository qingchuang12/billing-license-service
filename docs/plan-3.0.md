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
- **现状**：`.gitignore` 仅忽略 `keys/` 目录（第 41–42 行，注释「Local signing keys … never commit」），但真实密钥文件落在**仓库根** `private.key`/`public.key`（各 32B，当前 `git status` 为未跟踪 `??`，**尚未入库**）；Docker 实际挂载 `./keys:/keys:ro` 并设 `PRIVATE_KEY_PATH=/keys/private.key`——根级文件既未被忽略、又未被容器使用（容器读 `/keys` 为空），属错位 + 入库隐患。
- **步骤**：① 将根级 `private.key`/`public.key` 移入 `keys/`（已被 gitignore）或显式追加 `private.key`/`public.key` 到 `.gitignore`；② 确认 `keys/` 在部署时由 Secret/挂载注入，本地联调同理；③ 之后 `git status` 应不再出现这两文件；④ 若曾误加，执行 `git rm --cached` 并从历史清理（当前未跟踪，无需此步）。
- **回归**：`git add .` 不应纳入任何 `.key` 文件。

### A1 服务端校验端点补验签（P1，纵深防御）

- **触点**：`service/LicenseService.java:124-156`（`verifyLicense` 仅查库状态 + 过期，未调 `licenseIssuer.verifyLicense`）。
- **步骤**：在 `verifyLicense(String licenseKey)` 查得 License 后，对其 `signedToken` 调用 `licenseIssuer.verifyLicense(token)` 重验签名；验签失败按 `VERIFY_FAILED` 记录事件并抛 `LICENSE_INVALID`。公开 `GET /api/licenses/verify/{key}` 返回前亦走同一校验。
- **价值**：防止私钥轮换/数据被篡改后库内旧记录与签名不一致；客户端离线验签仍是主强制点，此处为服务端兜底。
- **测试**：新增单测——篡改 `signedToken` 后 `verifyLicense` 必拒；正常 token 通过。

### A2 管理端批量签发绑 `mid`（P1，正确性）

- **触点**：`service/LicenseService.java:263-283`（`createLicense` 未设 `machineCode`，`issueLicensesForOrder` 路径产出的 License token 无 `mid`）。
- **步骤**：`createLicense` 若 `order.getMachineCode()` 非空则注入 `machineCode`（同 `issueLicense(orderId, machineCode)` 路径）；若订单无 `machineCode`，则该管理端路径**仅产出兑换码**，不直接发可离线锁机的 License（避免「一码多机」）。
- **价值**：管理端补发/补偿签发的 License 才能被客户端离线校验设备。
- **测试**：单测覆盖「订单带 machineCode → token 含 mid」「订单无 machineCode → 不绑 mid 且仍可用兑换路径」。

### A3 `update_until` / `max_major_version` 落 payload（P2，商业化）

- **触点**：`infrastructure/crypto/LicenseIssuer.java:51-85`（payload 仅 `exp`，无更新门槛字段）；`entity/Product`；种子 `V4__product_tiers_and_seed.sql`。
- **步骤**：① `Product` 增 `updateUntilDays` / `maxMajorVersion` 字段（可空）；② `LicenseIssuer.issueLicense` 依据二者计算 `update_until`（`iat + updateUntilDays`）与 `max_major_version` 并写入 payload（保持 `meta` 之外的顶级短键，如 `umu`/`mmv` 或沿用 `meta` 内嵌，需与客户端契约统一）；③ 种子补默认档位值；④ `授权设计方案.md` §三/§十 字段示例同步为实际键名。
- **价值**：大版本升级收费、更新截止的离线强制得以落地（当前设计空窗）。
- **注意**：字段名须与 §十六 客户端逻辑一致，定下后客户端按名取值。

### A4 `feat` 解析强约束（P2，一致性）

- **触点**：`infrastructure/crypto/LicenseIssuer.java:61-66`（`product.getFeatures()` 非法 JSON 时 `catch` 静默忽略 → 客户端拿不到权益清单）。
- **步骤**：在落库/种子层约束 `features` 为合法 JSON 数组（Flyway 种子校验或 `@ColumnTransformer`/校验器）；签发时对非法值**不再静默忽略**，改为记录告警日志并置空 `feat`（或抛错，视产品容忍度），避免「部分环境有权益、部分没有」的不一致。
- **测试**：种子含一个非法 `features` 行 → 启动/签发时明确告警而非静默。

### A5 多 `kid` 公钥验证 / 密钥轮换（P3，基础设施）

- **触点**：`infrastructure/kms/KmsService` 单密钥（所有 `kid` 映射同一公钥，`授权设计方案.md` §九.2）。
- **步骤**：① `KmsService` 支持密钥版本/别名，提供「按 `kid` 选公钥」的 `verify(data, sig, kid)` 重载；② 客户端内置多公钥（按 `kid` 选），`LocalKmsService`/云 KMS 实现匹配；③ 轮换流程文档化：生成新密钥 → 配 `billing.license-kid` → 客户端发版内置新公钥 → 旧证仍由旧公钥验。
- **价值**：买断 License 有效期数年，轮换后旧证必须仍能验。
- **边界**：本项改动面较大，可先只做接口与客户端多公钥预留，密钥版本管理留待基础设施迭代。

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

### SEC. 私钥防入库（P0）
- [ ] 将根级 `private.key`/`public.key` 移入 `keys/`（已被 gitignore）或显式追加到 `.gitignore`
- [ ] 确认 `keys/` 由部署 Secret/挂载注入，`git add .` 不再纳入任何 `.key` 文件
- [ ] 核对 `docker-compose.yml` 的 `./keys:/keys:ro` 挂载路径与实际密钥位置一致

### A. 授权子域硬化
- [ ] A1 服务端 `verifyLicense` 补 `licenseIssuer.verifyLicense(token)` 重验签 + 单测
- [ ] A2 管理端 `createLicense` 绑 `machineCode`；订单无 `mid` 时仅产兑换码不直发 License + 单测
- [ ] A3 `Product` 增 `updateUntilDays`/`maxMajorVersion`；`LicenseIssuer` 注入 `update_until`/`max_major_version` + 种子 + 文档同步
- [ ] A4 `features` 落库/种子层强约束为合法 JSON 数组；签发非法时告警而非静默 + 测试
- [ ] A5 `KmsService` 支持按 `kid` 选公钥；客户端内置多公钥预留；轮换流程文档化

### C. 上线前验证闸门（待外部资源）
- [ ] T1 全渠道真实沙箱/生产联调（前置：各渠道测试密钥 + 公网回调端点；建议先打通 Stripe）
- [ ] T2 性能压测：getStatus 补偿 / 限流淘汰 / 并发兑换，记录 P95/P99 与限流行为
- [ ] T3 客户端 exe 验签验证：算法白名单、机器码绑定、过期/吊销/篡改样本 5 类（需客户端仓库配合）
- [ ] T4 渗透测试：Webhook 验签 / admin 鉴权 / 限流 / CORS / Actuator 暴露面，发现项回流本 plan

### L. v2.10 结转遗留项（2026-09-15 并入）
- [ ] L2 上线前配置真实 SMTP 并实测「注册验证码 / 找回密码」两封邮件可达（`account.code-log-only` 仅联调兜底）——**阻塞于生产凭据，非仓库内可完成**

### 遗留风险（从 B 结转）
- [x] W17 AWS KMS 算法/编码映射：原修复针对 AWS KMS 路径；AWS KMS 实现已于 2026-09-17 移除（`AwsKmsService`/`AwsKmsServiceTest` 删除），该风险项随之关闭；当前为纯本地 Ed25519 + 阿里云 KMS 方案，无需云 KMS DER↔raw 转换路径。
