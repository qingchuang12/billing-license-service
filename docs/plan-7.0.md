# plan-7.0 · 凭证激活体系统一 + 管理员 MFA + 管理端 License 处置 + 决策定案后续开发（含退款 / 统一登录遗留收尾）

> 本 plan 由 `plan-4.1`（用户端退款）、`plan-5.0`（凭证体系治理）、`plan-6.0`（统一登录）三份并行 plan 于 2026-09-23 合并而成，原三份文件已删除。未完成项均标注来源。

## 背景与目标

原 docs/ 下三个活动 plan 并存，违反「同一目录单一活动 plan」纪律（且存量 plan 的文件名 `5.0`/`6.0` 与其首行版本号 `2.1`/`3.0` 不一致，引用面亦出现断链）。合并时三线状态：**用户端自助退款**与**管理员/消费者统一登录**已收口，**凭证激活体系尚未落地**。

**主体一已落地（2026-09-23）**：凭证激活体系统一按方案 A 实现完成。
其契约与实现现状以 `README.md`「凭证激活」段、`接口调用时序图.md` §1.7.6 / §3.13–3.14 / §4.10 为准，本 plan **只承载未完成项与待决策**。

**主体二（2026-09-23 追加）：管理员 MFA**。原 B8「管理员口令策略 / 是否要求二次因子」已拍板（见 §3）。该文件本就是多 plan 合并后的**容器**，故本次沿用「容器内追加主体」而非另开 plan、亦不改名——改名会破坏既有跨文档引用（`架构与业务流程设计.md` 引 `plan-7.0 方案 A`、`上线准备工作.md` 引 `plan-7.0 §3`）。**M1–M7 已全部落地**，实现现状见 `README.md`「二次因子（MFA，B8）」段、`接口调用时序图.md` §3.15 / §4.11 / §5.2，测试基线升至 **330 测试 0 失败 0 错误**。

**主体三（2026-09-23 追加）：管理端 License 处置**。起因是 **B3 拍板**（账号页**维持明文展示 `licenseKey`**，安全由管理端处置能力兜底）。查证后发现管理端只有 `revoke` / `reissue` 两个**无 UI 的 API**，而**解绑端点根本不存在**——「兜底」名不副实，故本次补齐。

**主体四（2026-09-23 追加）：决策定案后的开发项**。川哥对本 plan 的 B2/B5/B6/B7/B9/B10/B11 与 C1 一次性拍板（B11 指定「按最佳架构方案」= 统一作废实现），定案与依据见 §5「决策定案」；其中 B7 / B9 / B10 / B11 转为开发项（**D3、D4、D5、D6**），B5 落点在客户端，并**新增一项需求**——服务端「自动上报绑定」端点（**D2**）。**E1（归属凭证 = 凭 `signedToken`）与 Y1（无外部脚本，关闭）亦已定案**，故**全 plan 已无待决策项**。**已落地：D5（删旧端点）、D6（作废统一 + 退款吊销补留痕）——333 测试 0 失败 0 错误；D2（自动上报绑定端点，见 §6）——338 测试 0 失败 0 错误；D3（机器「已转正」标记，见 §5「B7」拍板与落地）——343 测试 0 失败 0 错误**；**D1（删自动发码）因 B2 = C 早期记述属误读，已整体回退**（正确口径见 §5「B2」）；余 **D4** 待开发。

**剩余目标**：落地 **D4**（管理端用户管理 API）；收口两处遗留（Paddle 续费幂等、管理员降权入口的实现）；并把跨仓库客户端改造（A9）推进到位——其改造量已查实（见 C2 结论）。

## 范围与边界

**做**
- **管理端 License 处置（主体三，2026-09-23 追加）**：新增管理端**解绑**端点（只清设备绑定、保留授权）；`reissue` 增**管理端豁免**重发上限的显式开关并放宽 `newMachineId` 为可选；管理台新增「License 管理」分区（查询 + 解绑 / 作废 / 失效重发）。
- **管理员 MFA**：TOTP 主因子 + 邮箱验证码兜底；默认关、管理员在管理台自助开启/解绑（B8 定案）。
- 跨仓库 `ai-tools` 客户端契约对齐（A9：两条自动路径）。
- Paddle 续费幂等加固（防重复延长 License）。
- 管理员降权/停用即时生效的**入口**决策与落地。
- 激活体系落地后衍生的决策收口（B2–B7、B10）。

