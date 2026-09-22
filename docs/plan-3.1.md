# plan-3.1 · 第二轮业务完整性审计发现（唯一活动 plan）

## TODOS（仅未完成）

- [ ] **Paddle 续费可能重复延长（多送 License 时长）** — `SubscriptionService.bindOrRenewLicense`（:120-151）对**每个** paymentSuccess 事件都执行 `expiresAt + 1 周期`；而 `PaddleStrategy` 把 `transaction.billed`、`transaction.completed`、`subscription.*`（status=active/trialing）**全部映射为 SUCCESS**（:306-323）。同一续费周期若到达多个 SUCCESS 事件（Paddle 续费通常同时发 billed + subscription.updated），会被重复延长 N 次。**待确认**：Paddle 真实事件流（同一周期实际投递哪几个事件）——属外部事实，需以收到的真实回调为准。**加固方向**：延长前用 `payload.getCurrentPeriodEnd()` 与 `license.getExpiresAt()` 比对，该周期已延长则跳过（幂等），避免依赖事件个数。

## 登记表（外部阻塞 / 需你本人动手，不占 TODOS）

- **管理统计页实机走查（2026-09-22）**：`static/admin/` 页面已上线（编译 + 8 测试绿，JS 语法过），但当日 Docker/Postgres（127.0.0.1:10001）未运行，未能起服务带真实数据走查。DB 可用后启动服务，浏览器访问 `http://localhost:8000/admin/`：输入管理 Key → 6 个分区各看一遍（空态/有数态）、时间范围快捷键、流水分页与筛选、401 时是否退回解锁卡片。
- **N6 报错文案实机复测**：后端已在线（8000），浏览器真实报错文案需你走一遍 GUI。
- **B2/B3 回调路径实机验证**：发货 / 渠道退款吊销需真实支付宝异步通知或构造签名回调触发，建议管理端造单或沙箱回调复核。
