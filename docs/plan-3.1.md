# plan-3.1 · 上线验证 + 授权硬化 + 业务完整性审查（合并活动 plan）

> 版本：v3.1（2026-09-20 夜间）· 唯一活动 plan（billing-license-service）。前序 plan-3.0 的授权硬化已全部收口；本版并入「业务完整性审查」发现，并据实校正前序 TODOS（N2-N6/C8/T-RTL/C9 代码已落地并已验证，K5/C5/C7 按川哥「同口径收口，三条清掉」移除）。
> **本 plan 仅列项、不动代码**——用户明确指示「只生成 plan 暂不修改」。所有修复待川哥拍板后另开执行 plan。

## 背景与目标
- 前序计划（plan-3.0 v3.13）完成：错误提示回传修复（N1-N5）、收银台产品参数（C9）、服务端试用防重置账本（C8，V12 + MachineRegistryService + MachineController）、U1-U3 用户密钥自助、S1 激活联调、K8/K16 内嵌收银台 + 公开产品目录。
- 2026-09-20 夜间：川哥要求「全面检查 ai-tools 和 billing-license-service 业务完整性，对问题/业务漏洞生成 plan」。
- 本版据此对 license/account/security/payment 域做只读审查，输出**已确认**的漏洞清单（附 file:line 证据，CRITICAL 均经二次复核）。

## 范围与边界
- **做**：列出已确认的 CRITICAL/HIGH/MEDIUM/LOW 业务漏洞，含证据与修复方向。
- **暂不做**：任何代码修改（用户「只生成 plan 暂不修改」）。修复纪律：未定位问题点严禁擅自改代码，本 plan 所有项均为「待确认/待开发」。
- **硬约束**：Flyway 纪律不变——迁移文件受 checksum 保护，需变更请新增版本（当前 V12）。

## 实现思路
- 审查方法：三路并行只读走查（license/account/security、payment/checkout/webhook、ai-tools 客户端），所有 CRITICAL 结论均经二次 file:line 复核（见 B1/B2/B3 证据链）。
- 前序已收口内容（设计依据，留存）：N1 根因（GlobalExceptionHandler 自拼 Map 被 ApiResponseAdvice 再包成功壳）、N2-N5（ApiResponse 信封 / JSON 401-403 / 前端兜底 / EMAIL_NOT_PURCHASED）、C8（machine_first_seen 不可变账本）、C9（checkout.js:1758-1762 读 productId 预选档位）、U1-U3/S1/K8/K16。
- **已核实无问题的域**：License 吊销/过期三重复检（verifyLicense 每次在线重验状态/过期/签名）；machine_first_seen 并发兜底（DataIntegrityViolationException）；错误契约（GlobalExceptionHandler 脱敏为 INTERNAL_ERROR、无原始 Map/堆栈外泄）；JWT 无状态 + tokenVersion 失效；Webhook 验签五渠道 fail-closed；重复发货幂等（PaymentEvent 唯一约束 + Order.canFulfill）。

## TODOS（仅未完成，按严重度）

- [ ] **B1 CRITICAL 独立注册端点违反「禁止独立注册」铁律** — `SecurityConfig.java:104` 将 `/api/account/register` 列入 permitAll；`AccountController.java:68` `@PostMapping("/register")`；`AccountService.java:47-89` 直接 `userRepository.save(user)` 建独立账户。与 N5 已拍板口径（账号仅购买/兑换时由系统建，新邮箱不允许单独建号）直接冲突，任何人可凭邮箱+验证码+密码自建账户。**修复**：删除 `AccountController.register` 端点、`SecurityConfig` 对应 permitAll 行、`AccountService.register` 方法；账号创建收敛到 `RedeemCodeService`/`CheckoutService` 既有入口。需川哥确认是否彻底移除或改为仅内部调用。