**暂不做**
- **管理员口令策略加严**（B8 已定「不加严，只做 MFA」）：不做管理员专用复杂档、不做弱口令黑名单、不做定期轮换。
- **一次性恢复码**：邮箱码已覆盖同一场景（认证器丢失 / 换机），再加恢复码是重复设施。
- 面向普通用户的 MFA（本次仅管理员；实现上挑战条件不判角色，故逻辑天然可扩展，但不开入口）。
- 退款申请单 / 审核工作流。
- 订阅类订单自助退款（渠道侧无取消订阅 API，仅 webhook 侧处理 `canceled`）。
- 退款政策页面文案（官网侧）。

## 实现思路

### 1. 凭证激活体系统一（主体一，已落地）

**已完成口径**（实现现状见 `README.md` / `接口调用时序图.md`，此处只留影响后续决策的结论）：

- **单一入口**：`POST /api/licenses/activate`，`credential` 按格式自动分流（`RC-` 前缀 → 兑换码分支；其余 → 许可证密钥分支），两条分支统一返回 `ActivateResponse`。端点 `permitAll` + 分支内判登录（`CurrentUserResolver.currentUserIdOrNull()`）。
- **内核落点**：可复用的「绑定 / 签发 / 签名」内核是 **`LicenseService.bindToMachine`**（不是 `CredentialBindingService`——后者是凭证分发器）。原三份拷贝（购买直签 / 兑换 / 管理端签发）已全部改调内核，**行为不变**。
- **安全红线**：许可证密钥分支**必须登录且归属本人**（`License.customerId == users.id`）；越权与不存在同码返回 `CREDENTIAL_NOT_FOUND`。此点**不可松动**——账号页明文展示 `licenseKey`，一旦改为匿名可绑，明文泄漏即等于抢绑。
- **换机恢复路径已补**：`POST /api/account/licenses/{licenseKey}/unbind`（决策 B6 的出口）。此前账号页**无任何解绑 UI**、`LicenseResponse` 对账号接口也不返回 `signedToken`，故「引导用户去解绑」本无着落；现已落地，`MACHINE_MISMATCH` 不再是死路。
- **「凭证不认识」错误码已统一为 `CREDENTIAL_NOT_FOUND`**（B4，2026-09-23）：此前密钥分支 `LICENSE_NOT_FOUND`、兑换分支 `CODE_NOT_FOUND`。统一手法是**各出改一处**——`RedeemCodeService.doRedeem`（被两个端点共用，改这里即两处一致）+ `CredentialBindingService` 密钥分支，**不加「异常码翻译层」**（那本身是新漂移源）。只统一「凭证不认识」这一类；`CODE_ALREADY_USED` / `CODE_EXPIRED` / `LICENSE_NOT_ACTIVE` 是**状态类**语义，保持原码。
  - **刻意保留的不同码**（勿「顺手统一」）：`GET /api/licenses/verify/{licenseKey}` 仍 `LICENSE_NOT_FOUND`（第三个端点，不在本次范围）；账号页自助解绑仍 `LICENSE_NOT_FOUND`（资产操作语义，且 `account.js` 已按该码映射文案）；管理端撤销兑换码 `revokeCode` 仍 `CODE_NOT_FOUND`。
  - **后续可选**（未决）：`verify` 端点的 `LICENSE_NOT_FOUND` 是否也并到 `CREDENTIAL_NOT_FOUND`。

### 2. 遗留收尾

**Paddle 续费幂等**
`SubscriptionService.bindOrRenewLicense:120` 对每个 paymentSuccess 事件都执行续期（`license.setExpiresAt(base.plusDays(days)):146`），而 `PaddleStrategy:306-319` 把 `subscription.*`（`active`/`trialing`）与 `transaction.completed`/`transaction.billed` **全部映射 `SUCCESS`** → Paddle 侧一次计费可能推送多个成功事件，导致 License 被重复延长。加固方向：以 `payload.getCurrentPeriodEnd()` 与 `license.getExpiresAt()` 比对做幂等。**前置：先确认 Paddle 真实事件流**（与 H7 沙箱实测同批）。

**管理员降权/停用入口（结论已重新界定）**
原先记为「需开发：降权/停用时 `tokenVersion + 1`」并注明「依赖角色变更入口，目前尚无该入口」。实查后**机制已具备**：`JwtAuthFilter` 每请求查 `status` + `tokenVersion`，而 `promote-to-admin.sql` 的降级回滚语句**已含** `token_version + 1`。因此剩余问题不是「实现踢下线」，而是**是否要提供代码级角色变更/停用入口**（当前仅运维 SQL 一条路径；`AdminController` 实查无任何用户管理端点）——已改列为决策项 B9。

### 3. 管理员 MFA（主体二，B8 定案 2026-09-23 · **已落地**）

