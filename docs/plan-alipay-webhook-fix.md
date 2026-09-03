# 修复方案：支付宝 Webhook 验签必然失败（C1，共两处）+ 退款渠道解析（C2）

> 状态：已批准，进入实施。本文件为 `docs/plan-2.3.md` 的 C1 收口版，改动内容与其完全一致。

## 一、缺陷根因

### C1-第一处：Controller 从 HTTP Header 取支付宝 sign（恒为 null）
`WebhookController.alipayWebhook` 用 `headers.get("sign")` 取签名。但支付宝异步通知以
`application/x-www-form-urlencoded` POST，`sign` 在**请求体参数**里，不在 HTTP Header。
→ `signature` 恒 null → `verifyWebhookSignature` 直接 `return false` → 回调 401「Invalid signature」
→ 用户付款后永不发货，渠道持续重试。

### C1-第二处（审计报告遗漏）：AlipayStrategy 提前 remove 导致 SDK 拿 null 签名
`AlipayStrategy.verifyWebhookSignature` 在调用 SDK 前执行了
`params.remove("sign")` 和 `params.remove("sign_type")`。

独立复核（`javap -c` 反编译 `alipay-sdk-java-4.40.560.ALL.jar` 的 `AlipaySignature`）确认：
`rsaCheckV1(Map, pubKey, charset, signType)` 的字节码是：
1. `params.get("sign")` 先取出签名（offset 0-11）；
2. 再调 `getSignCheckContentV1(params)`，而该方法**内部自己会** `params.remove("sign")` / `params.remove("sign_type")`（offset 6-23）。

→ 业务侧提前 remove 会让 SDK 第 1 步拿到 null 签名 → 验签照样失败。
→ **只改 Controller 从 body 取 sign 是修不好的，L208-209 必须一并删掉。**

### C2：管理端退款 100% 走失败分支
`AdminService.refundOrder` 用 `resolveMethod(order.getPaymentProvider())` 解析渠道策略。
`order.paymentProvider`（对应 `orders.payment_provider`）**主代码零赋值点**，从未被写入
→ `resolveMethod` 恒返回 null → 跳过渠道退款 → 标记 `REFUND_FAILED` 抛 `BusinessException`。
退款功能实际不可用。

## 二、修复方向（已批准）

1. `WebhookController`：sign 改从 body 参数取（复用 `FormParamParser`）。
2. `AlipayStrategy`：删除两行 `params.remove(...)`，交由 SDK 自行剔除；解析改走 `FormParamParser`。
3. `CheckoutService.selectProvider`（`throw PAYMENT_CREATE_FAILED` 之后）落库
   `order.setPaymentProvider(method.name())`。
4. `AdminService.refundOrder`：存量订单兜底，从已加载的 `payment.getMethod()` 回补渠道。
5. 抽取 `FormParamParser` 工具类，消除 Controller / AlipayStrategy 中重复的 `&`/`=` 解析逻辑。

## 三、改动文件清单

| 文件 | 改动 |
|---|---|
| `src/main/java/com/billing/license/service/payment/util/FormParamParser.java` | **新增** 表单编码解析工具类（`@UtilityClass`） |
| `src/main/java/com/billing/license/controller/webhook/WebhookController.java` | sign 改从 body 参数取 |
| `src/main/java/com/billing/license/service/payment/impl/AlipayStrategy.java` | 删 2 行 remove；解析改走 `FormParamParser`；删除 private `parseCallbackParams` |
| `src/main/java/com/billing/license/service/CheckoutService.java` | 落库 `paymentProvider` |
| `src/main/java/com/billing/license/service/AdminService.java` | 存量订单 provider 兜底 |
| 测试类 ×5（新增 2、修改 3） | 共 +11 用例，含 7 个「改前必挂」断言 |

## 四、验证

- 全量 `mvn test` 预期 91 tests, 0 failures, 0 errors, BUILD SUCCESS（基线 80）。
- 反向验证：临时回退 3 处主代码、保留新测试重跑，预期 7 个用例失败，证明测试真能抓 bug。
- 无 Flyway 迁移（`orders.payment_provider` 已在 V1 建列）。

## 五、回滚

- 代码级：`git diff` 反向即可，~35 行，无数据库变更，零数据风险。
- 已上线回滚：无需回滚数据库（`payment_provider` 残留值无副作用），直接 revert 重新发布即可。
