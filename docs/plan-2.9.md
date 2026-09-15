# plan v2.9 · 上线前验证闸门（活动 plan）

> 版本：v2.9（2026-09-14）
> 前序：v2.0–v2.8 及各专项 plan 已归档至 `archive/`。
> **归档说明（v2.10，2026-09-15）**：账号体系 plan 已全量完成并归档至 [`archive/plan-v2.10.md`](./archive/plan-v2.10.md)，其未完成遗留项（L 段）结转本 plan；主题 J/K 的已完成 TODOS 同期移入 `archive/plan-v2.9-completed.md`。本 plan 现为**唯一活动 plan**。
> **归档说明（v2.8）**：A 审计报告 Critical、B 遗留项（除 W17）、D Swagger 修正、E 审计统一落库、F 字段枚举化 → [`archive/plan-v2.8-completed.md`](./archive/plan-v2.8-completed.md)。
> **归档说明（主题 G/H/I，2026-09-14）**：G 文档-代码一致性收口、H 契约分歧判定与双侧修复、I 接口合并简化（24→21 端点、鉴权 3 档→2 档）均已完成并整批移入 [`archive/plan-v2.9-completed.md`](./archive/plan-v2.9-completed.md)。
> **命名变更（2026-09-14）**：文件名由 `docs/plan.md` 改为 `docs/plan-2.9.md`，与「活动 plan 唯一且带版本号」规范一致。
> 当前状态：**不建议发布**——T1–T4 闸门未过；W17 代码已修、待真实 AWS KMS 端到端验证。
> 契约基线（G/H/I 之后）：API 统一 `/api/**`、**鉴权仅两档（公开 / `X-API-Key`）**、下单唯一入口 `POST /api/checkout/create`（可带 `provider` 一步下单）、全部管理动作在 `/api/admin/**`、共 21 个端点。
> **注（v2.10 上线后）**：账号体系新增 7 个 `/api/account/**` 端点并引入第三档鉴权（用户令牌），端点总数变为 28、鉴权三档；现有 21 个端点的路径与权限**未变**。详见 [`archive/plan-v2.10.md`](./archive/plan-v2.10.md)。
> **主题 J/K（2026-09-14）**：契约文档化——① 接口出入参 DTO 补齐 Swagger（`@Schema`）注解，10 个 DTO 全覆盖；② `docs/接口调用时序图.md` 新增「接口调用顺序（端到端总览）」（§1.7）与「接口入参示例」（第四章，21 个端点全覆盖）；③ 兑换与批量生成两处匿名 `Map` 响应提取为类型化 DTO。详见 TODOS。

---

## 一、背景与目标

P0/P1 阻塞项已清零、5 渠道代码层审计通过（详见归档）。本版目标：完成依赖外部资源的 4 项上线验证闸门。

## 二、范围与边界

**做**：T1 全渠道联调 / T2 压测 / T3 客户端验签 / T4 渗透。

**暂不做**：新功能开发（自助换机/自助解绑等产品化能力）、多实例优化（Redis 共享限流）、M6 Secrets Manager、M7 前端页面。

> 文档/契约整改（主题 G、H、I）已完成归档；后续文档维护遵循 `MAIN.md` 的「如何新增文档」与归档纪律。

## 三、实现思路

### T1 全渠道真实沙箱/生产联调（高）
- **触点**：各 `PaymentStrategy` + `WebhookController` + `ChannelConfigValidator`。
- **前置阻塞**：① 各渠道测试密钥（建议先 Stripe）② 公网 Webhook 端点 ③ 后台配置回调 URL/签名密钥。
- **步骤**：按 `archive/plan-v2.5-t1-channel-verify.md` §2.3 的 5 步清单逐渠道执行；Paddle 重点核金额为最小货币单位整数串（`"999"` 非 `"9.99"`）。
- **风险回滚**：渠道以 `enabled=false` 关停；验签异常先反向复核配置/代码。

### T2 性能压测（中）
- **触点**：`CheckoutService.getStatus` 补偿、`RateLimitService`、并发兑换/发放。
- **步骤**：真实 PG + 单渠道密钥，压测记录 P95/P99、限流淘汰行为。独立环境，不触生产。

### T3 客户端 exe 验签（高，需客户端仓库）
- **关注点**：算法白名单、机器码绑定、过期/吊销/篡改样本 5 类验证。
- **前置**：客户端需按 G/H/I 后的契约对接（`/api/**`、单 header `X-API-Key`、License 状态含 `REISSUED`）。

### T4 渗透测试（中）
- **范围**：Webhook 验签 / admin 鉴权（现为单 header `X-API-Key`）/ 限流 / CORS / 异常脱敏 / Actuator 暴露面。

## 四、依赖与顺序

T1（先 Stripe）→ T2/T3 并行 → T4 最后。任一闸门发现问题回流本 plan 修复。

> ⚠️ 生效于 2026-09-14：`DB_PASSWORD` 与 `ADMIN_API_KEYS` **已无默认值**，本地联调/压测前必须先注入环境变量（容器走 `.env`，单测走 `application-test.yml` 覆盖）。

---

## TODOS（仅未完成项）

### C. 上线前验证闸门（待外部资源）
- [ ] T1 全渠道真实沙箱/生产联调（前置：各渠道测试密钥 + 公网回调端点；建议先打通 Stripe）
- [ ] T2 性能压测：getStatus 补偿 / 限流淘汰 / 并发兑换，记录 P95/P99 与限流行为
- [ ] T3 客户端 exe 验签验证：算法白名单、机器码绑定、过期/吊销/篡改样本 5 类（需客户端仓库配合）
- [ ] T4 渗透测试：Webhook 验签 / admin 鉴权 / 限流 / CORS / Actuator 暴露面，发现项回流本 plan

### L. v2.10 结转遗留项（2026-09-15 并入，来源：[`archive/plan-v2.10.md`](./archive/plan-v2.10.md)）
- [ ] L1 `RateLimitService.TimestampRing` 占用内存与 `max` 成正比（按 `capacity+1` 预分配 `AtomicLongArray`），大 `max` 配置会撑爆堆 → 重构为动态容器 + 容量上限裁剪；用 `RateLimitServiceEvictionTest` 的大阈值场景做回归验证
- [ ] L2 上线前配置真实 SMTP 并实测「注册验证码 / 找回密码」两封邮件可达（`account.code-log-only` 仅联调兜底）——**阻塞于生产凭据，非仓库内可完成**

### 遗留风险（从 B 结转）
- [~] W17 AWS KMS 算法/编码映射：代码已修复（P-521→`ECDSA_SHA_512`/`ES512`、EC DER↔raw、JWS alg 精确化）+ `AwsKmsServiceTest` 通过；**真实 AWS KMS 密钥端到端验证待 T4/联调**（详见归档 plan-v2.8-completed.md）
