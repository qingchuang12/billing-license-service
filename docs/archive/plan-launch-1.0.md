# 上线就绪计划 · billing-license-service v1.0.0

> **[已归档 2026-09-07]** 本 plan 为 2026-08-23 的历史快照，原位于 `doc/plan-launch-1.0.md`（旧文档目录）。其全部任务卡已被后续 plan 覆盖完成：
> - 卡1 配置与密钥治理 → `application.yml` 已无写死口令（`DB_PASSWORD` 无默认值 fail-fast）、`ddl-auto: validate`（H3）
> - 卡2 部署可运行性 → B20 容器化（Dockerfile 多阶段 + compose 数据源/密钥清理 + application-docker.yml）
> - 卡3 安全加固 → H8/H9/H10/H11/H13（限流淘汰、CORS/CSRF、脱敏 DTO、常量时间比较+审计、Actuator 探针）
> - 卡4 文档校正 → docs/ 文档体系重建（`docs/README.md` 为项目入口）
> - 卡0/卡5 基线验证与发布收尾 → `docs/archive/plan.md`（v2.2）结论：可发布候选
> 未完成的后续验证闸门（真实沙箱联调/压测/客户端验签/渗透测试）现由活动 [`../plan.md`](../plan.md) 承接。

> 主调度：WorkBuddy（idea-dev-steward）｜生成类任务首选 marscode/deepseek-v4-pro，降级 hy3（WorkBuddy 自身）；操作经 JetBrains MCP。
> 外部模型别名待 IDEA 运行时核验；若 P1–P4 全不可达，由 hy3 生成并标注。
> 版本：v1.0 (2026-08-23 22:39)

---

## 一、需求确认（阶段一结论）

**产品**：面向桌面 exe 工具的统一「计费 + 授权许可」中台。核心能力：
- 非对称签名 License（Ed25519/ECDSA/RSA，绑定 machine_id，离线验证）
- 统一订单 / 兑换码 / 发货；多支付渠道（Stripe、支付宝、微信、PayPal、Paddle、银联）
- KMS 私钥签名（local/aws/azure/aliyun）；Webhook 统一回调发货；风控限流；邮件

**上线范围（本次工程目标）**：使服务达到「可随时发布」的工程就绪态——编译/测试/静态校验全绿，密钥不外泄，配置可外置，容器可构建部署，安全闸门到位，文档与代码一致，附回滚预案与上线清单。

**成功标准 / 验收口径**：
1. `mvn`/IDEA 构建零错误，全量单测通过
2. IntelliJ inspections 无 error 级问题
3. 无写死密钥 / 调试残留；所有敏感配置经环境变量注入
4. `docker compose build` 成功且容器可连 PostgreSQL 启动
5. admin 接口 API-Key 鉴权生效（非占位即拒）；Webhook 验签；`/actuator/health` 可用
6. 文档与代码一致（修正「Java17/SpringBoot3.2/仅 Stripe」等过时表述）
7. 给出「可随时发布」结论 + 剩余风险清单 + 回滚预案

**约束**：
- 技术栈锁定：Java 21 + Spring Boot 4.0.6（已验证，不降级）
- 不破坏既有不变量：JWS License 格式、订单/兑换码状态机、Webhook 幂等
- 破坏性动作（删密钥/改表结构）先确认并备份

**不在本次范围（需用户提供 / 运维前置）**：
- 真实支付商户号与 API 密钥、域名与 TLS 证书、KMS 生产实例
- 目标服务器 / CI-CD 流水线、实际发版时间窗
- 六大渠道全部「生产可用」需逐家联调凭证，本次仅保证代码与配置就绪、按配置启停

---

## 二、任务卡（串行执行，完成一张再下一张）

| # | 任务卡 | 涉及文件 | 验收 | 建议模型 |
|---|--------|----------|------|----------|
| 0 | 基线验证：编译+全量测试+全项目 inspections | 全项目 | 编译0错/测试绿/inspections 无 error，产出问题清单 | 操作经 MCP（execute_run_configuration + get_file_problems） |
| 1 | 配置与密钥治理（上线阻断修复） | `application.yml`、新增 `application-prod.yml`、`doc/.env.example` | 无写死密钥；prod 不自动改表；无 DEBUG 暴露；admin key 缺失即启动失败 | hy3 |
| 2 | 部署可运行性：Dockerfile + compose 修正 | 新增 `Dockerfile`、`docker-compose.yml` | `docker compose build` 成功；容器起得来并连 PG | hy3 |
| 3 | 安全加固：admin/webhook/actuator/限流 | `SecurityConfig`、`AdminController`、webhook 控制器、`application.yml` | 无 key 访问 admin→401；webhook 验签失败拒收；`/actuator/health`=200 | hy3 |
| 4 | 文档校正：README/架构/流程对齐真实栈 | `doc/README.md`、`doc/架构设计.md`、`doc/业务流程.md` | 文档与代码一致，无过时表述 | hy3 |
| 5 | 发布就绪收尾：回滚预案+上线清单+交叉校验 | `doc/上线就绪.md` + 代码二次复核 | 给出「可随时发布」结论 + 风险清单；核心改动无空指针/并发/越权/边界漏洞 | hy3 |

**执行顺序**：0 → 1 → 2 → 3 → 4 → 5
**依赖**：1 完成后 2/3/4 可并行规划但按序落地；2 复用 1 的新配置；5 最后。
**硬规则**：禁止一次性批量派发；任一卡闸门不绿 → 回退修复，不进下一张。

---

## 三、已识别上线阻断项（来自基线审计）

1. `application.yml:9` 写死 DB 口令默认值 `XGTjfWhkfd3QXJA7`（写死密钥，必须移除）
2. `ddl-auto: update` 与 Flyway 并存（生产应 `validate`）
3. `logging.level com.billing.license: DEBUG`（生产应 INFO）
4. `docker-compose.yml`：`app` 连 `127.0.0.1:10001/license`（用户 license），但 compose 起 `postgres:5432` 库 `billing_db`（用户 postgres）——容器内不可达
5. 缺 `Dockerfile`，`build: .` 无法构建
6. `admin-api-keys: admin-key-change-me` 占位默认值（未覆盖即暴露 admin，应强制覆盖）
7. 文档漂移：README 写 Java17/SpringBoot3.2/仅 Stripe，实际 Java21/SpringBoot4.0.6/六支付+KMS

---

## TODOS
- [ ] 卡0 基线验证
- [ ] 卡1 配置与密钥治理
- [ ] 卡2 部署可运行性
- [ ] 卡3 安全加固
- [ ] 卡4 文档校正
- [ ] 卡5 发布就绪收尾
