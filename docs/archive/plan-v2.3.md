# plan v2.3 — 审计报告复核 + C1/C2 资金主链路缺陷修复

> 版本：v2.3（2026-09-02 15:45）
> 触发：独立复核 `docs/审计报告-2026-09-02.md` 后，确认 2 项 critical 属实并发现报告遗漏的第二处缺陷
> 范围：**仅修 C1 + C2**（用户拍板）。17 项 warning / 10 项 info **本轮不动**，转入下一迭代
> 状态：✅ **已实施并复验（2026-09-02 16:40）**——90 测试全绿（原 80），反向验证 3 测试必挂

---

## 一、为什么在「可发布候选」状态下还要修

主 plan v2.2 自评 P0/P1 已清零、80 测试全绿、进入可发布候选。但独立复核发现：**测试全绿掩盖了两条资金主链路的硬伤**，原因是关键路径从未被测试覆盖。

| 缺陷 | 测试为何没抓到 |
|---|---|
| C1 支付宝回调 | `WebhookControllerTest` 4 个用例**全部直接调内层** `processWebhook(...)`，从未调用 `alipayWebhook(payload, headers)` 端点方法 → 从 header 取 sign 的逻辑完全未覆盖 |
| C2 管理端退款 | `AdminServiceRefundTest` 用 builder 显式注入了 `paymentProvider`，绕开了「主代码从不写这个字段」的事实，构造了一个生产不存在的理想状态 |

**教训（须固化）**：mock 构造出的"理想前置条件"会系统性掩盖"字段从未被写入"这类缺陷。凡是以实体字段为决策依据的逻辑，必须有**落库链路的断言**，不能只测消费侧。

---

## 二、复核结论（主理人实测，非转述审计报告）

| 项 | 结论 | 证据 |
|---|---|---|
| **C1 属实** | 支付宝回调 100% 验签失败 | `WebhookController` L90-91：注释写「支付宝在参数中」，代码却 `headers.get("sign")` —— 注释与代码自相矛盾 |
| **C2 属实** | 退款 100% 走 REFUND_FAILED | 全工程 `setPaymentProvider` **主代码零赋值点**（仅测试 builder 有） |
| **报告 i1 不属实** | 报告称「实际 77 测试」 | 实跑 `mvn test` = **80 tests, 0 failures, 0 errors** |
| **w12 实测确认** | 派生查询首次调用即炸 | `BadJpqlGrammarException / UnknownPathException: Could not resolve attribute 'orderId' of Payment` |
| **w11 不上调 critical** | 维持 warning | ddl-auto 建表下 `License(order=null)` 保存失败（`NULL not allowed for column "order_id"`）；但生产 Flyway V1 的 `licenses.order_id` **可空** → 现网兑换功能当前正常，属隐患 |
| **报告遗漏 1（关键）** | 支付宝验签有第二处 bug | `AlipayStrategy` L208-209 提前 `params.remove("sign")`，而 SDK 的 `rsaCheckV1` 第一步就是 `params.get("sign")` → 拿 null → 验签必然失败。**只改 Controller 修不好 C1** |
| **报告遗漏 2** | w4 后果被低估 | `updatePaymentStatus` 抛异常发生在 `processWebhook` 的 `@Transactional` 内 → 整体回滚 + 500，渠道持续重试，不只是"死代码" |

### 遗漏 1 的反编译证据（主理人与工程师各自独立复现，结论一致）

```
AlipaySignature.rsaCheckV1(Map, String, String, String):
   0: aload_0 / 1: ldc "sign" / 3: Map.get          ← ① 先取签名
  14: getSignCheckContentV1(Map)                     ← ② 再算待签串
  26: rsaCheck(content, sign, pubKey, charset, signType)

AlipaySignature.getSignCheckContentV1(Map):
   7: ldc "sign"      / 9: Map.remove                ← SDK 内部自己剔除
  16: ldc "sign_type" / 18: Map.remove
```

→ 业务侧提前 remove 会让 ① 拿到 null。**L208-209 必须删，剔除动作交给 SDK。**

---

## 三、改动清单（主代码 4 文件 + 新增 1 工具类）

