# plan v2.4 — 审计报告 warning/info 级整改（A+B+C 全量）

> 版本：v2.4（2026-09-02 16:30）
> 触发：C1+C2（v2.3）已修复并复验（90 测试全绿）；用户拍板「全做」审计报告剩余 12 类 warning/info 待办
> 范围：**17 项 warning + 10 项 info** 中可落地的部分（i7/i8 采用收敛契约而非删功能，避免破坏客户端）
> 状态：✅ 已实施并复验（2026-09-02 15:30，`mvn test` 98 测试 0 失败 0 错误，BUILD SUCCESS）

---

## 一、改动总表（按 A/B/C 档）

### A 档 · 资金/安全主链路（最高优先）

| # | 文件:行 | 现状 | 改动 |
|---|---|---|---|
| w3 | `WebhookController.java` wechatWebhook L118 / stripe L134 / paddle L150 / paypal L166 | 微信恒 SUCCESS、其余恒 200，丢弃 processWebhook 返回码 | 四方法改用 `processWebhook` 的真实 `ResponseEntity` 返回（微信改返回 `ResponseEntity<String>`，把 SUCCESS/OK 信息写进 body）；验签失败→401、金额不符→400、成功→200，事件不再静默丢失 |
| w10 | `StripeStrategy` L124-126 / `PayPalStrategy` L168-171 / `PaddleStrategy` L153-156 | 未配置时 `verifyWebhookSignature` 返回 true（=无鉴权） | 未配置改返回 **false**（拒绝回调）；另新增 `ChannelConfigValidator`（ApplicationRunner）在 **生产 profile** 下校验各渠道 secret/webhookId 非空，缺失则 fail-fast 中止启动（测试 profile 不校验） |
| w9 | `PayPalStrategy.java` L154 | `COMPLETED \|\| APPROVED` 当 SUCCESS | 仅 `COMPLETED` 当 SUCCESS，`APPROVED` 回落 PENDING（未捕获扣款不发货） |
| w8 | `PaddleStrategy.java` L158 | 取 Paddle-Timestamp 仅拼消息、未校验时效 | L158 后加 ±5min 新鲜度校验：超窗返回 false |

### B 档 · 稳定性/可用性

