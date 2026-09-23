# plan-4.0 · 用户端退款申请（我的授权页）

## 背景与目标

需求：已购买用户登录后查看已购许可证，并支持申请退款。

**检查结论（2026-09-22）**：前半需求已完整存在——账号体系（注册/登录/找回/JWT）+ `/account/` 前端页 + 三个只读资产端点（`GET /api/account/licenses|subscriptions|orders`，`AccountAssetController`）均已上线。**缺口仅在「用户端申请退款」**：退款能力目前只存在于管理端 `POST /api/admin/orders/{orderNumber}/refund`（`AdminService.refundOrder`，链路成熟：渠道退款调用 + C2 存量渠道兜底 + H5 交易号修正 + REFUND_FAILED 独立事务落库 + 成功后吊销 License + 状态机流转 + 退款流水 + 邮件通知）。

本 plan 结转自 plan-3.1（Paddle 续费条目与登记表，见文末）。

## 范围与边界

- **做**：用户端退款端点（归属校验 + 资格校验 + 复用现有退款链路）、`/account/` 页订单区退款入口、审计与限流、测试。
- **做（2026-09-22 新增拍板）**：**按使用时间折算退款金额**。这意味着必须支持**部分退款**，
  原「暂不做部分退款」的边界据此作废。
- **不做**：退款申请单实体 / 审核工作流（已拍板走**直接退款**，不设单据与审批环节）；退款政策页面文案（官网侧）。

## 实现思路

1. **后端端点**：`AccountAssetController` 加 `POST /api/account/orders/{orderNumber}/refund`（ROLE_USER 保护区，SecurityConfig 零新增规则）。请求体：`{reason}`。
2. **归属校验（安全关键）**：`order.getCustomerId()` 必须等于当前 JWT 用户 ID；不匹配按 `ORDER_NOT_FOUND` 返回（不泄露他人订单存在性）。Order 已有 customerId（`findByCustomerIdOrderByCreatedAtDesc` 在用）。
3. **资格校验**：订单 `paymentStatus == PAID` 且未退款（`ALREADY_REFUNDED` 已有）；退款时限（默认：支付完成后 7 天内，超期 `REFUND_WINDOW_EXPIRED`，时限做成配置项）。
4. **复用退款链路**：`AdminService.refundOrder` 是 public，最小改动由 `AccountAssetService` 直接注入调用；核心链路不动（含渠道兜底/失败态/吊销/流水/邮件全部继承）。`@Audit(action = "USER_REFUND_REQUEST")` 区分用户发起与管理员发起。
   **注意金额**：现有实现内部固定以 `order.getTotalAmount()` **全额**退款（`AdminService`:191-192）。
   折算后要按金额退，必须给该方法加金额入参或新增重载，**且不能改动管理员「全额退」的既有行为**
   ——管理员路径仍按全额，仅用户自助路径按折算额。订单/支付态可用现成的 `PARTIALLY_REFUNDED` 表达。
5. **限流**：`RateLimitService` 加用户维度 key（如 `refund:userId`，默认 5 次/小时），防刷。
6. **前端**：`/account/` 订单区——`paymentStatus=PAID` 且在退款窗口内的订单显示「申请退款」按钮；点击弹原因输入 + 确认（明示「退款将吊销该订单全部 License」）；成功/失败（含 REFUND_FAILED 引导文案）就地反馈，刷新订单与 License 列表。
7. **测试**：越权（退他人订单 → 404 语义）、窗口外拒绝、成功链路（License 吊销 + 流水 + 状态）、重复退款幂等拒绝。

## 已拍板（2026-09-22）

- **直接退款（非审核制）**：用户端点直接复用 `AdminService.refundOrder`，**不新增退款申请单实体与审核工作流**
  （此前曾按两段式审批制实现过一套 `refund_requests` 表与审批端点，已按本次拍板全部清除）。
- **按使用时间折算金额**：退的是**折算后的金额**，不再是全额。因此「部分退款」由不做变为必做。

## 待确认项（折算口径，动手前必须拍板）

- **折算公式**：推荐「可退额 = 实付额 × 剩余天数 ÷ 总天数（线性）」，是否接受？
- **买断 / 终身 License 怎么算**：终身件没有到期日，"总天数"取什么？建议定义一个政策窗口（如付款后 30 天内可退、按窗口折算）。
- **是否设止损下限**：剩余比例过低是否直接不退（如已用超 50% 不退），避免小数额退款叠加手续费反而倒亏。
- **渠道的部分退款能力**：现有 `AdminService` 以 `order.getTotalAmount()` 全额退（:191-192），
  改传折算额后需**逐家验证** ALIPAY / WECHAT_PAY / STRIPE / PADDLE / PAYPAL 是否支持按指定金额退款；
  不支持的渠道要有明确降级策略（拒绝或转 FULL）。
- **退款后 License 是否吊销**：建议「只要退款就吊销」，避免钱退了还能用。
- **订阅订单**：在期订阅如何折算；退款后是否需要向渠道侧同步取消订阅。

## TODOS（仅未完成）

- [ ] **后端：用户端退款端点**（归属/资格校验 + 复用 refundOrder + 审计 + 限流 + 测试）——待上述待确认项拍板后动手。
- [ ] **前端：/account/ 订单区退款入口**（按钮显隐逻辑 + 原因弹窗 + 结果反馈）。
- [ ] **实机走查**：浏览器全流程（登录 → 订单 → 退款 → License 吊销状态刷新）。
- [ ] **（结转自 plan-3.1）Paddle 续费可能重复延长（多送 License 时长）** — `SubscriptionService.bindOrRenewLicense`（:120-151）对每个 paymentSuccess 事件都执行 `expiresAt + 1 周期`；PaddleStrategy 把 billed/completed/subscription.* 全映射 SUCCESS（:306-323）。待确认 Paddle 真实事件流；加固方向：用 `payload.getCurrentPeriodEnd()` 与 `license.getExpiresAt()` 比对做幂等。

## 登记表（外部阻塞 / 需你本人动手，不占 TODOS）

- **N6 报错文案实机复测**：后端已在线（8000），浏览器真实报错文案需你走一遍 GUI。
- **B2/B3 回调路径实机验证**：发货 / 渠道退款吊销需真实支付宝异步通知或构造签名回调触发，建议管理端造单或沙箱回调复核。