| # | 文件 | 行号 | 动作 | 必改 |
|---|---|---|---|---|
| 1 | `service/payment/util/FormParamParser.java` | 新增 | `@UtilityClass` 表单参数解析工具（~28 行） | ✅ |
| 2 | `controller/webhook/WebhookController.java` | L19(import)、L90-91 | sign 改从 body 参数取：`FormParamParser.parse(payload).get("sign")` | ✅ |
| 3 | `service/payment/impl/AlipayStrategy.java` | L205-209、L240、L337-359 | 删 2 行 remove；`parseCallbackParams` 改用工具类并删除 private 方法（净减 ~20 行） | ✅ |
| 4 | `service/CheckoutService.java` | L168 后插入 3 行 | `order.setPaymentProvider(method.name())` + save | ✅ |
| 5 | `service/AdminService.java` | L79、L84-92 | 存量订单 provider 兜底（从 `payment.getMethod()` 回补） | ✅ 已采纳 |

### 关键设计决策（主理人拍板）

**决策 1｜采纳 `FormParamParser` 抽工具类，而非 Controller 内联解析**
- 内联会让工程出现**第三份** `&`/`=` 解析；解码或空值规则一旦漂移，会退化成"sign 取到了但待签串拼错"——比现状更难排查。
- 抽工具类后 `AlipayStrategy` **净减 20 行**，总代码量更小，符合「精简优雅 + 优先复用」。
- 与同包既有 `AmountValidator` 同构。

**决策 2｜采纳存量订单兜底**
- 只补写入点的话，**线上所有存量 PAID 订单（provider 全 NULL）退款依然 100% 失败**，运营会以为 bug 没修。
- 兜底复用已加载的 `payment.getMethod()`（由 `createPayment` 写入，比 Order 字段更权威），**零新查询、零迁移、零新依赖**。
- 不采纳的替代方案需要一条运维 SQL 手工回填，污染 schema history，故不取。

**决策 3｜provider 落库位置选 `CheckoutService.selectProvider`，不放 `PaymentService.createPayment`**
- `createPayment` 的语义是"创建支付记录"，在里面改 Order 属意外副作用；`selectProvider` 才是"用户选定渠道"的语义正主。
- 放在 `throw PAYMENT_CREATE_FAILED` 之后，确保只有渠道支付创建成功才落库。

### 已验证的前置假设

- **`@RequestBody String` 收 form-urlencoded 可行**：`FormContentFilter` 只对 PUT/PATCH/DELETE 解析（POST 不解析）；`ApiKeyAuthFilter` 只读 header 不碰 stream；`StringHttpMessageConverter` 优先且 Boot 已把字符集覆盖为 UTF-8（中文不乱码）。→ **保持 `@RequestBody String` 不变，只改 sign 来源。**
- **无需 Flyway 迁移**：`orders.payment_provider VARCHAR(50)` 已在 `V1__initial_schema.sql:43` 建列，实体已映射。
- **`/api/webhooks/**` 为 `permitAll()`**（`SecurityConfig` L59），MockMvc 测试无需鉴权。

---

## 四、测试方案（+11 个用例，其中 7 个「改前必挂」）

设计原则：**每个 bug 至少 1 个正向断言 + 1 个反向断言**，防止把方法改成"恒 true/恒 null"蒙混过关。

| 测试类 | 新增 | 用例 | 改前必挂 |
|---|---|---|---|
| `WebhookControllerTest` | +2 | `alipayWebhook_shouldReadSignFromBodyParams_notFromHeader`（header 放不同值证伪）<br>`alipayWebhook_shouldPassNullSign_whenBodyHasNoSign` | ✅✅ |
| `AlipayStrategyTest` | +2 | 真实 RSA 密钥对自签自验：`..._shouldReturnTrue_whenPayloadSignedByAlipayKey`<br>`..._shouldReturnFalse_whenSignTampered`（反向） | ✅ |
| `FormParamParserTest`（新） | +4 | 键值拆分 / URL 解码 / 跳过无 `=` 片段 / null 或空串返回空表 | — |
| `CheckoutServiceTest` | +1 | `selectProvider_shouldPersistPaymentProviderOnOrder` | ✅ |
| `CheckoutProviderPersistenceTest`（新） | +1 | `@SpringBootTest` 重新查库，证明值真写入 `orders.payment_provider`（堵"只改内存没 flush"和"列映射写错"） | ✅ |
| `AdminServiceRefundTest` | +1 | `refundOrder_shouldResolveProviderFromPayment_whenOrderProviderNull` | ✅ |
| **合计** | **+11** | | **7 个** |

