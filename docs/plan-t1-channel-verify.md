# T1 支付渠道真实沙箱/生产联调 — 验证矩阵（plan-t1-channel-verify）

- **关联**：`docs/plan-audit-backlog.md` 的 T1（上线门禁 · 高）
- **生成日期**：2026-09-04
- **当前状态**：Paddle 代码层缺陷已定位并修复（单测锁定，101/0/0 全绿）；其余渠道 + Paddle 真实沙箱全链路仍待真实密钥。
- **唯一事实源**：本文件 + `src/main/java/.../payment/impl/*`、`WebhookController.java`、`PaddleStrategyTest.java`

---

## 一、已完成的代码修复（单测验证，无需密钥）

### Paddle（最危险的渠道，原 backlog 仅标记 H7 金额，实测另有两个未标记缺陷）

| 缺陷 | 位置 | 问题 | 修复 | 验证 |
|---|---|---|---|---|
| **H7 金额单位** | `PaddleStrategy.java` createPayment `unitPrice.amount` / refundPayment `amount` | 发主单位小数 `"9.99"`，Paddle v2 要求最小货币单位整数串 `"999"` → 按 9.99 分计费或拒单 | 新增 `toMinorUnitString()`：`×100` 转整数串（与 Stripe/WeChat 一致，且与本类入站 `parseWebhookPayload` 的 `÷100` 解析对称） | `createPayment_shouldSendAmountInMinorUnits` / `refundBody_shouldUseMinorUnits`（"9.99"→"999"、"19.50"→"1950"） |
| **Webhook h1 编码错误** | `verifyWebhookSignature` | `h1` 用 **Base64** 比对，但 Paddle v2 发的是 **hex**（官方示例 `h1=eb4d0dc8…` 为 64 位 hex）→ 所有合法 Paddle 回调被拒，付款永不发货 | 改用 `bytesToHex()` 计算 HMAC-SHA256 的十六进制串与 `h1` 比较（大小写不敏感） | `verifyWebhookSignature_shouldValidateHmac`（hex 约定）、`shouldReturnFalse_whenH1Mismatch`（篡改 payload 拒） |
| **时间戳取错头** | `verifyWebhookSignature` | 从不存在的 `Paddle-Timestamp` 请求头取 ts → 恒 null 直接拒 | Paddle v2 把 `ts` 放在 `Paddle-Signature: ts=…;h1=…` 内；解析该头取 `ts`/`h1`（`WebhookController` 已把此头作为 `signature` 参数传入） | `shouldReturnFalse_whenTimestampExpired`（±5min 重放防护仍生效） |

> 旧 `PaddleStrategyTest` 用「同样错误的 Base64 约定」自洽通过——正是审计报告警告的「测试绿但生产坏」陷阱；已改为 hex 并新增防篡改用例。

**证据（可独立交叉验证）**：
- Paddle 官方文档 `developer.paddle.com/api-reference/transactions/create-transaction` 与 `developer.paddle.com/webhooks/about/signature-verification`：
  - `unit_price.amount` = 最小货币单位整数串（例 `"999"` = $9.99，`"4900"`/`"1099"`）。
  - `Paddle-Signature` 头格式 `ts=…;h1=…`，`h1` = `HMAC-SHA256(ts:raw_body, secret)` 的 **hex** 编码。

---

## 二、代码层审计结论（本地可验证，已完成）

> 审计方法：逐渠道读取 `src/main/java/.../payment/impl/*Strategy.java` 的 `verifyWebhookSignature` / `createPayment` / `refundPayment` / `parseWebhookPayload`，并对照 `WebhookController.java` 的 header 透传，与**官方算法/金额单位约定**逐一核对，定位「测试绿但生产坏」的同类缺陷。本环境无真实密钥，仅做代码层静态核对 + 既有单测结论。

### 2.1 逐渠道结论

| 渠道 | Webhook 验签算法 | 金额单位（出库/入站） | header 透传 | 结论 |
|---|---|---|---|---|
| **ALIPAY** | `AlipaySignature.rsaCheckV1(params, publicKey, UTF_8, RSA2)`（官方 SDK）；验签前**不** `remove("sign")`（旧 C1 坑已闭环，见 L206-208 注释） | 主单位小数 `"9.99"`（元）——符合支付宝 `total_amount` 约定 | body 内 `sign` 由 `WebhookController` 解析后传入；`FormParamParser.parse` **已做 `URLDecoder.decode(UTF_8)`**，与支付宝签名计算口径一致 | ✅ 无新增缺陷 |
| **WECHAT_PAY** | 用**平台证书**公钥 `SHA256withRSA` 验 `ts\nnonce\nbody\n`（v3 标准串）；含 ±5min 重放防护；证书缺失触发刷新重试 | 出库 `amount.total = 元×100` 整数分；入站 `total÷100`（对称，正确） | `Wechatpay-Timestamp/Nonce/Serial/Signature` 由 `headers` Map 透传 | ✅ 无新增缺陷 |
| **STRIPE** | `Webhook.constructEvent(payload, signature, secret)`（官方 SDK） | 出库 `unitAmount = 元×100` 分；入站 `amount_total÷100`（对称，正确） | `Stripe-Signature` 经 `@RequestHeader` 直传（大小写不敏感绑定） | ✅ 无新增缺陷 |
| **PAYPAL** | `POST /v1/notifications/verify-webhook-signature`，转发明文全部 `Paypal-Transmission-*`（`Id/Time/Cert-Url/Auth-Algo/Sig`）+ `webhook_event` | 主单位小数 `"9.99"`（美元）——符合 PayPal `value` 约定 | `Paypal-Transmission-Id` 由 `@RequestHeader` 直传，其余经 `headers` Map | ✅ 无新增缺陷 |
| **PADDLE** | `h1 = hex(HMAC-SHA256(ts:raw_body, secret))`，解析 `Paddle-Signature: ts=…;h1=…` | 出库最小货币单位整数串（`×100`，已修 H7）；入站 `÷100` | `Paddle-Signature` 由 `@RequestHeader` 直传 | ✅ 3 缺陷已修（见第一节） |