**定案（川哥拍板）**：TOTP 主因子 **+ 邮箱验证码兜底**；**默认关、管理员在管理台自助开启**；**口令策略不加严**（不做管理员专用档/黑名单/轮换）；`account.mfa-key` **缺失即拒绝启动**（fail-fast，与 `account.jwt-secret` 同风格）。

**实查前提（这些事实决定了方案形态，非假设）**

| 事实 | 证据 | 影响 |
|---|---|---|
| 管理员登录**复用** `/api/account/login`，与普通用户同一口令策略 | `admin.js:120-137` 登录后再自查 `role` | 不存在「管理员专属策略」；MFA 闸门必须落在**签发令牌处** |
| **角色不写进 JWT、每请求现查** | `JwtAuthFilter:96-99` | 只在管理台 UI 加一步输入是**假安全**——绕过页面直调 `login` 仍拿得到 token |
| 离线仓**无任何 TOTP 库** | `~/.m2` 全盘无命中；构建走 `-o` | 引入新依赖不可行 → TOTP **手写 RFC 6238**（HMAC-SHA1 + Base32 ≈ 40 行），零依赖 |
| `KmsService` 是 **Ed25519 签名服务，无对称加解密** | `KmsService` 仅 `sign`/`verify`/`getPublicKey` | TOTP 密钥落库**不能复用 KMS**，需另写 AES-GCM 工具 |
| 静态页**完全自包含、零 CDN 资源** | 三页仅有官网品牌链接 | 二维码无库可用 → 展示 `otpauth://` URI + Base32 密钥文本，用户手动录入 |
| `verification_codes.purpose` 为 `VARCHAR(30)` **无 CHECK 约束** | `V1__baseline_schema.sql:478` | 新增 `LOGIN_MFA` 枚举**零 DDL** |

**关键设计**

1. **票据密钥必须与 `account.jwt-secret` 隔离（安全关键）**。`JwtTokenService` 用同一密钥签发、`JwtAuthFilter` 只校验 `sub`+`ver`+签名。若票据复用它，票据即为一枚**合法 access token** → 攻击者持票据直接调 `/api/admin/**`，**MFA 被整体绕过**。故 `MfaTicketService` 用**派生独立密钥**签名，票据天然无法通过 `JwtTokenService.parse`（此点以单测断言固化，不靠注释约束）。
2. **两阶段登录**：`login` 密码通过且 `mfa_enabled` → **不发令牌**，返回 `mfaRequired:true` + 短时效票据（默认 300s，载荷含 `sub`+`ver`，改密即随 `tokenVersion` 失效），`user` 返回 `null`（未过第二因子前不泄漏账号信息）。两个新字段直接加进 `AuthResponse`，不另立响应类——沿用项目「同一实体单一视图」的既有取舍。
3. **两个挑战端点必须 `permitAll` 且声明在 `/api/account/**` 的 `hasAuthority(ROLE_USER)` 规则之前**——此刻用户尚无令牌，否则死锁。与登录端点同坑，`SecurityConfig` 原注释已警告。
4. **仅新增一个密钥** `account.mfa-key`（≥32 字节），用 HMAC-SHA256 派生两个**互不相关**的子密钥（票据签名 / TOTP 密钥加密）。运维只多配一个变量，同时满足密钥隔离。
5. **挑战条件 = `mfa_enabled`（不判角色）**：降权后仍受保护（更保守）。绑定入口只在管理端 `/api/admin/mfa/**`（`ROLE_ADMIN`），故只有管理员能开启。降权脚本一并清 MFA 字段，堵住「非管理员却被挑战、又无 UI 解绑」的死角。
6. **TOTP 重放防护**：新增 `mfa_last_used_step`，同一时间步的码只能用一次（RFC 6238 §5.2 建议）；时间步容错 ±1。
7. **恢复路径 = 邮箱码**（`CodePurpose.LOGIN_MFA`）：整段复用 `VerificationCodeService`（发送冷却 / 失败计数 / pepper 哈希全现成）。**不另做一次性恢复码**。
8. **绑定 / 解绑均需二次校验**：`enroll` 与 `unbind` 要求**当前口令**（防 session 劫持后静默绑定攻击者认证器），`unbind` 另需动态码或邮箱码；逐条走 `RateLimitService`。
9. **自救路径**：新增运维脚本 `scripts/db/reset-admin-mfa.sql`（照 `promote-to-admin.sql` 格式），清空指定账号 MFA 字段以重新绑定——覆盖「认证器丢失且邮箱不可用」与「`mfa-key` 轮换后旧密钥解不开」两种情形。

**端点（6 个，34 → 40）**

