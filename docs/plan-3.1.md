# plan-3.1 · 第二轮业务完整性审计发现（唯一活动 plan）

> 版本：v3.3（2026-09-20）· 唯一活动 plan（billing-license-service + ai-tools）。
> 前情（已交付，留一行证据）：B1-B9 已落地、`mvn -o test` 212/0/0；Flyway V1..V12 已合并为单一 `V1__baseline_schema.sql` 并实机迁移验证；端到端冒烟已跑通（隧道→远程 PG 18.3→8000，B4 去重复测通过）。本版并入第二轮全面审计（支付/账户/前端三域）新发现，逐条附证据。
> 审计口径：三域并行只读走查（支付回调域子代理 + 账户安全域与 ai-tools 前端域实机读码）；只列**已确认**问题，不重复 B1-B9。

## TODOS（仅未完成，按严重度）

- [ ] **C1 HIGH（支付/订阅正确性）Paddle 订阅事件字段错配致 NPE，首激活绑不上 License** — `PaddleStrategy.java:293-294`：判空用 `current_billing_period`（Paddle v2 真实字段），取值却 `data.get("current_period")`（不存在）返回 null，紧接 `period.has("starts_at")` 抛 NPE。NPE 被外层 catch（:329）吞掉 → status 置 FAILED，但 :288 已 set subscriptionId。进入 `SubscriptionService.processSubscriptionEvent` 后：非 SUCCESS 不置 ACTIVE、不 bindOrRenewLicense → Paddle 订阅首激活恒不绑 License（licenseId 恒 null、status 恒 PENDING）；后续续期因 licenseId==null 被当「首次绑定」不延期（`SubscriptionService.java:129`），周期时长丢失。**修复**：`data.get("current_billing_period")`，字段名与判空一致。

- [ ] **C2 HIGH（幂等并发/重复发货）Webhook 发货路径与轮询补偿路径未共享锁，issueLicense 无按单幂等** — `WebhookController.fulfillOrder`（:316-368）全程无锁；而 `CheckoutService`（getStatus :291、compensateFromChannel :368）用 `orderLock(orderNumber)` 串行化。`LicenseService.issueLicense`（:44-89）直接 build+save，无 `findByOrderId` 存在性检查（对比 CheckoutService.getStatus :294 先查后发）；`Order` 实体无 `@Version`。跨事务「检查 canFulfill—markPaid」非原子且无行锁/唯一约束兜底 → Webhook 线程与客户端轮询补偿线程并发各读到 PENDING 时，同一订单可签发两张 License。**修复**：Webhook fulfillOrder 复用同一 orderLock 或对 Order 加悲观/乐观锁，并给 issueLicense 加按订单存在性检查。

- [ ] **C3 HIGH/CRITICAL（前端授权绕过）ai-tools 特性门禁开关来自明文可改配置、无完整性校验** — `feature-gate.ts:49-50`：`cfg.enabled=false` 或 `cfg.killSwitch=true` 时对所有权益键返回 `{allowed:true}`（fail-open 全放行）。`config.ts:43` 以 `JSON.parse(fs.readFileSync(...))` 读取 `resources/license/license.config.json`（`constants.ts:28/31` EXTERNAL_LICENSE_DIR=license、CONFIG_FILE_NAME=license.config.json），`bool()` 直接取 `enabled`/`killSwitch`，**无签名/HMAC/anchor 完整性校验**。用户改一个明文文件（enabled:false）即绕过全部 R1 门禁。**待确认设计意图**：killSwitch 显为「应急停用」有意为之；但 enabled/killSwitch 对终端用户可达即等于授权绕过。**修复方向**：enabled/killSwitch 纳入 vault/anchor 签名保护，或与激活令牌绑定，明文配置仅允许收紧不允许放开。

- [ ] **C4 MEDIUM（枚举/写放大）公开 verify 端点无限流且每次失败写库** — `LicenseController.java:46` `/api/licenses/verify/{licenseKey}` permitAll（SecurityConfig:85-86）且无 RateLimit 依赖；`LicenseService.verifyLicense` 每次校验失败均 `recordLicenseEvent(...VERIFY_FAILED...)` 落库（inactive/expired/badsig 三处）。攻击者可高频遍历 licenseKey → license_event 表无界增长 + 探测某 key 是否存在（错误码区分 NOT_FOUND/INVALID/EXPIRED）。**修复**：verify 端点接入 RateLimitService（按 IP/机器码），失败事件采样或按窗口去重落库。