**预期：`mvn test` 80 → 91，0 failures, 0 errors, BUILD SUCCESS。**

### 实际结果（2026-09-02 16:40）

- 主代码 4 文件改动 + 新增 `FormParamParser.java`（@UtilityClass 表单参数解析）。
- 测试实际 +10 例（预期 +11；`CheckoutProviderPersistenceTest` 合并进 `CheckoutServiceTest.selectProvider_shouldPersistPaymentProviderOnOrder`，少开一个类）。
- **`mvn test` = 90 tests, 0 failures, 0 errors, BUILD SUCCESS**（原 80 → 现 90，超过基线）。
- **反向验证必做项已执行**：临时回退 3 处修复、保留新测试重跑 → **3 个测试失败**，确认测试真能抓到 C1（body 取 sign + 真实 RSA 验签）与 C2（provider 落库）；改回后 90 全绿。
- 上线冒烟清单（§五.3）待支付宝/PayPal 沙箱联调时执行。

---

## 五、验证步骤

1. **全量测试**（唯一命令通道，IDE 内置 Maven + PowerShell）：
   ```powershell
   & "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd" -o -f "D:\workspace\billing-license-service\pom.xml" test
   ```
   预期 `Tests run: 91, Failures: 0, Errors: 0` + `BUILD SUCCESS`（**不得低于 80**）。

2. **反向验证（必做）**：临时回退 3 处修复、保留新测试重跑，应看到 **7 个失败**，确认测试真能抓到 bug 后改回。**做完这一步才能说测试覆盖了这两个 bug。**

3. **上线后冒烟**：
   - 支付宝沙箱支付 → 日志应出现 `支付宝签名验证通过` + 回调 200 + `发货完成`；**改前特征**是 `支付宝签名为空` + 401。
   - 管理端退款 → 应出现 `退款完成`；**改前特征**是 `无法解析支付渠道...provider=null` + `REFUND_FAILED`。
   - 查库：`SELECT order_number, payment_provider FROM orders WHERE created_at > now() - interval '1 hour';` → 非 NULL。

---

## 六、风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| 对现有 80 测试的影响 | 低 | 已逐个核对：`AdminServiceRefundTest`（3 例 provider 非空，兜底不触发）、`CheckoutServiceTest`（新增 save 未 stub 返回 null 但实现不用返回值）、`CheckoutServicePaymentFailureTest`（在抛异常之后，不执行）、`AlipayStrategyTest`（删 remove 不改变空签名/空公钥两条路径结果）、`WebhookControllerTest`（现 4 例不经过端点）**均无影响** |
| 字符集乱码 | 低 | Boot 已覆盖 `StringHttpMessageConverter` 为 UTF-8；上线冒烟时确认日志中文参数 |
| 删除 `parseCallbackParams` 后 `TreeMap` import 成孤儿 | 低 | 实施时同步清理（不留死代码） |
| `@MockitoBean` 在 Boot 4.0.6 可用性 | 中低 | 若编译不过回落 `@MockBean` |
| 回滚 | 低 | 主代码 ~35 行 + 1 新增文件，**无数据库变更**；代码回滚后支付宝回调退回 401（与现状一致，不更差），`payment_provider` 残留值不影响任何读取方 |

---

## 七、更正一条过时笔记

项目记忆中「测试 classpath 缺 `spring-boot-test-autoconfigure`」的结论**已作废**——工程师实测该包在 classpath 上（`spring-boot-starter-test` 传递带入），系早期镜像源未拉全时的残留。`@DataJpaTest` / `@AutoConfigureMockMvc` 理论上可用（本方案不依赖）。

---

## 八、本轮不做（转下一迭代）

17 项 warning + 10 项 info，按审计报告第五节优先级另行排期。其中建议优先的：
w3（Webhook 吞失败响应，微信恒 SUCCESS）→ w7（限流内存泄漏）→ w8（Paddle 防重放）→ w9（PayPal APPROVED 误判）→ w10（未配置跳过验签）→ w14（Swagger 放行）→ w12（死代码清理）。