| 端点 | 鉴权 | 说明 |
|---|---|---|
| `POST /api/account/mfa/challenge` | 票据（`permitAll`，票内校验） | 发送邮箱兜底码（用途码 `LOGIN_MFA`） |
| `POST /api/account/mfa/verify` | 票据（同上） | TOTP 或邮箱码 → 换完整令牌 |
| `GET /api/admin/mfa/status` | `ROLE_ADMIN` | 查询绑定状态（不回显密钥） |
| `POST /api/admin/mfa/enroll` | `ROLE_ADMIN` | 生成密钥（须当前密码） |
| `POST /api/admin/mfa/activate` | `ROLE_ADMIN` | 认证器动态码激活 |
| `POST /api/admin/mfa/unbind` | `ROLE_ADMIN` | 解绑（须密码 + 动态码/邮箱码） |

> 落地时新增的两条结论（原设计未预见，实现后补记）：
> 1. **`VerificationCodeService` 改为「返回值而非抛异常」的统一入口**——`challenge` 需要在自身事务内调用发码，若发码方法抛业务异常被外层 catch，会触发 `UnexpectedRollbackException`（内层事务已标记回滚）。改为返回结果对象后，两层各自决定语义。
> 2. **`AuthResponse.user` 在待第二因子时返回 `null`**（而非回填用户资料）——未过第二因子前不泄露账号信息；`mfaMethods` 同步告知客户端可用因子（`["TOTP"]` 或 `["TOTP","EMAIL"]`），前端据此决定是否显示「改用邮箱码」。

### 4. 管理端 License 处置（主体三，2026-09-23 追加 · **已落地**）

**实查前提（决定方案形态，非假设）**

| 事实 | 证据 | 影响 |
|---|---|---|
| 管理台**零写操作 UI**（MFA 之外） | `admin.js` 内 `revoke`/`reissue` **零命中**；tab 只有 6 个只读统计 + 安全设置 | 需新增整个分区 |
| 管理端**无解绑端点** | `AdminController` License 组仅 `list / get / revoke / reissue` | 需新增；现有四种解绑/失效语义（客户端 `unbindDevice`、账号侧 `unbindByOwner`、`revoke`、`reissue`）**都不是**「管理员手动解绑」 |
| `reissue` = 旧证置 `REISSUED` + **签发新 key** | `LicenseService:344-374`（`.licenseKey(generateLicenseKey())`） | 「失效并重新生成 key」**可直接复用**，无需新语义 |
| `reissue` 受 `licenseReissueMax = 5` 风控上限 | `BillingProperties:73`；`LicenseService:336-342` 按原 key 累计 `REISSUED` 事件计数 | `revoke` 后**不能再** `reissue`（显式抛 `LICENSE_REVOKED`）→ 客服触限时**无路可走**，故需豁免开关 |
| 端点 `newMachineId` 标 `required = true`，而 service 允许空 | `AdminController:193` vs `LicenseService:327` | 放宽为可选，否则「只失效重发、不绑新机」无法表达 |
| **账号页明文展示已存在** | `account.js:570` `escapeHtml(item.licenseKey)` + 复制按钮 | **B3 拍板保留 → 前端零改动**；`LicenseResponse.adminView` 对账号接口本就不返回 `signedToken` |

**实现**

1. **新增端点** `POST /api/admin/licenses/{licenseKey}/unbind?reason=`（40 → 41）。
2. **`LicenseService.unbindByAdmin(licenseKey, reason)`**：查件 → 不存在抛 `LICENSE_NOT_FOUND` → `REVOKED` 拒绝（沿用 `LICENSE_REVOKED`，**与账号侧 `unbindByOwner` 逐字一致**）→ 本就未绑定**幂等返回**（不落库、不重复留痕）→ 清 `machineCode` → `recordLicenseEvent(UNBOUND, 解绑前机器码, "admin: <reason>")`。
3. **`reissueLicense` 增 `boolean force`**：达上限时 `force=false` 抛 `LICENSE_REISSUE_LIMIT`（**默认行为不变**）、`force=true` 放行且**在事件 detail 标注 forced**——人工越权必须留痕，否则审计看不出「这是豁免操作」。
4. **管理台新增「License 管理」分区**：查询（按密钥精确 / 按邮箱列表）→ 结果表（密钥 / 状态 / 客户 / 机器码 / 到期）→ 选中行展开**内联处置表单**（解绑 / 作废 / 失效重发），全部走带令牌的 POST；`rangePanel` 对该分区一并隐藏（与「安全设置」同处理）。
5. **测试**：`LicenseServiceTest` 补 `unbindByAdmin`（正常 / 幂等 / `REVOKED` 拒绝 / 不存在）与 `reissueLicense` 的 `force` 豁免（触限时 `false` 拒、`true` 过）。

**取舍**

