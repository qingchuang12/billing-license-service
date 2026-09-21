# 平台账务管理 API（plan 1.0）

## 需求（已与川哥对齐）
- **形态**：仅后端 API（不做前端页面），挂在 `/api/admin/accounting/*`。
- **能力**：收入总览 / 分渠道与分产品统计 / 交易流水明细 / 时间趋势 + 对账差异。

## 现状调研
- `/api/admin/**` 已有 X-API-Key 鉴权（SecurityConfig）、`@Audit` 审计、Swagger 注解 —— 新接口直接继承。
- 现有 Admin 只有订单/License/兑换码管理，**无账务视角**；数据在库（Order/Payment/PaymentEvent/Subscription/License）。
- `OrderRepository`/`PaymentRepository` 方法极少，**需补时间范围查询**。
- 现有订单接口**无分页**，新交易明细接口必须带分页。

## 核心设计决策
1. **多币种绝不混加**：`Currency` 枚举 24 种且带 `minorUnits`，所有金额统计一律**按币种分组**返回，禁止跨币种求和。
2. **金额口径**（写代码注释固化）：
   - 已支付（计入实收）：`status ∈ {PAID, REFUNDED, REFUND_FAILED}`
     （REFUND_FAILED 时 paymentStatus 仍为 PAID，钱没退成 → 仍算实收）
   - 已退款：`paymentStatus = REFUNDED`
   - 净收入 = 实收 − 已退款
3. **聚合方式**：Repository 按时间范围取列表 → Service 用 Stream 按币种/渠道/产品分组聚合。
   账务属运营低频查询，优先保证口径清晰与可维护性；数据量大时可改为数据库聚合（已注释标注）。

## 接口清单
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/accounting/overview` | 收入总览：GMV/实收/退款/净额/订单数/客单价，按币种分组 |
| GET | `/api/admin/accounting/by-channel` | 分渠道统计（支付宝/微信/Stripe/Paddle/PayPal）× 币种 |
| GET | `/api/admin/accounting/by-product` | 分产品档位统计 × 币种 |
| GET | `/api/admin/accounting/transactions` | 交易流水明细（时间/渠道/状态/订单号/币种筛选 + **分页**） |
| GET | `/api/admin/accounting/trend` | 时间趋势（day/month 粒度）× 币种 |
| GET | `/api/admin/accounting/discrepancies` | 对账差异：订单与支付状态/金额不一致的疑点记录 |

## 改动清单
1. `dto/accounting/` 新增 DTO（Overview/ChannelStat/ProductStat/TransactionView/TrendPoint/Discrepancy 等）。
2. `repository/OrderRepository` 补 `findByCreatedAtBetween` 等。
3. `repository/PaymentRepository` 补时间范围 + 分页查询。
4. `service/AccountingService` 新增（聚合逻辑）。
5. `controller/AccountingController` 新增（6 个接口，带 Swagger + @Audit）。

## 完成情况（2026-09-20 落地）
- [x] DTO 与 Repository 查询方法（7 个 accounting DTO + Order/Payment 时间范围查询 + Payment 加 JpaSpecificationExecutor）
- [x] AccountingService 聚合逻辑（6 方法，@Transactional(readOnly=true)，口径与多币种分组见类注释）
- [x] AccountingController 6 接口（/overview /by-channel /by-product /transactions /trend /discrepancies，带 Swagger + @Audit；交易接口带分页与渠道/状态/币种过滤）
- [x] 静态校验（包路径/类名/枚举引用一致，无残留旧签名）

## 关键决策与取舍
- **退款不写 REFUNDED 支付记录**：`AdminService.refundOrder` 只 `markRefunded()` 改订单状态，故退款金额只能从订单侧体现；交易流水（Payment 表）不含退款行，已在接口说明。对账差异规则据此改为「已退款订单无成功支付记录」等真实可触发项。
- **数据源分离**：收入/渠道/产品/趋势以 Order 为主口径；交易流水以 Payment 为主。
- **低频聚合取列表再 Stream 分组**：优先口径清晰；大数据量可改 DB 聚合，对外契约不变。
- **未做前端**：按川哥对齐，仅后端 API；前端可后续按需接 Swagger 或另行开发。

## TODOS
（账务 API 已全部完成；待川哥经 IDEA MCP 触发构建验证编译）