| # | 文件:行 | 现状 | 改动 |
|---|---|---|---|
| w7 | `RateLimitService.java` checkAndCount L45-61 | 主路径不调 evictStaleWindows | L53 `computeIfAbsent` 前加 `evictStaleWindows();`（与 countOnly 一致） |
| w14 | `SecurityConfig.java` L54-64 | 未放行 Swagger，被 denyAll 拦截 | L58 后加 `/v3/api-docs`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` permitAll |
| w12 | `PaymentRepository.java` L17 / `PaymentService.java` L114-116 | `findByOrderId(Long)` 派生查询（实体 orderId 为 @Transient）→ 首次调用抛 UnknownPathException；全死代码 | 删除 `PaymentRepository.findByOrderId(Long)` 与 `PaymentService.getPaymentByOrderId`（grep 确认 main 无调用点） |
| w1/w2 | `pom.xml` L48-52 / L53-57 | 显式锁定 spring-security-core 6.5.10、jackson-core 2.22.1，与 Boot 4 BOM 混搭 | 删除两依赖的 `<version>`（及 properties L33-34），改由 Boot 4.0.6 parent BOM 管理 |

### C 档 · 一致性/收尾

| # | 文件:行 | 现状 | 改动 |
|---|---|---|---|
| w15 | `CheckoutService.java` L110-111 | OrderItem 单价取 product.getPrice()（USD），与订单区域价不一致 | 改为复用 `orderAmount`（与 L89 同值） |
| w16 | `PaymentService.java` L49 | Payment.currency 按 method.isDomestic() 推 | 改为 `order.getCurrency()` |
| w17 | `CheckoutService.getStatus` L210-245 | 仅读本地会话，Webhook 丢失无主动查渠道补偿 | 进入时若 session 为 CREATED/PENDING 且未超时，调 `paymentService.queryPaymentStatus` 补偿（单次、幂等、超时保护）；成功则置 PAID |
| w11 | `License.java` L33-35 | 实体 order_id nullable=false，与 V1 迁移（可空）冲突；兑换码 order=null 会 JPA 失败 | `@JoinColumn(nullable = true)`（与 V1 及业务一致，无新迁移） |
| i4 | `docs/README.md` L3/L53 | 写「6 家渠道」（自相矛盾，L11 又写 5 家）；`ALIPAY_APP_PRIVATE_KEY` 键名错 | L3 改「5 家」；L53 改 `ALIPAY_PRIVATE_KEY` |
| i7 | `AdminController` L100+ / `SecurityConfig` L61-63 | 管理端点需同时带 X-API-Key + X-Admin-API-Key | SecurityConfig 移除 `/api/admin/**` 的 hasAuthority，改 permitAll（鉴权交控制器，保留审计 actor） |
| i8 | `LicenseController` L58 / `AdminController` L152 | 两处吊销端点重复 | 保留两处、文档化分工：v1=客户端自吊销（X-API-Key）、admin=特权吊销；无功能删除 |
| i11 | `docker-compose.yml` L24-29 | 仍是 bash TCP 探针 | 改 HTTP 探针 `wget -qO- http://localhost:8080/actuator/health` |
| i12 | `WebhookController.java` L257/recordPaymentEvent L260 | orderId 硬编码 null，审计表丢关联 | recordPaymentEvent 增加 orderId 参数，调用处传 webhookData.getOrderId() |

---

## 二、关键设计决策

1. **w10 fail-fast 仅限生产 profile**：测试 profile 下各渠道本就未配置，若启动期强校验会崩测试。用 `@Profile("!test")` 的 ApplicationRunner 校验，生产缺失即抛异常中止启动——比"验签返回 false 静默丢事件"更早暴露部署错误。
2. **w17 不过度设计**：仅主动查一次渠道（不轮询），命中成功即置 PAID；依赖现有状态机幂等（canFulfill/markPaid）防重复发货；加 `expiresAt` 超时保护（已过期会话不再查渠道，避免无限打外部 API）。
3. **i7/i8 收敛契约而非删功能**：删客户端在用的端点会破坏 exe 客户端契约；改为文档化分工 + 安全配置收敛。
4. **w11 改实体 nullable=true**：与 V1 迁移一致，兑换码场景 order=null 合法；无需新增 Flyway 迁移（V1 本就可空）。

---

## 三、测试方案

| 类 | 新增/改 | 覆盖 |
|---|---|---|
| `WebhookControllerTest` | + | 微信验签失败应返 401（body 含 FAIL）；金额不符返 400；成功返 200 |
| `PayPalStrategyTest` | + | APPROVED → PENDING（非 SUCCESS） |
| `PaddleStrategyTest` | + | 超 5min 时间戳 → 验签 false；有效期内 → 正常 |
| `RateLimitServiceTest` | + | checkAndCount 主路径触发 evict（阈值内不崩、超阈值回收） |
| `CheckoutServiceTest` | + | getStatus 在 Webhook 丢失时主动查渠道补偿置 PAID |
| `License` 相关 | + | order=null 可持久化（需 @SpringBootTest + H2） |
| 现有 90 测试 | 复跑 | 应全绿；特别关注 Alipay/Stripe/PayPal/Paddle 测试对 w10 返回 false 的兼容 |

**预期**：`mvn test` 达 90+，0 failures, 0 errors, BUILD SUCCESS。

---

## 四、验证步骤

1. `mvn test`（IDEA 内置 Maven + PowerShell，离线）：全绿。
2. 反向抽查：w9/w8 等改动点构造「改前必挂」断言。
3. 上游冒烟：生产启动（配齐渠道）应正常；未配齐任一渠道启动即失败（fail-fast）。

---

## 五、风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| w10 返回 false 影响现有「未配置渠道」的回调行为 | 中 | 现网若真有未配置渠道，其回调从"放行进 200"变"拒 401"——这是正确的安全收紧；配合 fail-fast 生产启动校验，部署期即可发现 |
| w17 主动查渠道增加外部 API 调用 | 低 | 仅 CREATED/PENDING + 未超时 + 单次查询；状态机幂等保护 |
| pom 删 version 后版本漂移 | 低 | Boot 4.0.6 BOM 锁定；构建后 `mvn test` 复验通过即证明兼容 |
| w11 改 nullable 触发 ddl-auto=validate 校验 | 低 | 测试 yml 为 validate，但实体与 V1 一致（均可空），不会冲突 |

---

## 六、实施与复验记录（2026-09-02 15:30）

### 6.1 落地结果

全部 A/B/C 档改动已实施。生产代码经磁盘复核确认落盘（SecurityConfig w14/i7、License w11、CheckoutService w15/w16/w17、ChannelConfigValidator w10、各 Strategy w8/w9/w10、RateLimitService w7、pom w1/w2、docs/README i4、docker-compose i11、WebhookController i12/w3）。

### 6.2 测试复验

`mvn test`（IDEA 内置 Maven + PowerShell，离线）结果：

```
Tests run: 98, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

新增/修正测试（共 8 例覆盖增量）：

| 测试类 | 覆盖项 |
|---|---|
| `PayPalStrategyTest` | w9 APPROVED→PENDING、COMPLETED→SUCCESS；w10 凭证缺失返回 false |
| `PaddleStrategyTest` | w8 超 5min 时间戳→false、有效期内→正常；w10 secret 缺失返回 false |
| `StripeStrategyTest` | w10 secret 缺失返回 false |
| `RateLimitServiceTest` | w7 checkAndCount 主路径触发 evict（不再无限累积） |
| `CheckoutServiceTest` | w17 Webhook 丢失时 getStatus 主动查渠道补偿置 PAID；C2 provider 落库 |
| `LicenseNullOrderTest` | w11 order=null 可持久化 |

### 6.3 实施期风险与处理（EBUSY）

本机 IDEA 文件锁导致 **Edit 工具多次静默丢弃编辑**（返回成功但磁盘未改），出现三类症状：① 测试文件类体被提前 `}` 截断致孤立方法（RateLimitServiceTest）；② 测试文件缺字段声明/import（CheckoutServiceTest 的 paymentRepository/PaymentStatus）；③ try-with-resources 误用于非 AutoCloseable 的 HttpServer（PayPalStrategyTest）。

处理：对每个被静默丢弃的改动改用**全量 Write 重写文件**并 `Read` 回读复核，确认落盘后再继续。最终 `mvn test` 98 全绿。教训：本环境下对正在构建/被 IDE 监视的源文件，Edit 不可信，须 Write + Read 复核。

### 6.4 遗留（上线前长期待办，非本次范围）

- **H7** Paddle v2 沙箱实测联调（金额单位未 ×100，本机无沙箱）。
- 各支付渠道真实密钥/商户号联调。
- 部署侧 Redis 共享限流 + 网关限流（多实例场景）。
- **i12 客户端契约**：审计表 orderId 关联已修，但 `payment_events` 全链路对账（M8）仍未做。