- **解绑只清机器码，不吊销授权**——与账号侧、客户端侧语义一致；管理端不提供「解绑即作废」，作废是独立动作（且不可逆），两者必须分开，否则客服一次误点就把用户权益清了。
- **重发上限默认仍生效**，`force` 是**显式**逃生口而非默认放行——防误点，同时给人工作业留路（这与「管理端豁免」的口径不矛盾：豁免是能力具备，不是默认行为）。
- **UI 侧作废要求必填原因**（端点 `reason` 仍可选以兼容既有调用）——售后追溯需要，接口层不强制以免破坏兼容。

**风险 / 回滚**

- 管理端解绑会清掉机器码，用户随后可在新机激活——**不涉及归属变更**（`customerId` 不动），与「抢绑」风险无关。
- 越权由框架保证：端点在 `/api/admin/**` 下，`SecurityConfig` 统一要求 `ROLE_ADMIN`。
- 回滚：移除端点与 UI 分区即可；`reissueLicense` 新增的 `force` 为**可选参数且默认 false**，既有行为不变；`license_events.event_type` 为 `VARCHAR(50)` **无 CHECK**，复用既有 `UNBOUND` 枚举**零 DDL**。

### 5. 决策定案（2026-09-23，川哥拍板）

**B2 = C｜保留无机器码订单的自动发码（邮件 + 网站可查）· 2026-09-23 口径修正**

> ⚠️ **本条早期记述（「兑换码收窄为人工发放 / 删除自动发码分支」）是误读，已作废。** 据此误读实施的开发项 **D1 已整体回退**（代码回到自动发码状态，测试基线回落 335 → 333）。以下是川哥 2026-09-23 的澄清口径。

- **正确语义**：**网上付款（无机器码订单）照常自动发兑换码**——系统生成后**发兑换码邮件**，客户也可登录网站（收银台 / 账号页）查看，之后再绑定机器使用。
- **客服人工发码（`POST /api/admin/redeem-codes/generate`）的用途 = 活动 / 运营发放**，**不是**购买交付路径；它与自动发码**并存、互不替代**（该端点本就存在，无需改动）。
- **现状：无需改动**。自动发码三处（`CheckoutService.getStatus` + `fulfillSession` + **`WebhookController` 渠道回调发货**）与 `RedeemCodeService.generateCode(String orderId)` **均保持原样**；`EmailNotificationService.sendRedeemCodeEmail` 仍是自动发码邮件的投递实现（**有调用者，非死代码**）。
- **与 D2 的配套关系**：客户付款后拿到码 → 在客户端激活 → 客户端启动时**自动上报机器码**完成绑定（D2，E1 = ① 凭 `signedToken` 验签）。这才是 B2 与 D2 的原本分工。

**B5 = ③ 手动按钮 + ① 轮询（客户端，上限 1 小时）**

- **定案**：客户端付款后轮询 `GET /api/checkout/{checkoutId}/status` 自动存证激活，**最长 1 小时，超时即停止轮询**；并保留手动按钮兜底。② 重启自愈不采用（会让「付完款无反应」成为主要客诉）。
- **服务端无阻碍（已实查，故本仓无需改动）**：① 该端点**不受任何限流**——`CheckoutController:67-72` 直调 `checkoutService.getStatus`，无 `rateLimitService` 调用；② 对渠道的主动对账已有 **30 秒冷却窗口**（`CheckoutService:38` `COMPENSATION_COOLDOWN_SECONDS = 30`，R3 修复）→ 1 小时内高频轮询**不会**放大渠道 API 调用；③ 发放侧已有 `synchronized(orderLock)` + 「优先返回已签发」幂等（R5 / B13）。

**B6 = A｜维持保守换机口径（不改）**

- **现状依据**：`CredentialBindingService:107-120`——该件已绑他机且机器码不同 → `MACHINE_MISMATCH`，**不自动改绑**；恢复路径 = 账号页自助解绑（已落地）+ 管理端解绑（本轮落地）。改动点**仅此一处**。
- **定案**：**不改**。自动放行等于「拿到登录态即可迁走授权」，收益（省一次点击）远小于风险。

**B7 = B｜增加「已转正」标记**