- [ ] **B2 CRITICAL PayPal/Stripe 成功回调永不发货（资损）** — `WebhookController.java:234` 调 `paymentService.updatePaymentStatus(webhookData.getPaymentId(), …)`；`PaymentService.java:105` 查无则抛 `RuntimeException`（未捕获）；`WebhookController.java:239` `if (payment == null)` 为死代码（`updatePaymentStatus` 抛而非返 null）。PayPal 回调 `webhookData.getPaymentId()` = **capture id**（`PayPalStrategy.java:227`），而落库 `Payment.paymentId` = **PayPal Order ID**（`PayPalStrategy.java:68`/`109`）；Stripe 同理 payment_intent id（pi_…）错配落库 cs_…。异常在 `fulfillOrder`（:252）之前抛出 → License 永不签发，已扣款订单卡 UNPAID，渠道反复重试同一 500。**修复**：`updatePaymentStatus` 改以 `orderId` + `transactionId` 定位 Payment，或在 `createPayment` 时同步记录 capture/payment_intent id；查无时降级为日志而非抛异常阻断发货。

- [ ] **B3 CRITICAL 渠道侧退款不吊销 License / 不取消订阅（资损/欺诈）** — `WebhookController.java:253-256` 非 SUCCESS 分支仅 `reserveEvent(...)` 写审计，未调 `markRefunded`/`revokeLicense`/取消订阅；PayPal `PAYMENT.CAPTURE.REFUNDED`、Paddle `transaction.refunded`、Alipay 退款均无量身处理。管理端人工退款（`AdminService.java:216` `markRefunded` + 吊销）正确，但**渠道回调触发的退款无此链路** → 客户退款后 License 仍可用。**修复**：`processWebhook` 增 `REFUNDED`/`CANCELLED` 分支，复用 `SubscriptionService.expireLicense` + License 吊销逻辑（与管理端同口径）。

- [ ] **B4 HIGH select-provider 重复选择可双重扣款** — `CheckoutService.java:164-197` 每次 `selectProvider` 都重新 `createPayment`，无会话/订单状态前置校验；`Payment` 仅 `paymentId` 唯一，缺 `(order, channel)` 去重约束。用户连点可能生成第二个真实收银台。**修复**：会话已 PENDING/已存在 Payment 则复用或返 409。

- [ ] **B5 HIGH 发货事务内含邮件发送 → 邮件失败回滚整单发货** — `fulfillOrder`（`WebhookController.java:300` `@Transactional`）内调 `emailNotificationService.sendPaymentSuccessEmail`；邮件异常使已签发 License 回滚，邮件服务不可用时订单永久卡死（幂等行也回滚，反复 500）。**修复**：邮件改 `@TransactionalEventListener(AFTER_COMMIT)` 或异步，移出发货关键路径。

- [ ] **B6 MEDIUM resetPassword 账户枚举神谕** — `AccountService.resetPassword` 查无账户抛 `EMAIL_NOT_PURCHASED`，暴露「该邮箱是否为付费客户」。**修复**：账户不存在返回与成功一致的统一响应（如 `RESET_EMAIL_SENT`）。

- [ ] **B7 MEDIUM updatePaymentStatus 对未知 paymentId 抛未捕获异常（与 B2 关联）** — `PaymentService.java:105` 抛 `RuntimeException`；`WebhookController.java:239` 死代码。**修复**：定位失败降级日志，不抛异常阻断 Webhook 事务（与 B2 同改）。

- [ ] **B8 LOW/MEDIUM `/checkout/**` 与 `/account/**` 目录级 permitAll 误暴露面** — `SecurityConfig.java:94`/`98` 以目录粒度放行（本意仅静态页）；若将来控制器误映射到这两前缀将被无鉴权放行。**修复**：收紧为精确静态文件匹配或专用 `/static/**` 前缀。

- [ ] **B9 LOW 兑换网络重试丢失已签发 License** — `RedeemCodeService` 事务内 `save` 后置 `USED` 并提交；HTTP 响应在返回前丢失时，客户端重试触发 `CODE_ALREADY_USED` 拿不到已签发 License。**修复**：按 `code` 幂等，已签发直接返回既有 License。

## 跨仓契约（已核实，不占 TODOS）
- **serverTime 契约吻合（原 R3 怀疑不成立）**：后端 `RedeemResponse.java:53`/`61` 已下发 `serverTime`（= System.currentTimeMillis()），客户端 `redeem.ts:135` 消费并填充 `server_time_floor`——单调时钟确有服务端锚定。

## 结转（前序遗留，待用户侧）
- **N6 验证收尾**：后端实例重启使 N2/N3 生效 + 浏览器复测真实报错文案。后端重启可经 IDEA MCP 执行；浏览器复测需人工 GUI，待川哥醒后过一遍。
