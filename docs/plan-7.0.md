# plan-7.0 · 客户端自动路径与支付幂等加固（剩余开发项）

> 本 plan 由 `plan-4.1`（用户端退款）、`plan-5.0`（凭证体系治理）、`plan-6.0`（统一登录）三份并行 plan 于 2026-09-23 合并而成，原三份文件已删除。未完成项均标注来源。

## 背景与目标


其契约与实现现状以 `README.md`「凭证激活」段、`接口调用时序图.md` §1.7.6 / §3.13–3.14 / §4.10 为准，本 plan **只承载未完成项与待决策**。





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

## 取舍与风险

- **`ACCOUNT_MFA_KEY` 是本轮唯一的破坏性变更**：fail-fast 意味着**现有部署升级前必须先配好该变量**，否则服务起不来。详见 `上线准备工作.md` §3.1（本 plan 不再重复罗列）。
- **迁移占位**：MFA 新增列由 `V6__users_mfa.sql` 落库（`users` 四列）；D3 的转正标记由 `V7__machine_converted_at.sql` 落库（`machine_first_seen.converted_at`，2026-09-23）。**下一个可用版本为 V8**。`mvn test` 不跑 Flyway，故单测不依赖新增列存在（与既有约定一致）。
- **密钥轮换/丢失后已绑定的 TOTP 密钥不可解密**：即便 fail-fast 也一样（起不来）。缓解 = `reset-admin-mfa.sql` 重新绑定；且**邮箱兜底不依赖该密钥**，故不会把管理员硬锁在管理台外。
- **邮箱兜底的因子独立性弱于 TOTP**：邮箱与登录标识同源。故保留 `account.mfa.email-fallback-enabled` 开关，且文档**必须标明档次差异**，不得宣称「邮箱码 = 同等强度第二因子」。
- **票据在 TTL 内可重放，但不足以冒用**：票据不含动态码，且 TOTP 侧有 `mfa_last_used_step` 防同码重放、`verifyFailMax` 限制猜测次数（6 位码 + 5 次上限 + 300s TTL）。若日后多实例部署，需把失败计数迁到共享存储（与既有限流同源问题）。
- **「抢绑」已被落地实现堵住，红线须保留**：归属判定取登录用户 id，明文 key **单独泄漏不足以完成绑定**（需同时持有受害人登录态）。**若日后有人把该分支放宽为匿名，「抢绑」立即回归，B3 也随之重新升级为安全红线**。
- **渠道部分退款未收口风险（须实测）**：`PaymentStrategy.refundPayment` 现为 `boolean`，无法区分「渠道明确拒绝部分金额」与「调用超时/网络失败」。若部分退款实际已受理却返回 false，降级会**重复退款**。缓解：失败与降级均写审计 + metadata 便于对账；列入登记表的渠道沙箱实测。
- **跨仓库契约风险已收窄（C2 已查实）**：`ai-tools` 客户端**只支持兑换码**、**无登录代码**，故 A9 的改造量集中在「先做账号登录」+「改指向新端点」两件事；且其对外文案已统一为 `license.errors.generic`（不读服务端错误码），故本仓改码**无需**客户端 i18n 联动。**注意其 plan 已由用户合并为 `plan-4.1.md`（原 `doc/plan-3.1.md` 已并入）**。
- **回滚**：MFA 属增量列 + 增量端点，回滚 = 移除端点与列；`verification_codes` 新枚举值无 DDL 影响。

## TODOS（仅未完成项）

> **状态锚点（2026-09-24 复核）**：本仓 D 组（D2 自动上报 / D3 机器转正 / **D4 管理端用户管理 API** / D5 删旧端点 / D6 作废统一）均已收口；其中 **D4 已于 2026-09-23 落地**（`AdminUserService` + `AdminController` 的 `PATCH /api/admin/users/{userId}/role|status` + `AdminUserView` + `AdminUserServiceTest` 8 例全覆盖 `tokenVersion+1`、禁自我操作、禁动末位管理员）。本 plan 剩余开发项仅 **A9**（跨仓 `ai-tools`，本仓无服务端改动）；**A10 已于 2026-09-24 收口**（移除冗余去重层 + 续期改 max 幂等 + `transaction.billed` 解析周期），详见 TODOS 内收口说明。