- **现状依据**：`machine_first_seen.source` 列**在写但全仓无人读**（grep 仅 `MachineFirstSeen:56` 构造器自身命中，无任何 `getSource()` 调用）；C8 端点只回 `firstSeenAt` → 当前**没有**「该机器已转正」判定，试用完全靠首次出现时间回溯。
- **定案**：**新增「已转正」标记**（购买/激活成功后置位），使试用与正式授权解耦，为「购买后试用期抵充/续期」一类运营玩法留出判定依据。
- **实现前必须先定的四个口径**：① 标记落点（新列 `converted_at` vs 复用 `source`——注意 `source` 语义是「**首次**来源」且不可变，机器先 `PROBE` 后 `PURCHASE` 时无法表达转正，**倾向新列**）；② 哪些事件算转正（`PURCHASE` / `ACTIVATE` / `REDEEM` 是否都算）；③ `REVOKED` 后是否回退（建议**不回退**——授权已被使用过，回退等于再送一次试用）；④ C8 端点是否对外回传该标记。
- **拍板与落地（2026-09-23，川哥四项均按建议项拍板）**：①落点 = **新列** `machine_first_seen.converted_at`（迁移 **V7** `V7__machine_converted_at.sql`，NULL = 未转正）；②**任何一次正式绑定都算转正**——购买直签 / 兑换码 / 密钥激活 / D2 启动上报四条路径已汇入 `bindToMachine` 内核，故在该内核 `touch` 之后调 `MachineRegistryService.markConverted` **一处全覆盖**（幂等：已转正不覆盖首次转正时间；并发竞态下行缺失时兜底建转正行）；③**作废 / 退款不回收**（撤标记 = 再送一次试用）；④**C8 端点暂不回传**（客户端契约零改动，将来要用加字段即可向后兼容）。实查事实：`source` 字段**写了永不改**且机器可能先 `PROBE` 后付费，复用它表达不了转正——与①的新列结论互证。**测试基线 338 → 343**（`MachineRegistryServiceTest` 4 例 + `LicenseServiceTest` 内核钩子 1 例）。

**B9 = B｜增加代码级角色变更 / 停用入口**

- **现状依据**：`AdminController` 12 个端点全为订单 / License / 兑换码，**零用户管理端点**；`promote-to-admin.sql:29-32`（含 `token_version + 1`，降级段注释亦有）是唯一路径；踢下线机制已具备（`JwtAuthFilter` 每请求查 `status` + `tokenVersion`）。
- **定案**：**新增管理端用户管理 API**（角色变更 / 停用启用），内部**必须** `tokenVersion + 1`，动作走 `@Audit` 留痕——**这正是选 B 的核心理由**：纯 SQL 操作不进审计，「谁何时把谁提成管理员」查不到。
- **实现前必须先定的两条**：① 端点形状（倾向 `PATCH /api/admin/users/{id}/role` 与 `/status` 两条）；② 护栏（建议**禁止自我提权**、**禁止降级/停用最后一个管理员**，否则会把管理台锁死）。

**B10 = A｜删除旧端点（**修正原推测**）· 已落地（2026-09-23）**

- **现状依据**：`POST /api/licenses/unbind` **实现完整但不可达**——`LicenseController:111-113` → `LicenseService.unbindDevice(signedToken, machineId)`：凭**旧授权签名令牌 + 机器码匹配**解绑，且**仅同机有效**（用于同机换绑新授权前释放旧授权）；它**不在 `SecurityConfig` 白名单** → 落 `anyRequest().denyAll()` → 403。注意签名令牌**仍有发放方**（`CheckoutService:297-300` 对已绑机订单仍回 `getSignedToken()`），故「恢复可用」技术上成立，**不是纯死代码**。
- **定案**：**删除**端点 + `unbindDevice` 方法；同机换绑语义由账号页解绑承接。
- **连带**：跨仓 `ai-tools` 的 `constants.ts` 必须改指向账号侧端点（已登记其 `plan-4.1.md`）。

**C1 = 不考虑存量（默认存量 0）**

- **定案**：密钥分支「未绑定则允许绑定」**不做存量清洗**；`licenses` 中未绑定件的规模按 **0** 计。

**B11 = 修，按最佳架构方案（统一作废实现）· 已落地（2026-09-23）**

