# 审计未处理任务收口 Plan（plan-audit-backlog）

- **生成日期**：2026-09-04
- **唯一事实源（findings）**：`docs/archive/审计报告-2026-09-02.md`
- **已归档（handled，见 `docs/archive/`）**：C1 / C2、w1–w17、i2 / i3 / i4 / i5 / i6 / i7 / i8 / i9 / i10 / i11 / i12
- **本 plan 仅含未处理任务**；完成后在末节「验收与状态」更新状态（不回写归档 plan）

---

## 一、未处理清单

### i1（info · 低）测试计数口径对齐
- **问题**：README 写 80 个测试、审计报告 i1 称「实际 77」，但 2026-09-04 实跑 `mvn test` = **98 tests, 0 failures, 0 errors**（plan-2.3 已判定 77 不属实；80 为 UnionPay 删除前旧口径，删除后旧报告残留 + 后续 info 级用例新增导致真实数已升至 98）；`target/surefire-reports` 残留历史报告文件。
- **范围**：`README.md`、`docs/plan.md`（已归档）、`target/surefire-reports`
- **修复**：`docs/README.md` 三处测试数 80→98，并修正 `plan.md` 链接为 `./archive/plan.md`；清理 surefire 历史残留；后续以 `mvn test` 实跑数为准（已验证 = 98）。
- **优先级**：低（纯文档校正）

### T1（上线前 · 高）支付渠道真实沙箱 / 生产联调
- **问题**：6 家渠道（ALIPAY / WECHAT_PAY / STRIPE / PADDLE / PAYPAL / UnionPay）仅做了代码层模拟，未做真实沙箱 / 生产回调与退款联调；尤其 **H7（Paddle v2 沙箱金额单位未×100）** 等上线前必踩。
- **范围**：各 `PaymentStrategy` + `WebhookController` 端到端。
- **修复**：逐渠道用真实测试密钥跑 create → webhook → fulfill → refund，核对签名 / 金额单位 / 状态机。
- **优先级**：高（上线门禁）

### T2（中）性能压测
- **问题**：无压测数据；限流 / 补偿 / 并发发放路径（R3 轮询冷却、R5 并发发放）缺乏真实负载验证。
- **范围**：`CheckoutService.getStatus` 补偿、并发兑换、退款。
- **优先级**：中

### T3（安全 · 高）客户端 exe 验签逻辑
- **问题**：License 签发 / 验签服务端已做，但桌面 exe 客户端侧 JWS 验签（含 w13 算法降级 ES384/RS512 风险）未独立验证。
- **范围**：exe 客户端验签模块。
- **优先级**：高（安全）

### T4（中）渗透测试
- **问题**：未做渗透测试；Webhook 验签、管理端鉴权、限流等安全面需外部验证。
- **优先级**：中

---

## 二、验收与状态

| 编号 | 状态 | 证据 / commit |
|---|---|---|
| i1 | ✅ 已处理 | 2026-09-04 实跑 `mvn test` = 98/0/0；docs/README.md 三处 80→98 + plan.md 链接修正；surefire 34 个 dumpstream 已清，2 个过期报告（UnionPayStrategyTest/AuditVerifyTmpTest）因 IDE 持有句柄未删，无害残留 |
| T1 | 🔧 代码层审计全绿（5 渠道无「绿但坏」缺陷）· 全链路待真实密钥 | Paddle 3 缺陷已修 101/0/0；Alipay(rsaCheckV1+URL解码)/WeChat(平台证书SHA256-RSA+分)/Stripe(SDK+分)/PayPal(verify端点+全header) 代码层审计通过；`@RequestHeader Map` 大小写不敏感已验证非缺陷（见 plan-t1-channel-verify.md §二、§三） |
| T2 | ⬜ 待处理 | — |
| T3 | ⬜ 待处理 | — |
| T4 | ⬜ 待处理 | — |

> 说明：w1–w17 与多数 info 在 `docs/archive/plan-2.4.md` 标注已实施，且已抽样核对代码（w14 Swagger 放行、w7 限流淘汰、w10 渠道 fail-fast 均在库）；如后续需正式验收，可单独发起一轮「已归档项复验」，不列入本未处理 plan。