### 需开发
- [ ] **A9** 客户端两条自动路径（跨仓库 `ai-tools`，**本仓无服务端改动**）：
  - **现状**：① 原「客户端轮询 checkoutId」方案已废弃（客户端全仓 0 checkoutId，订单号在外部浏览器，无从轮询）；② 改为**登录后自动到账**，纯客户端行为，**不新增任何服务端端点**。
  - **依赖的本仓端点（契约已就位，本仓侧动作为「守门」——不得对这些端点做破坏性改动）**：
    1. `GET /api/account/licenses`（`AccountAssetController:52`，Bearer，401 缺令牌）→ 返回 `List<LicenseResponse>`，含 `licenseKey` / `status` / `machineCode` / 有效期，按签发时间倒序。
    2. `POST /api/licenses/activate`（`LicenseController:91`，`credential=licenseKey` 走密钥分支，要求登录且归属本人）。
  - **客户端逻辑（落在 `ai-tools/plan-4.1.md`「授权客户端跨仓对齐」段，本仓只挂账）**：登录 → 拉取 License 列表 → 优先级 `status==ACTIVE && machineCode==null` > `machineCode==本机` > 都没有则不动不弹窗 → 对选中项 `POST /api/licenses/activate`。
  - **前置**：ai-tools 客户端需先具备账号登录能力（其 plan-4.1.md 已登记）。
  - **执行准备完成判据**：跨仓 plan 两条路径均已登记且本仓两端点契约零改动、回归（343 基线）通过。

  - **A10 已收口（2026-09-24，源 plan-4.1 / 结转自 plan-3.1）**：Paddle 续费重复延长风险根治完成。
    - **根因**：续期原累加式延长（`expiresAt.plusDays(days)`）；Paddle 以两个不同 eventId 投递续费（`subscription.updated` 带周期 + `transaction.billed` 不带周期）时，WebhookController(B18) 按 eventId 去重不拦、两次都进 Service，后者回退累加 → License 被重复延长。
    - **改造（均已落地）**：① **移除冗余层**：删除 `WebhookEventDeduplicator`（Service 内存去重）——生产由 `WebhookController(B18)` 的 `payment_events` 唯一约束 + `existsByProviderAndEventId` 统一做事件级去重，Service 层去重永不触发，且内存版重启/多实例失效具误导性；② **续期改 `max(现有 expiresAt, currentPeriodEnd)` 而非累加**：`SubscriptionService.bindOrRenewLicense` 以 Paddle 权威周期结束时间为幂等基准，与投递顺序/是否重复投递（含双 eventId）无关，彻底收敛到同一目标值；③ **`transaction.billed/completed` 也解析周期**：`PaddleStrategy.parseWebhookPayload` 抽出 `parseBillingPeriod` 公用，`subscription.*` 与 `transaction.billed/completed` 均解析 `current_billing_period` 写入 `currentPeriodEnd`，杜绝双 eventId 不带周期的累加回退。
    - **测试**：`renewal_duplicateSuccessEvents_shouldBeIdempotent`（同周期重复 SUCCESS）、`renewal_subscriptionUpdatedThenTransactionBilled_shouldBeIdempotent`（双 eventId 真实链路）、`PaddleStrategyTest#parseWebhookPayload_transactionBilled_withBillingPeriod_shouldSetCurrentPeriodEnd`（解析）；随冗余层移除同步删 `duplicateWebhookEvent_shouldBeSkipped`。
    - **闸门**：改造后 `mvn test` 通过（343 基线 + 加固用例）；本机无 docker/mvn，仅配置级核对 + IDEA 构建。

## 登记表（外部阻塞 / 需你本人动手，不占 TODOS）

- **启动配置（如 `ACCOUNT_MFA_KEY`）**：已并入 `上线准备工作.md` §3.1（缺失即拒启，升级前必配；升级顺序＝先配环境变量、再发布镜像/重启）——本 plan 不再重复罗列。
- **MFA 实机走查（新增）**：管理台「绑定（手抄密钥录入认证器）→ 退出 → 动态码登录 → 换用邮箱码登录 → 解绑」全闭环；并**专项验证**直接用 `mfaTicket` 调 `/api/admin/**` 被拒（防 MFA 绕过）、同码重放被拒。
- **H7 · Paddle 沙箱实测**：① `Transaction` 金额单位是否需 ×100（Paddle v2 最小货币单位）；② **A10 依赖项**——用沙箱模拟 `subscription renewed`，抓取真实 `transaction.billed/completed` 原始 payload，确认其是否含周期字段、以及 `subscription.updated` 是否稳定先于交易事件落库（决定 A10 第一阶段幂等是否在生产成立）；与 A10 同批做。
- **渠道部分退款能力沙箱实测**：Alipay / Wechat / Stripe / Paddle / PayPal 逐家验证「按指定金额（非全额）退款」是否受理；Paddle 走 Classic v2 `/transactions/{id}/refund`（非 Billing 的 `/adjustments`）。
- **N6 报错文案实机复测**：后端已在线（8000），浏览器真实报错文案需 GUI 走一遍。
- **B2/B3 回调路径实机验证**：发货 / 渠道退款吊销需真实支付宝异步通知或构造签名回调触发，建议管理端造单或沙箱回调复核。
- **实机走查**：浏览器全流程（登录 → 订单 → 申请退款 → License 状态刷新）。
- **激活/解绑实机走查**：客户端或 curl 走「密钥激活 → 同机重试幂等 → 换机 `MACHINE_MISMATCH` → 账号页解绑 → 再激活成功」闭环；并确认账户页解绑按钮仅在已绑定时出现。