- **现状依据**：生产路径 `POST /api/admin/licenses/{key}/revoke` → `AdminController:186` → `AdminService.revokeLicense:350-357`：只置 `REVOKED` + `revokedAt` + `log.info`，**不写 `license_events`**；带留痕的同名方法 `LicenseService.revokeLicense:211-221` 的**唯一调用者是它自己的单测**（`LicenseServiceTest:157`）→ 生产零调用。即作者把作废逻辑搬进 `AdminService` 时**漏了留痕**，原版沦为孤儿。后果：`license_events`（License 生命周期账本）**永久缺作废记录**，售后按事件排查会得出「该件从未被作废」的错误结论。
- **定案**：**收进 `LicenseService` 内核**，只存一处——`revokeLicense(licenseKey, reason)` 形状对齐 `unbindByAdmin` / `reissueLicense`；删除 `AdminService.revokeLicense` 与无参 `LicenseService.revokeLicense` 两处旧实现。选此方案的理由：① 作废只此一处，不再两版漂移；② 流水账完整；③ 管理端三个处置动作（解绑 / 重发 / 作废）的接口形状与留痕口径完全一致，后续维护只认一处。
- **落地时发现同一缺陷的第二处（已一并修）**：**退款吊销**路径 `AdminService.refundOrder` 的「逐条置 REVOKED」同样**不写 `license_events`**（只置状态 + save）。若只修管理端作废，流水账依旧不完整、D6 的目标落空。故一并补：该循环改为经 `licenseService.recordLicenseEvent(REVOKED, 作废前机器码, "Revoked by refund. order=…")` 留痕（`AdminService` 新增对 `LicenseService` 的依赖；两者无循环依赖）。<br>「退款吊销」与「管理端作废」仍是**两次不同的业务动作**（前者的状态迁移发生在退款流程内），故未强行走同一个方法，但**事件口径统一到同一个 helper**。

**E1 = ①｜「自动上报绑定」凭客户端 `signedToken` 证明归属**

- **定案**：客户端上报机器码时，一并携带其持有的 `signedToken`，服务端**验签通过即完成绑定**（与已删的 `unbindDevice` 同族口径）。已实查 `LicenseService.bindToMachine:114` **无条件**签发 `signedToken`（**未绑定件亦有**），故客户端兑换/激活后必然持有 → **零登录依赖**，D2 即刻可做。
- **风险**：`signedToken` 即授权文件本体，泄漏等价于授权泄漏，**不新增攻击面**；且只对「未绑定」件生效，已绑定他机仍按 `MACHINE_MISMATCH` 拒绝。

**Y1 = 无外部脚本，关闭**

- **定案**：川哥确认**本仓库之外没有**依赖 X-API-Key 调管理端接口的脚本 / Postman / CI / 其他仓库。本仓内早已全量清除（仅剩注释里的历史说明），故本项**关闭**，无需改动。

### 6. 自动上报绑定（D2，2026-09-23 落地）

**口径**：B2 = C 澄清后确认「网购无机器码订单**照常自动发兑换码**（邮件 + 网站可查）」，客户在客户端激活；激活时**未携带机器码**则该 License 落「未绑定」态——故需一条**补绑**通道。这才是 B2 与 D2 的原本分工。**归属凭证 = E1 ①（凭 `signedToken` 验签，无需登录）**。

**实现现状**（不在此重复，以 `README.md`「客户端自动上报绑定（plan-7.0 / D2）」段与 `接口调用时序图.md` §4.13 为准）：新增 `POST /api/licenses/report-binding`（**41 → 42**，公开）；`LicenseService#reportBinding` 内核顺序＝非空校验 → 机器码限流（`REPORT_RATE_LIMIT`）→ 验签（`CREDENTIAL_NOT_FOUND`，B4 口径）→ 取 `lic` → 非 ACTIVE 拒 → 已绑同机幂等 / 已绑他机 `MACHINE_MISMATCH`（守 B6 = A）→ 补绑 + 记 `BOUND_BY_REPORT`；限流**复用机器码档位**（独立命名空间 `machine-report`，不新建配置项）。**测试基线 333 → 338。**

**对 A9 的依据**：客户端侧「兑换 / 激活后于启动时上报一次机器码」的接口契约即上述端点；响应合同与 `POST /api/licenses/activate` **同构**（`ActivateResponse`，含 `signedToken` / `serverTime`），客户端可复用同一套解析。**`signedToken` 即授权本体，客户端兑换 / 激活后必然持有 → 本项无需先做登录，可先于「新增账号登录能力」落地。**

## 7. 取舍与风险

