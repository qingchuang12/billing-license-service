# plan-3.1 · 业务完整性收口后的收尾验证（唯一活动 plan）

> 版本：v3.2（2026-09-20）· 唯一活动 plan（billing-license-service）。
> 本版记录：业务完整性审查（B1-B9）已按川哥指示执行落地，`mvn -o test` 全绿（212/0/0）。已交付项按用户规则清除，仅保留待用户侧/环境阻塞的收尾验证。

## 本版决策与交付（留一行证据，随 plan 清理）
- **B1 独立注册端点：保留**（川哥 2026-09-20 拍板「账户注册接口依旧保留」）。故 B6 语义随之调整为「查无账户静默成功」（防枚举），不再回 EMAIL_NOT_PURCHASED。
- **B2/B7 已交付**：`PaymentService.updatePaymentStatus` 改 4 参（paymentId→transactionId→订单号 三路回退定位），查无降级为 warn 日志不再抛异常阻断发货；`WebhookController` 死代码路径复活为真实兜底。
- **B3 已交付**：`WebhookController.revokeOnChannelRefund` 处理渠道侧 REFUNDED/CANCELLED，吊销订单全部非 REVOKED License + `Order.markRefunded()`，与管理端同口径。
- **B4 已交付**：`CheckoutService.selectProvider` 增会话去重（已 PAID / 同渠道 PENDING 复用），防连点双重收银台。
- **B5 核实无需改**：邮件发送已 `@Async`（独立线程、无事务），发货事务不受邮件失败回滚影响。
- **B6 已交付**：`AccountService.resetPassword` 查无账户静默返回，不泄露邮箱是否为付费客户。
- **B8 已交付**：`SecurityConfig` 将 `/checkout/**`、`/account/**` 收紧为仅 `GET` permitAll。
- **B9 已交付**：`RedeemCodeService` 兑换按客户+产品幂等，已签发 License 重试直接返回既有 License。
- 验证：`mvn -o test` → Tests run: 212, Failures: 0, Errors: 0, BUILD SUCCESS（WebhookControllerTest 10/10、AccountServiceTest 15/15）。

## TODOS（仅未完成 · 待用户侧/环境阻塞）

- [ ] **N6 报错文案实机复测** — 后端实例重启使 N2/N3 信封生效后，浏览器复测真实报错文案。后端重启可经 IDEA MCP，浏览器复测需人工 GUI。（待川哥过一遍）
- [ ] **前后端端到端冒烟** — ai-tools（前端）↔ billing-license-service（后端）激活/收银台/退款回调联调。当前环境无 Docker/PostgreSQL（端口 10001），无法本地起后端，阻塞于外部资源。（环境阻塞）