**关键交叉验证点**：
- **Alipay URL 解码**：`rsaCheckV1` 内部 `getSignCheckContentV1` **不会**自行解码，必须由调用方先解码。本实现经 `FormParamParser.parse`（`src/.../util/FormParamParser.java:41-42`）`URLDecoder.decode(UTF_8)` 处理 —— 与支付宝服务端签名口径一致，**这是 Alipay 通知最经典的「漏解码→验签恒失败」坑，已规避**。
- **WeChat 平台证书**：旧实现误用商户证书验签（必然失败且安全模型错误），现改平台证书 + 启动即拉取 + 缺失刷新重试，正确（`WechatPayStrategy.java:50-55, 288, 368-373`）。
- **Stripe/WeChat 金额对称性**：出库 `×100` 与入站 `÷100` 在各自 `parseWebhookPayload` 中对称，不存在单边单位错误。

### 2.2 Spring `@RequestHeader Map` 大小写不敏感 —— 解除 WeChat/PayPal 隐患

> 审计中曾怀疑：WeChat `headers.get("Wechatpay-Timestamp")`、PayPal `headers.get("Paypal-Transmission-Id")` 等取**混合/大写** key，而 `WebhookController` 经 `@RequestHeader Map<String,String> headers` 注入 —— 若容器把 header 名小写化（Tomcat 行为随版本而变），这些 `get` 会返回 null → 验签恒失败（典型「绿但坏」）。

**结论：非缺陷。** `RequestHeaderMapMethodArgumentResolver` 对 `Map` 型 `@RequestHeader`（无 `value`）返回的是 `NativeWebRequest.getHeaders()` 即 **`HttpHeaders` 实例**，其为 `LinkedCaseInsensitiveMap`，**key 查找大小写不敏感**。故 `headers.get("Wechatpay-Timestamp")` 对 `wechatpay-timestamp` / `WECHATPAY-TIMESTAMP` 均可命中；PayPal 同理。Stripe/Paddle 更直接用 `@RequestHeader(value="Stripe-Signature"/"Paddle-Signature")` 强绑定（大小写不敏感）。**5 渠道 header 取值全部安全。**

> 仍建议真实回调首跑时打印 `headers` key 集合做一次确认（见第三节联调步骤 5），属稳健性确认而非缺陷修复。

### 2.3 真实沙箱全链路（仍待真实密钥）

**前置阻塞（需用户提供）**：
1. 各家真实沙箱/测试密钥（**Stripe test key 最容易获取，建议优先打通端到端**）。
2. 公网可达的 Webhook 回调端点（Hookdeck / ngrok 隧道，或部署到测试环境），否则收不到渠道回调。
3. 各渠道在沙箱后台配置的 Webhook 目标 URL 与签名密钥。

**每渠道联调步骤（拿到密钥后）**：
1. `createPayment` → 拿到渠道侧交易/结账 URL，确认金额与币种（Paddle 重点核 `"999"` 而非 `"9.99"`）。
2. 沙箱触发支付完成 → 渠道推送 Webhook。
3. `WebhookController` 验签通过 → `parseWebhookPayload` → 金额校验 R4 → `fulfillOrder` 发货（License/兑换码）。
4. 发起退款 → 核退款金额单位与状态机（PAID→REFUND_FAILED 不谎报）。
5. **首跑确认**：打印 `headers` key 集合确认 2.2 大小写结论；反向用错误密钥/篡改 payload 验证验签拒绝（401）与金额不符拒绝（400）。

---

## 三、验收与状态

| 项 | 状态 | 证据 |
|---|---|---|
| Paddle H7 金额 | ✅ 已修（单测锁定） | `PaddleStrategyTest` 金额用例，101/0/0 |
| Paddle Webhook h1 hex | ✅ 已修（单测锁定） | `shouldValidateHmac` / `shouldReturnFalse_whenH1Mismatch` |
| Paddle Webhook ts 解析 | ✅ 已修（单测锁定） | `shouldReturnFalse_whenTimestampExpired` |
| Alipay 验签（rsaCheckV1 + URL 解码） | ✅ 代码层审计通过 | `AlipayStrategy.java:194-230` + `FormParamParser.java:41-42` |
| WeChat 验签（平台证书 SHA256-RSA + 金额分） | ✅ 代码层审计通过 | `WechatPayStrategy.java:345-399, 186` |
| Stripe 验签（SDK）+ 金额分 | ✅ 代码层审计通过 | `StripeStrategy.java:122-142, 65-68` |
| PayPal 验签（verify 端点 + 全 header 透传） | ✅ 代码层审计通过 | `PayPalStrategy.java:167-199` |
| `@RequestHeader Map` 大小写不敏感 | ✅ 验证非缺陷 | Spring `HttpHeaders` 大小写不敏感（2.2） |
| 全渠道真实沙箱全链路 | ⬜ 待真实密钥 | 见 2.3 前置阻塞 |

> 说明：本文件记录「代码缺陷修复 + 代码层审计」这一可本地验证的部分；T1 的完整验收（真实沙箱 create→webhook→fulfill→refund）依赖外部密钥，不列入本次本地交付。代码层 5 渠道现已全部确认无「绿但坏」缺陷。
