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

## Flyway 脚本合并（2026-09-20 已完成，留证据一行随 plan 清理）
- 上线前收敛：原 V1..V12 合并为单一 `V1__baseline_schema.sql`（15 建表/13 改表按序拼接）。空库临时库验证等价（15 表/184 列/产品种子 4 条与逐版一致，差异仅 Flyway 自建历史表）；测试库 DROP SCHEMA 重置后经 Flyway 干净迁移至 v1，Hibernate validate 通过，应用启动成功、`/api/products` 200。
- 注意：改脚本后需清 `target/classes/db/migration` 旧编译产物，否则新旧 V1 撞版本号（Found more than one migration with version 1）。

## 端到端冒烟（2026-09-20 已跑通，留证据一行随 plan 清理）
- SSH 隧道 `10001→远程1001` 建立，后端经隧道连远程 PostgreSQL 18.3，Flyway 迁至 V12，Tomcat 起于 8000。启动关键：`.env` 的 `DB_PASSWORD` 为空会覆盖默认值导致 SCRAM 认证失败，需以环境变量传真实口令 `DB_PASSWORD=XGTjfWhkfd3QXJA7`（仓库调试口令，与 license/license 配套）。
- `GET /api/products` 200；`POST /api/checkout/create` 200 建会话；`POST /api/checkout/{id}/select-provider` 生成支付宝表单+订单号；**B4 去重复测通过**：同一 checkoutId 二次选同渠道复用既有订单，未新建第二个收银台。
- 注意 `/actuator/health` 含 MailHealthIndicator，SMTP 慢连要 11~21s，curl 需给足超时（≥25s）或走 readiness 分组。

## TODOS（仅未完成 · 待用户侧）

- [ ] **N6 报错文案实机复测** — 后端已在线（8000）；浏览器复测真实报错文案需人工 GUI，待川哥过一遍。
- [ ] **B2/B3 回调路径实机验证** — 发货/渠道退款吊销需真实支付宝异步通知或构造签名回调触发，冒烟未覆盖；建议用管理端造单或沙箱回调复核。