- **`ACCOUNT_MFA_KEY` 是本轮唯一的破坏性变更**：fail-fast 意味着**现有部署升级前必须先配好该变量**，否则服务起不来。已列入登记表与 `上线准备工作.md`。
- **迁移占位**：MFA 新增列由 `V6__users_mfa.sql` 落库（`users` 四列）；D3 的转正标记由 `V7__machine_converted_at.sql` 落库（`machine_first_seen.converted_at`，2026-09-23）。**下一个可用版本为 V8**。`mvn test` 不跑 Flyway，故单测不依赖新增列存在（与既有约定一致）。
- **密钥轮换/丢失后已绑定的 TOTP 密钥不可解密**：即便 fail-fast 也一样（起不来）。缓解 = `reset-admin-mfa.sql` 重新绑定；且**邮箱兜底不依赖该密钥**，故不会把管理员硬锁在管理台外。
- **邮箱兜底的因子独立性弱于 TOTP**：邮箱与登录标识同源。故保留 `account.mfa.email-fallback-enabled` 开关，且文档**必须标明档次差异**，不得宣称「邮箱码 = 同等强度第二因子」。
- **票据在 TTL 内可重放，但不足以冒用**：票据不含动态码，且 TOTP 侧有 `mfa_last_used_step` 防同码重放、`verifyFailMax` 限制猜测次数（6 位码 + 5 次上限 + 300s TTL）。若日后多实例部署，需把失败计数迁到共享存储（与既有限流同源问题）。
- **「抢绑」已被落地实现堵住，红线须保留**：归属判定取登录用户 id，明文 key **单独泄漏不足以完成绑定**（需同时持有受害人登录态）。**若日后有人把该分支放宽为匿名，「抢绑」立即回归，B3 也随之重新升级为安全红线**。
- **渠道部分退款未收口风险（须实测）**：`PaymentStrategy.refundPayment` 现为 `boolean`，无法区分「渠道明确拒绝部分金额」与「调用超时/网络失败」。若部分退款实际已受理却返回 false，降级会**重复退款**。缓解：失败与降级均写审计 + metadata 便于对账；列入登记表的渠道沙箱实测。
- **跨仓库契约风险已收窄（C2 已查实）**：`ai-tools` 客户端**只支持兑换码**、**无登录代码**，故 A9 的改造量集中在「先做账号登录」+「改指向新端点」两件事；且其对外文案已统一为 `license.errors.generic`（不读服务端错误码），故本仓改码**无需**客户端 i18n 联动。**注意其 plan 已由用户合并为 `plan-4.1.md`（原 `doc/plan-3.1.md` 已并入）**。
- **回滚**：MFA 属增量列 + 增量端点，回滚 = 移除端点与列；`verification_codes` 新枚举值无 DDL 影响。

## TODOS（仅未完成项）

### 需开发
- [ ] **D4**（B9 = B）管理端用户管理 API：角色变更 / 停用启用，内部**必须** `tokenVersion + 1` + 走 `@Audit` 留痕 + 护栏（**禁止自我提权**、**禁止降级或停用最后一个管理员**）
- [ ] **A9** 客户端两条自动路径（跨仓库 `ai-tools`）：① 同机购买后轮询 `/api/checkout/{checkoutId}/status` 自动存证（**上限 1 小时**，超时停止轮询——定案 B5）；② 登录后自动领取并激活
- [ ] **A10**（源 plan-4.1 / 结转自 plan-3.1）Paddle 续费幂等加固：`SubscriptionService.bindOrRenewLicense`（`:120-146`）对每个 paymentSuccess 事件都续期，`PaddleStrategy`（`:306-319`）把 `subscription.*` + `transaction.completed/billed` 全映射 `SUCCESS` → 可能重复延长 License。**先确认 Paddle 真实事件流**，再用 `payload.getCurrentPeriodEnd()` 与 `license.getExpiresAt()` 比对做幂等

## 登记表（外部阻塞 / 需你本人动手，不占 TODOS）

- **升级前配置 `ACCOUNT_MFA_KEY`（本轮新增，硬性）**：fail-fast 设计，未配则新版本**无法启动**。升级顺序＝先配环境变量，再发布镜像/重启。
- **MFA 实机走查（新增）**：管理台「绑定（手抄密钥录入认证器）→ 退出 → 动态码登录 → 换用邮箱码登录 → 解绑」全闭环；并**专项验证**直接用 `mfaTicket` 调 `/api/admin/**` 被拒（防 MFA 绕过）、同码重放被拒。
- **H7 · Paddle 沙箱实测**：`Transaction` 金额单位是否需 ×100（Paddle v2 以最小货币单位传值）；与 A10 同批做，可一并确认事件流。
- **渠道部分退款能力沙箱实测**：Alipay / Wechat / Stripe / Paddle / PayPal 逐家验证「按指定金额（非全额）退款」是否受理；Paddle 走 Classic v2 `/transactions/{id}/refund`（非 Billing 的 `/adjustments`）。
- **N6 报错文案实机复测**：后端已在线（8000），浏览器真实报错文案需 GUI 走一遍。
- **B2/B3 回调路径实机验证**：发货 / 渠道退款吊销需真实支付宝异步通知或构造签名回调触发，建议管理端造单或沙箱回调复核。
- **实机走查**：浏览器全流程（登录 → 订单 → 申请退款 → License 状态刷新）。
- **激活/解绑实机走查**：客户端或 curl 走「密钥激活 → 同机重试幂等 → 换机 `MACHINE_MISMATCH` → 账号页解绑 → 再激活成功」闭环；并确认账户页解绑按钮仅在已绑定时出现。