- [ ] **C5 MEDIUM（资损/欺诈）订阅首充绕过金额校验** — `WebhookController.processWebhook` 订阅分支（:213-225）在金额校验（:229-235）之前 `return`，订阅首充直接 `self.fulfillOrder(orderId)`（:219）不做 `amountValidator.validateAmount`，与一次性支付口径不一致（验签兜底在，但金额防篡改缺失）。**修复**：订阅首充在 fulfillOrder 前对携带金额的事件补金额校验，无金额字段的续期事件豁免。

- [ ] **C6 MEDIUM（幂等）reserveEvent 用 save 而非 saveAndFlush，唯一约束冲突延到 commit** — `PaymentEvent.java:26-28` 主键 `@GeneratedValue(UUID)` 由 Hibernate 内存生成，`save()`（`WebhookController.java:301`）不触发 INSERT；:303 try-catch 想捕 `DataIntegrityViolationException` 优雅去重，但冲突要到 commit（在 processWebhook 事务拦截器、catch 之外）才暴露 → 并发重复投递时渠道收 500+整事务回滚而非设计中的 200「Already processed」（重复发货最终仍被约束+回滚阻止，不会双发，但语义与注释不符、渠道会重试）。**修复**：reserveEvent 内改 `saveAndFlush` 让冲突在 try 内同步抛出。

- [ ] **N6 报错文案实机复测**（结转）— 后端已在线（8000）；浏览器复测真实报错文案需人工 GUI，待川哥过一遍。

- [ ] **B2/B3 回调路径实机验证**（结转）— 发货/渠道退款吊销需真实支付宝异步通知或构造签名回调触发，冒烟未覆盖；建议用管理端造单或沙箱回调复核。

## 待核实（未下结论，需确认设计意图）
- **微信/支付宝渠道退款未映射 REFUNDED/CANCELLED**：`WechatPayStrategy.parseWebhookPayload`（:456-462）只认 trade_state SUCCESS/NOTPAY，退款通知 `REFUND.SUCCESS` 无 trade_state → 落 FAILED，不触发 `revokeOnChannelRefund`；`AlipayStrategy` 仅 `TRADE_CLOSED→CANCELLED`。即 B3「渠道退款吊销」实际只对 PayPal（PAYMENT.CAPTURE.REFUNDED）/Paddle 生效。疑为「国内渠道退款由管理端 `AdminService.refundOrder` 发起并本地吊销」的取舍（refundPayment 也未设退款专用 notify_url，渠道退款通知本就不到本端点）——需确认是否有意为之。
- **Paddle 续期依赖事件到达顺序**：修复 C1 后需复核 `subscription.activated` 与 `transaction.billed` 乱序时 `bindOrRenewLicense`（`SubscriptionService.java:129-138`）licenseId==null 分支是否仍吞一个周期续期。

## 已核实无问题（本轮抽查，留档不占 TODOS）
- 支付：B2 回归正确（updatePaymentStatus 唯一实调用点 4 参、locatePayment 三路回退无遗漏）；五渠道验签 fail-closed 闭合（密钥缺失/空签/异常均 false，失败返 401 且不写 payment_events）；金额最小单位归一与币种+0.01 容差校验；退款吊销口径 Webhook 与 AdminService 一致（findByOrder→逐张 REVOKED→markRefunded，已 REFUNDED 幂等跳过）；@Transactional 经 @Lazy self 代理生效。
- 账户/安全：admin 端点统一 `hasAuthority("ROLE_ADMIN")`（SecurityConfig:118）+ ApiKeyFilter 常量时间比较（MessageDigest.isEqual，防时序侧信道）；account 敏感端点 ROLE_USER + JWT principal；verify 公开端点不回显客户邮箱（E3）；A1 服务端校验点重验签名（防库内 token 篡改）；K7 lastVerifiedAt 仅成功后写。
- 前端契约：兑换请求字段 `{code, customerEmail, machineId}` 与后端 RedeemCodeRequest 一一对齐；serverTime(Long) 契约吻合；verifier 全异常路径 fail-closed（malformed/kid 缺失/验签失败/exp/nbf 均 fail(...) 拒绝）。
