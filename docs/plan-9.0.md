# plan-9.0 · 支付渠道能力补齐与 Web 运营开关（已合并 plan-8.1）

> 只登记未完成项；完成即删条目、不留痕迹；全绿即删文件。
> 本轮已落地内容不在此重复，见提交记录与 `docs/README.md`。
> plan-8.1 的 Creem 跨期验证项已并入本文件，原文件删除，避免多 plan 并行。

## TODOS

### ②【需你处理 / 外部资源】

- [ ] **支付宝生产配置**：`ALIPAY_SELLER_ID`、生产应用/证书、对应支付产品开通（当面付、电脑网站、手机网站、花呗分期）。
- [ ] **管理端退款审核 UI 浏览器验收**：在管理台真实点击 Creem 全额审核→自动退、部分审核→登记转账→结算，核对列表刷新、错误提示与权限。
- [ ] **手机网站支付真机验收**：移动浏览器自动选 WAP；确认回跳 `/payment/success`、支付结果页、PC 扫码/网页支付无回归。
- [ ] **支付宝周期扣款**：开通周期扣款产品；接入 `alipay.user.agreement.page.sign` 签约 → 保存 `agreement_no` → 定时 `alipay.trade.pay` 扣款 → 失败重试/宽限 → `alipay.user.agreement.unsign` 解约；需要用户签约页与订阅管理页。验收：真实签约、两期扣款、解约后不再扣款。
- [ ] **支付宝自动对账**：开通商家对账单权限；定时调用商家对账单下载地址查询接口，下载 zip/csv → 解析 → 写入 `channel_statement_lines` → 自动匹配 ledger/差异告警。验收：某日账单自动入库且差异列表可复核。
- [ ] **支付宝单笔转账**：开通商家转账产品；接入 `alipay.fund.trans.uni.transfer` + `alipay.fund.trans.common.query`，采集收款支付宝账号/姓名，幂等键、异步结果、失败退款登记。用于 CNY 部分退款/补偿；Creem 国际客户无支付宝账号时仍走人工转账。
- [ ] **支付宝预授权**：开通预授权产品；接入 `alipay.fund.auth.order.voucher.create` / `operation.pay` / `operation.unfreeze` / `voucher.query`，用于押金/冻结类业务；当前 License 一次性/订阅场景不强制需要。
- [ ] **支付宝发票**：确认企业开票资质与税控服务商；接入 `alipay.ebpp.invoice.*` 或线下开票流程；订单/用户信息需补开票抬头、税号。验收：真实开票并回传发票状态。
- [ ] **Creem 订阅升级/降级**：确认 Creem `upgrade subscription` 接口与 proration 规则；接管理端/账户页；订阅变更后的 License 周期、补差、退款边界需回归。验收：真实升级/降级各一次。
- [ ] **Creem Customer Portal**：接 `POST /v1/customers/billing` 生成 magic link；账户页提供“管理订阅/支付方式/下载收据”入口。验收：真实客户可打开 Portal 并修改支付方式。
- [ ] **Creem 税务/发票/收据**：Creem 作为 MoR 代收代缴；从 Customer Portal 或交易详情归档收据/发票链接到订单；明确本地账务与含税总额口径（当前 ledger 按小计）。验收：订单可下载/跳转收据。
- [ ] **Creem Customer Credits**：开通 Customer Credits；接账户创建、credit/debit/reverse、balance、entries API；用于部分退款补偿或余额抵扣。验收：真实 credit 后余额可查、退款/抵扣账本一致。
- [ ] **Creem 多币种/Dispute**：如需 EUR/GBP 或争议处理，扩 Currency、价目与 `chargeback/dispute` 事件；当前仅 USD 映射。
- [ ] **Creem 真实跨期续费/到期（原 plan-8.1 #8）**：事件级已覆盖；仍缺真实渠道第二期 `subscription.paid` / `subscription.renewed` 与 `expired` 到期收口。验收：等沙箱下个账期或 Creem 后台手动触发续费，用隧道/`creem listen` 接真实事件，核对续期、取消可逆、到期失效。
- [ ] **payment_provider 真实联调复核（原 plan-8.1 #11）**：已加 `@DynamicUpdate` 防旧快照覆盖；下次真实联调现场执行 `select order_number, payment_provider from orders order by created_at desc limit 5`，若再出现 null 才继续定位并发写路径。
- [ ] **V26/V27 真库验证**：在目标 PostgreSQL 备份后执行 V1→V27，确认 `flyway_schema_history` 全 success、`ddl-auto: validate` 通过；重点核对 V27 开关表、V26 审核/转账字段。
- [ ] **发布基线**：提交当前未跟踪文件（V16–V27、RefundService、PaymentSettingsService、AdminPaymentSettingsController 等），从干净 clone 跑 CI 与镜像构建。
- [ ] **多实例限流**：Webhook/接口限流改 Redis（当前内存滑动窗口，多副本会各自计数）；需先提供 Redis 实例。

### ③【暂不做 / 勿回头扩范围】

- 支付宝跨境/Alipay+、分账、预授权押金自动扣款、发票全自动税控对接（待真实产品与资质）。
- Creem 客户打款/转账 API（官方无该能力；当前人工转账 + 流水登记）。
- 订阅升级/降级的自动退款与跨期补差（先完成基础升级/降级）。
- 多主体/多商户号、分账结算、资金池。

## 硬约束

1. Creem 退款 API 只支持全额；部分退款必须走人工审核 + 转账登记，不得伪造“部分退款 API”。
2. 渠道停用只影响新建支付；已支付订单的 Webhook/退款必须继续可用。
3. 涉及金额/币种/周期的功能开关默认关闭，真实渠道验收后再开；生产 `BILLING_REFUND_REQUIRE_APPROVAL=true`。
4. 任何新渠道/功能必须同时补：配置校验、Webhook/回调、幂等、账本分录、审计、失败补偿、管理端开关。

### Creem 渠道硬约束（合并自 plan-8.1）

1. Creem 仅支持全额 API 退款；部分退款不得降级为全额退（会多退钱）。
2. Creem 退款必须用 `transaction_id`（`tran_` 前缀）；`ch_` 必须回查 checkout 取回交易号。
3. 验签口径：`creem-signature` = HMAC-SHA256(原始 body, webhook-secret) 的 hex，无时间戳拼接。
4. 回调金额取 `order.amount`（小计）；`amount_paid` / `refund_amount` 是含税总额，不得作为本地订单金额口径。
5. Creem 订阅只有 `canceled_at`，没有 `cancel_at_period_end`；三态 TRUE/FALSE/null 必须严格区分，`null` 不改本地标记。
6. `/payment/success` 与 `/payment/cancel` 必须保持匿名可达（GET 放行 + 只回显 URL 自带标识）。

## 验证命令

- 编译：`mvn -o -q -DskipTests compile`
- 全量：`mvn -o -q test`
- 打包：`mvn -o -q -DskipTests package`
