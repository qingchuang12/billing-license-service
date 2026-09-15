# plan v2.10 · 账号体系（注册 / 登录 / 改密 / 找回密码 / 验证码）

> 版本：v2.10（2026-09-15）
> 前序：v2.9（上线前验证闸门 T1–T4）**仍在进行中**，其待办阻塞于外部资源（渠道密钥、公网回调、客户端仓库）。本 plan 为**新功能开发线**，与 v2.9 并行，互不改动对方范围。
> 状态：**A0 已确认（2026-09-15）**，进入 A1 实施。4 项设计决策已锁定：① `customerId` 直接复用为 `User.id`；② 登出/改密走 `tokenVersion` +1 使旧令牌失效；③ 注册**强制**邮箱验证码（`emailCode` 必填）；④ 令牌密钥**独立配置** `account.jwt-secret`。详见第十一节。
> 契约基线不变：现有 21 个端点、两档鉴权（公开 / `X-API-Key`）**保持原样**；本次为**新增**第三档（用户令牌）与独立前缀 `/api/account/**`。

---

## 一、背景与目标

**现状问题**（据代码核实，非推测）：

1. 服务只有两档鉴权：公开端点 / `X-API-Key`（`SecurityConfig#authorizeHttpRequests`），**没有终端用户身份**。
2. `Order.customerId` 是自由 UUID（`nullable = false`）；未传 `customerId` 时由 `CheckoutService` **自动生成匿名客户 UUID**。用户换机、重装、换邮箱后**无法找回自己的订单与 License**。
3. 客服只能凭订单号/邮箱人工查单（`GET /api/admin/orders?orderNumber=`），无自助入口。

**目标**：为桌面 exe 客户端提供基础账号能力——注册、登录、改密、找回密码、验证码，并明确**各接口的登录态要求与限流阈值**（第五节）。

---

## 二、范围与边界

**做**：

- 账号数据模型（`users`、`verification_codes` 两表，Flyway `V10`）
- 7 个账号端点（`/api/account/**`）
- 会话令牌（JWT，无状态，与现有 `STATELESS` 策略一致）
- 鉴权模型由**两档扩为三档**（新增「用户」档）
- 账号相关接口的限流（复用 `RateLimitService`，不新建限流组件）
- 文档同步：`README.md` API 段、`接口调用时序图.md`（调用顺序 + 入参示例）、OpenAPI `@Schema`

**暂不做**（明确排除，避免范围蔓延）：

| 项 | 原因 |
|---|---|
| 第三方登录（OAuth / 微信扫码） | 与桌面 exe 场景重叠度低，且需渠道侧资质 |
| 二次验证（TOTP）、登录设备管理 | 属「增强」而非「基础」 |
| 用户自助查单页（我的订单 / 我的 License） | **建议列为 v2.11**，本次先把身份建立起来 |
| 角色权限细分（RBAC） | 当前只需「普通用户 / 管理员」两种身份 |
| 图形验证码 / 滑块 | 邮箱验证码 + 限流已覆盖基础防刷；如风控需要再补 |

---

## 三、现状盘点

### 3.1 可直接复用（已读源码确认）

| 组件 | 复用点 |
|---|---|
| `RateLimitService#checkAndCount(namespace, key, max, windowMinutes)` | 通用滑动窗口限流，新增 namespace 即可，**无需改代码** |
| `EmailNotificationService` | 已有 `@Async`、`isEmailConfigured()` 短路、HTML 模板与 `HtmlUtils` 转义；新增验证码/重置模板方法即可 |
| `ApiKeyAuthFilter` | 过滤器模式可直接照搬，用于新增 `JwtAuthFilter` |
| `ApiResponseAdvice` + `OpenApiConfig.billingEnvelopeCustomizer` | 统一响应壳自动生效，文档同步，无需改动 |
| `GlobalExceptionHandler` + `BusinessException` | 错误码机制现成，新增码即可 |
| `KeyHashUtil` | 已有 SHA-256 工具，可用于验证码哈希 |
| `@Audit` + `AuditAspect` | 改密、重置等敏感操作可直接声明式审计 |
| `spring-security-crypto`（随 `starter-security` 传递，本地仓库已确认存在） | `BCryptPasswordEncoder`，无需新增依赖 |
| `spring-boot-starter-validation` | 请求体校验，已在 pom |

### 3.2 需新建

```
entity/User.java                          users 表
entity/VerificationCode.java              verification_codes 表
repository/UserRepository.java
repository/VerificationCodeRepository.java
service/AccountService.java               注册 / 登录 / 登出 / 改密
service/VerificationCodeService.java      验证码签发与校验
service/PasswordResetService.java         找回密码
security/JwtTokenService.java             令牌签发与解析
security/JwtAuthFilter.java               用户令牌过滤器 → ROLE_USER
controller/AccountController.java         /api/account/**
config/AccountProperties.java             prefix = "account"
dto/ 共 5 个请求 DTO + 2 个响应 DTO
db/migration/V10__accounts.sql
```

### 3.3 需注意的现状约束（**关键**）

> **`jjwt` 依赖已在 pom（0.13.0），但源码中尚无任何使用**（`LicenseIssuer` 是手工构造 JWS + KMS 签名，`grep io.jsonwebtoken` 零命中）。
> 因此**没有现成用法可参照**；实现前须核对 0.13.0 的 API（0.12 起 Builder 签名方式有变更），不可凭旧版经验编写。

> **邮件通道当前不可用**：`application.yml` 的 `spring.mail.username` 默认为空，`EmailNotificationService#isEmailConfigured()` 返回 false 时**静默跳过发送**（仅记 warn 日志）。
> 注册与找回密码强依赖邮箱验证码 —— 若 SMTP 未配置，这两个功能将**不可用且难以察觉**。故本 plan 引入 `account.code-log-only`（见第九节），联调期把验证码输出到日志，避免被 SMTP 阻塞。

---

## 四、接口设计（7 个端点，前缀 `/api/account`）

| # | 方法与路径 | 登录态 | 一句话职责 |
|---|---|---|---|
| A1 | `POST /api/account/verification-code` | 公开 | 发送邮箱验证码（`purpose` = `REGISTER` / `RESET_PASSWORD`） |
| A2 | `POST /api/account/register` | 公开 | 邮箱 + 密码注册；按配置校验邮箱验证码 |
| A3 | `POST /api/account/login` | 公开 | 邮箱 + 密码登录，返回访问令牌 |
| A4 | `POST /api/account/logout` | **需登录** | 登出；使该用户所有已签发令牌失效 |
| A5 | `GET /api/account/me` | **需登录** | 当前用户信息 |
| A6 | `POST /api/account/password/change` | **需登录** | 改密（需旧密码），改后强制重新登录 |
| A7 | `POST /api/account/password/reset` | 公开 | 找回密码（邮箱 + 验证码 + 新密码） |

### 4.1 请求体

| 端点 | 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|---|
| A1 | `email` | string | ✅ | 邮箱格式 |
| A1 | `purpose` | string | ✅ | `REGISTER` / `RESET_PASSWORD` |
| A2 | `email` | string | ✅ | 邮箱格式、未被注册 |
| A2 | `password` | string | ✅ | 见第八节密码策略 |
| A2 | `emailCode` | string | ✅ **必填**（已确认强制邮箱验证） | 须为 `purpose=REGISTER`、未消费且在有效期内；校验通过后该码立即消费 |
| A3 | `email` / `password` | string | ✅ | — |
| A5 | — | — | — | 无请求参数 |
| A6 | `oldPassword` / `newPassword` | string | ✅ | 新旧不得相同 |
| A7 | `email` / `code` / `newPassword` | string | ✅ | 验证码用途须为 `RESET_PASSWORD` |

### 4.2 响应

- A2 / A3：`{ accessToken, expiresIn, user: { id, email, emailVerified } }`
- A5：`{ id, email, emailVerified, status, createdAt, lastLoginAt }`
- A1 / A4 / A6 / A7：`data` 为 `null`（统一壳仍包裹）
- **令牌不落 cookie**：桌面客户端从响应体取出后自行保存（服务端无会话）

### 4.3 错误码（新增）

| 错误码 | 触发 |
|---|---|
| `EMAIL_ALREADY_REGISTERED` | 注册时邮箱已存在 |
| `INVALID_CREDENTIALS` | 登录邮箱或密码错误（**统一文案，不区分**，防账号枚举） |
| `ACCOUNT_LOCKED` | 连续失败达阈值，账号临时锁定 |
| `ACCOUNT_DISABLED` | 账号被管理员停用 |
| `CODE_INVALID` / `CODE_EXPIRED` / `CODE_TOO_MANY_ATTEMPTS` | 验证码校验失败三类 |
| `CODE_SEND_TOO_FREQUENT` | 验证码发送冷却中 |
| `PASSWORD_POLICY_VIOLATION` | 不满足密码策略 |
| `OLD_PASSWORD_MISMATCH` | 改密时旧密码错误 |
| `TOKEN_INVALID` / `TOKEN_EXPIRED` | 令牌缺失/非法/过期 |

---

## 五、接口访问与限流限制矩阵（本次重点）

### 5.1 登录态要求（按接口）

| 接口 | 未登录 | 已登录（用户令牌） | 管理员（`X-API-Key`） |
|---|---|---|---|
| A1 发送验证码 | ✅ 允许 | ✅ 允许 | ✅ 允许 |
| A2 注册 | ✅ 允许 | 应拒绝（已登录无需注册）| ✅ 允许 |
| A3 登录 | ✅ 允许 | ✅ 允许（允许多端并存）| ✅ 允许 |
| A4 登出 | ❌ 401 | ✅ 允许 | ✅ 允许 |
| A5 当前用户 | ❌ 401 | ✅ 允许 | ✅ 允许 |
| A6 改密 | ❌ 401 | ✅ 允许 | ✅ 允许 |
| A7 找回密码 | ✅ 允许 | ✅ 允许（用户可能忘记当前密码）| ✅ 允许 |
| **现有 21 个端点** | **保持不变** | 令牌**不改变**其现有权限 | 不变 |

> **重要边界**：现有公开端点（`/api/checkout/**`、`/api/redeem/redeem`、`/api/licenses/verify/**`、`/api/webhooks/**`）**不因账号体系上线而收紧**——已发布的客户端尚未携带令牌，收紧会造成线上断裂。令牌对它们是「可选附加信息」，而非准入条件。
> `/api/admin/**` 仍只认 `X-API-Key`；**用户令牌不得访问管理端**（两者权限域严格隔离，`ROLE_USER` ≠ `ROLE_ADMIN`）。

### 5.2 限流阈值（复用 `RateLimitService`，namespace 独立）

| 接口 | 维度 | 阈值 | 窗口 | 触发结果 |
|---|---|---|---|---|
| A1 发送验证码 | 邮箱（冷却） | 1 次 | 60 秒 | `CODE_SEND_TOO_FREQUENT` |
| A1 发送验证码 | 邮箱（累计） | 5 次 | 60 分钟 | 同上 |
| A1 发送验证码 | IP | 10 次 | 60 分钟 | 同上 |
| A2 注册 | IP | 5 次 | 60 分钟 | 429 / 业务码 |
| A2 注册 | 邮箱 | 3 次 | 60 分钟 | 429 / 业务码 |
| A3 登录 | **账号**（失败计数） | 5 次 | → 锁定 15 分钟 | `ACCOUNT_LOCKED` |
| A3 登录 | **IP**（失败计数） | 20 次 | 10 分钟 | 429 / 业务码 |
| A6 改密 | 用户 | 3 次 | 60 分钟 | 429 / 业务码 |
| A7 找回密码 | 邮箱 | 5 次 | 60 分钟 | 429 / 业务码 |
| A7 找回密码 | IP | 10 次 | 60 分钟 | 429 / 业务码 |
| **验证码校验**（A2/A7 共用） | 邮箱 + `purpose`（失败计数） | 5 次 | — | 该码立即作废 `CODE_TOO_MANY_ATTEMPTS` |

**设计说明**：

1. **账号级锁定**持久化在 `users.failed_login_count` / `locked_until`，跨重启有效（内存限流器不具备该性质）。
2. **IP 级限流**走 `RateLimitService`（进程内，多实例部署需换共享存储——与现有已知限制一致，见 v2.9 §5.3）。
3. **登录失败的两级防护**：IP 档用于挡「撞库扫描」，账号档用于挡「单账号爆破」。两者独立计数，互不覆盖。
4. 登录成功时**清零**该账号的 `failed_login_count`。
5. 限流触发一律记录 WARN 日志并落审计（`@Audit`）。

### 5.3 令牌自身的限制

| 项 | 取值 |
|---|---|
| 令牌类型 | JWT（HS256，对称密钥来自配置，缺失即启动失败） |
| 载荷 | `sub`=userId、`ver`=tokenVersion、`iat`、`exp` |
| 有效期 | 默认 168 小时（7 天），可配置 |
| 失效手段 | 登出 / 改密 / 重置密码 → `tokenVersion` +1，**该用户所有旧令牌立即失效** |
| 传输 | `Authorization: Bearer <token>`（仅此一处） |
| 存储 | 服务端不存令牌（无状态）；`tokenVersion` 由 `JwtAuthFilter` 每请求校验 |

> **为何不用纯无状态 JWT**：无状态令牌签发后无法撤销，「登出」与「改密后踢下线」将形同虚设。代价是 `JwtAuthFilter` 每请求查一次 `users`（单机 exe 配套服务，成本可接受）。若后续部署为多实例且要求无库校验，可改为「令牌短时效 + 刷新令牌」模型，属独立演进主题。

---

## 六、鉴权模型变更：两档 → 三档

| 档位 | 凭证 | 适用路径 | 实现 |
|---|---|---|---|
| **公开** | 无 | 现有：`/api/webhooks/**`、`/api/checkout/**`、`/api/licenses/verify/**`、`/api/redeem/redeem`；新增：A1 / A2 / A3 / A7（**逐条显式列出**） | `permitAll` |
| **用户**（新增） | `Authorization: Bearer <JWT>` | `/api/account/**` 中除 A1/A2/A3/A7 外的端点（A4 / A5 / A6） | `JwtAuthFilter` → `ROLE_USER` |
| **特权** | `X-API-Key` | `/api/admin/**` | `ApiKeyAuthFilter` → `ROLE_ADMIN`（不变） |
| 内部 | — | `/actuator/health/**`、`/v3/api-docs`、`/swagger-ui/**` | `permitAll`（不变） |

**`SecurityConfig` 变更要点**：

- 新增 `JwtAuthFilter` 并注册到过滤器链（与 `ApiKeyAuthFilter` **并列**，任一命中即写入对应身份；两者不互斥）；
- `authorizeHttpRequests` 中，**A1/A2/A3/A7 的 `permitAll` 必须写在 `/api/account/**` 之前**（Spring Security 按声明顺序匹配，顺序颠倒会导致登录接口也要求令牌 → 死锁）；
- 保留 `anyRequest().denyAll()` 作为兜底，新增路径一律显式放行。

---

## 七、数据模型（Flyway `V10__accounts.sql`）

> 现有迁移已至 `V9__license_status_semantics.sql`，故本次为 **V10**；遵循 `scripts/README.md` 的「禁止合并 / 禁止首次部署后修改」规则。

### 7.1 `users`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | UUID | PK | **直接承载 `Order.customerId`**（已确认）：登录用户下单即以 `userId` 作为 `customerId`；匿名订单仍为随机 UUID，不对应任何 `User` |
| `email` | VARCHAR(255) | UNIQUE NOT NULL | 入库前统一转小写 |
| `password_hash` | VARCHAR(100) | NOT NULL | BCrypt 密文 |
| `status` | VARCHAR(20) | NOT NULL | `ACTIVE` / `DISABLED` |
| `email_verified` | BOOLEAN | NOT NULL DEFAULT false | — |
| `token_version` | INT | NOT NULL DEFAULT 0 | 登出 / 改密时 +1，使旧令牌失效 |
| `failed_login_count` | INT | NOT NULL DEFAULT 0 | 登录成功清零 |
| `locked_until` | TIMESTAMP | NULL | 为空或已过期则未锁定 |
| `last_login_at` | TIMESTAMP | NULL | — |
| `created_at` / `updated_at` | TIMESTAMP | NOT NULL | — |

### 7.2 `verification_codes`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | UUID | PK | — |
| `email` | VARCHAR(255) | NOT NULL | 小写 |
| `purpose` | VARCHAR(30) | NOT NULL | `REGISTER` / `RESET_PASSWORD` |
| `code_hash` | VARCHAR(100) | NOT NULL | **SHA-256(pepper + code)，不存明文** |
| `expires_at` | TIMESTAMP | NOT NULL | 签发 + 10 分钟 |
| `consumed_at` | TIMESTAMP | NULL | 一次性消费标记 |
| `attempt_count` | INT | NOT NULL DEFAULT 0 | 超 5 次作废该码 |
| `created_at` | TIMESTAMP | NOT NULL | — |

**索引**：`users(email)` 唯一；`verification_codes(email, purpose, created_at DESC)`；`users(locked_until)` 可选（解锁清理）。

> **不新建登录尝试表**：失败计数用 `users` 的两个列（持久化）+ 内存限流器（IP 维度）即可，避免为「基础功能」引入第三张表。

---

## 八、验证码 / 令牌 / 密码策略

### 8.1 验证码

- 6 位数字，`SecureRandom` 生成（**与 `RedeemCodeService#generateCode` 同源思路**）
- 有效期 10 分钟；一次性消费（`consumed_at` 非空即不可再用）
- 入库仅存哈希（`KeyHashUtil` SHA-256 + 配置 pepper），**日志默认不打印明文**
- 校验失败计数达 5 次 → 立即作废该码，需重新发送
- 签发新码时，**同邮箱同用途的旧未消费码一并作废**（避免多码并存被撞）

### 8.2 令牌

- 算法 HS256；密钥来自 `account.jwt-secret`（**不设默认值，缺失即启动失败**，与现有 `DB_PASSWORD` / `ADMIN_API_KEYS` 的 fail-fast 风格一致）
- 载荷最小化：仅 `sub` / `ver` / `iat` / `exp`，**不携带邮箱等 PII**
- `JwtAuthFilter` 校验顺序：签名 → 过期 → 用户存在且 `ACTIVE` → `ver == users.token_version`

### 8.3 密码

| 项 | 取值 |
|---|---|
| 存储 | BCrypt（cost 12） |
| 长度 | 8–72 字节（BCrypt 上限 72） |
| 复杂度 | 至少含 1 字母 + 1 数字（可配置放宽） |
| 禁止 | 与旧密码相同（改密 / 重置时校验） |
| 传输 | 仅经 HTTPS；**日志与审计中一律不得输出**（`@Audit` 的 `detail` 不可引用密码字段） |

---

## 九、配置项清单（新增顶层命名空间 `account`）

```yaml
account:
  # 令牌签名密钥：不设默认值，缺失即启动失败
  jwt-secret: ${ACCOUNT_JWT_SECRET:}
  # 令牌有效期（小时），默认 7 天
  token-ttl-hours: ${ACCOUNT_TOKEN_TTL_HOURS:168}
  # 注册是否强制邮箱验证码
  require-email-verification: ${ACCOUNT_REQUIRE_EMAIL_VERIFICATION:true}
  # 验证码位数与有效期
  code-length: ${ACCOUNT_CODE_LENGTH:6}
  code-ttl-minutes: ${ACCOUNT_CODE_TTL_MINUTES:10}
  # 验证码哈希 pepper（缺失则退化为无 pepper，建议生产必配）
  code-pepper: ${ACCOUNT_CODE_PEPPER:}
  # 联调模式：验证码仅写日志不发邮件（绕过未配置的 SMTP）
  code-log-only: ${ACCOUNT_CODE_LOG_ONLY:false}
  # 密码策略
  password-min-length: ${ACCOUNT_PASSWORD_MIN_LENGTH:8}
  password-require-alnum: ${ACCOUNT_PASSWORD_REQUIRE_ALNUM:true}
  # 风控阈值（与第五节矩阵一一对应）
  risk:
    login-account-fail-max: ${ACCOUNT_LOGIN_ACCOUNT_FAIL_MAX:5}
    login-account-lock-minutes: ${ACCOUNT_LOGIN_ACCOUNT_LOCK_MINUTES:15}
    login-ip-fail-max: ${ACCOUNT_LOGIN_IP_FAIL_MAX:20}
    login-ip-fail-window-minutes: ${ACCOUNT_LOGIN_IP_FAIL_WINDOW_MINUTES:10}
    code-send-cooldown-seconds: ${ACCOUNT_CODE_SEND_COOLDOWN_SECONDS:60}
    code-send-email-max: ${ACCOUNT_CODE_SEND_EMAIL_MAX:5}
    code-send-email-window-minutes: ${ACCOUNT_CODE_SEND_WINDOW_MINUTES:60}
    code-send-ip-max: ${ACCOUNT_CODE_SEND_IP_MAX:10}
    code-verify-fail-max: ${ACCOUNT_CODE_VERIFY_FAIL_MAX:5}
    register-ip-max: ${ACCOUNT_REGISTER_IP_MAX:5}
    register-email-max: ${ACCOUNT_REGISTER_EMAIL_MAX:3}
    reset-email-max: ${ACCOUNT_RESET_EMAIL_MAX:5}
    reset-ip-max: ${ACCOUNT_RESET_IP_MAX:10}
    change-password-user-max: ${ACCOUNT_CHANGE_PWD_USER_MAX:3}
```

> 风格与 `billing.risk`（`BillingProperties.Risk`）保持一致；统一收敛在 `AccountProperties`（`@ConfigurationProperties(prefix = "account")`）。

---

## 十、实现步骤（按依赖顺序，逐卡交付）

| 阶段 | 内容 | 交付判定 |
|---|---|---|
| **A0** | 确认第十一节的 4 项待决策 | 你拍板 |
| **A1** | `V10__accounts.sql` + `User` / `VerificationCode` 实体 + 仓储 | Flyway 迁移通过；`ddl-auto=validate` 启动无报错 |
| **A2** | `AccountProperties` + `PasswordEncoder`(BCrypt) + `JwtTokenService` + `JwtAuthFilter` + `SecurityConfig` 三档改造 | `/api/account/me` 无令牌返回 401、伪造令牌返回 401 |
| **A3** | `VerificationCodeService` + 邮件模板 + A1 发送接口 | 联调模式（`code-log-only=true`）下日志可见验证码 |
| **A4** | A2 注册 / A3 登录 / A4 登出 / A5 me | 注册→登录→me→登出→me(401) 全链路自测通过 |
| **A5** | A6 改密 / A7 找回密码 | 改密后旧令牌失效；重置后可用新密码登录 |
| **A6** | 限流接入（第五节矩阵全部落地）+ `@Audit` + 错误码 | 逐条对照矩阵验证阈值生效 |
| **A7** | 测试：单元（Service 层 Mock）+ 集成（`@SpringBootTest`，H2） | 参照 `LicenseServiceTest` / `CheckoutServiceTest` 现有风格 |
| **A8** | 文档同步：`README.md`（端点总览 21 → 28）、`接口调用时序图.md`（调用顺序 + 入参示例）、DTO `@Schema` | 文档与代码一致 |

> **构建提示**：本地 `mvn` 不可用、JetBrains MCP 亦不可用，验证编译须走 javac + `~/.m2` jar 手工构造 classpath（见项目 memory `MEMORY.md`）。

---

## 十一、设计决策（**已确认，2026-09-15**）

| # | 决策点 | 结论 | 关键影响 |
|---|---|---|---|
| 1 | `Order.customerId` 与 `User` 的关联 | ✅ **A · 复用** | 登录用户下单以 `userId` 直接作为 `customerId`，零 schema 改动、无历史数据迁移；匿名订单仍为随机 UUID，不对应任何 `User`。**待办**：`customerId` 语义由「客户标识」变为「用户标识或匿名标识」，须写入 `README.md` 契约说明（归入 A8） |
| 2 | 登出语义 | ✅ **B · `tokenVersion` +1** | 登出 / 改密 / 重置密码均使该用户**所有**旧令牌立即失效；`JwtAuthFilter` 每请求校验版本（单机成本可接受） |
| 3 | 注册邮箱验证 | ✅ **强制** | 注册接口 `emailCode` 必填；`require-email-verification` 默认 `true`，该开关仅保留用于联调期临时关闭。联调依赖 `account.code-log-only` 把验证码输出到日志，不被未配置的 SMTP 阻塞 |
| 4 | 令牌密钥来源 | ✅ **A · 独立配置 `account.jwt-secret`** | 与 License 的 KMS 签名密钥**用途隔离**（对称会话凭证 vs 非对称离线验签），密钥轮换互不牵连；缺失即启动失败（fail-fast） |

**其他已知风险**：

1. **SMTP 未配置则注册/找回不可用** —— 已在第九节用 `code-log-only` 兜底，但**生产上线前必须配置真实 SMTP 并实测**（否则用户永远收不到验证码）。
2. **多实例部署下限流失效** —— IP/邮箱频控为进程内内存态（与现有 `RateLimitService` 限制同源）。当前单实例部署可接受。
3. **账号枚举** —— 注册接口在邮箱已存在时返回 `EMAIL_ALREADY_REGISTERED` 会泄露「该邮箱已注册」。可选缓解：统一返回「验证码已发送」（需配合注册时验证码校验），**本次倾向保持明确错误码**（便于用户理解），如需收紧再调整。
4. **令牌有效期 7 天偏长** —— 若客户端被盗用，最长 7 天内有效。可通过缩短 TTL 或后续引入刷新令牌收敛，本次取「可用性优先」。

---

## TODOS（仅未完成项）

### A. 账号体系
- [x] A0 确认第十一节 4 项设计决策（**2026-09-15 已确认**：复用 `customerId` / `tokenVersion` 登出 / 强制邮箱验证 / 独立 JWT 密钥）
- [x] A1 Flyway V10 + `users` / `verification_codes` 实体与仓储（**已完成 2026-09-15**）
  - 交付：`V10__accounts.sql`、`entity/User.java`、`entity/VerificationCode.java`、`repository/UserRepository.java`、`repository/VerificationCodeRepository.java`
  - 验证：**已补做**——`~/.m2` 实际有 `spring-data-jpa`（此前判断有误），A2 起全量源码 javac 编译 EXIT=0，两个实体与两个仓储均通过编译
- [x] A2 `AccountProperties` + BCrypt(cost 12) + `JwtTokenService` + `JwtAuthFilter` + `SecurityConfig` 三档改造（**已完成 2026-09-15**）
  - 新增：`config/AccountProperties`（prefix `account`）、`security/JwtTokenService`、`security/JwtAuthFilter`；`SecurityConfig` 加 `PasswordEncoder` Bean、注册 JwtAuthFilter、账号公开端点逐条 permitAll 并声明在 `/api/account/**` 之前
  - **jjwt 0.13.0 API 已用 javap 核对**（`Jwts.builder()` / `Jwts.parser().verifyWith()` / `parseSignedClaims()` / `Keys.hmacShaKeyFor` / `Jwts.SIG.HS256`）——本地仓库原无该 jar，已从 Maven Central 取回 0.13.0 并核对后使用
  - 顺带：`RedeemCodeController` 的 IP 解析抽为 `common/web/ClientIpResolver`（H8 策略集中化，消除重复）
- [x] A3 `VerificationCodeService` + 邮件模板 + 验证码发送端点（**已完成 2026-09-15**）
  - `VerificationCode` 仓储补 `findUnconsumed`，用于把 `CODE_EXPIRED` 与 `CODE_INVALID` 区分开
  - `EmailNotificationService#sendVerificationCodeEmail` + HTML 模板；`code-log-only` 联调模式把验证码写日志
- [x] A4 注册 / 登录 / 登出 / 当前用户 四个端点（**已完成 2026-09-15**）
- [x] A5 改密 / 找回密码 两个端点（**已完成 2026-09-15**）
- [x] A6 限流矩阵落地（第五节 5.2 逐行）+ `@Audit` + 错误码（**已完成 2026-09-15**）
  - `RateLimitService` 新增 `peekCount(namespace,key,windowMinutes)`（只读计数，供登录前判定 IP 是否已超限，避免把合法请求计入失败数）；`TimestampRing` 抽出 `countWithin`
  - 新增错误码：`EMAIL_CODE_REQUIRED`、`LOGIN_IP_LIMIT`、`REGISTER_LIMIT`、`RESET_LIMIT`、`CHANGE_PASSWORD_LIMIT`、`ACCOUNT_NOT_FOUND`、`VALIDATION_ERROR`（`@Valid` 失败）
  - `GlobalExceptionHandler` 增加 `MethodArgumentNotValidException` 处理（400 + 中文提示，不回显字段名以外信息）
- [x] A7 单元测试（**已完成并运行通过 2026-09-15**）：`JwtTokenServiceTest`(6) / `VerificationCodeServiceTest`(5) / `AccountServiceTest`(13)，共 24 例；隔离安装 Maven 3.9.16 后 `mvn test` 全量 **134 用例全绿**（含新增 24 + 既有 110）
- [x] A8 文档同步（**已完成 2026-09-15**）：README 端点总览 21 → 28 + 鉴权三档 + 账号段（curl / 限流表 / 错误码 / `customerId` 语义）；时序图 §1.7.3 账号链路图 + §2.1 过滤链加 `JwtAuthFilter` + §4.8 七个端点入参示例；DTO 全部带 `@Schema`

### 实现偏离（需你知晓）
- **管理员 `X-API-Key` 不放行 A4/A5/A6**（plan 5.1 表中标注为"✅ 允许"）。理由：这三个端点全部依赖「当前用户」上下文（principal 为 `userId`），管理员令牌无此上下文，放行只会引入「我是谁」的歧义且要额外处理空值分支。故实现为 `hasAuthority("ROLE_USER")`，权限域严格隔离。如需管理员侧账号管理，应作为独立主题走 `/api/admin/**`。
- **账号端点未做集成测试（H2）**：本地无 mvn，无法验证 `@SpringBootTest` 上下文（且 `jwt-secret` 缺失即启动失败，需测试配置注入）。仅交付可编译的单元测试，集成测试待 IDEA 侧补齐。

### 收尾修复（2026-09-15 Maven 实测补充）

A7 原计划「编译通过但未运行」，本次隔离安装 Maven 3.9.16（见项目 memory `MEMORY.md`）后真正跑通全量测试，发现并修复 3 处：

1. **fail-fast 破坏既有集成测试**：`account.jwt-secret` 缺失即启动失败的设计（A2），使全部 `@SpringBootTest` 上下文加载失败（5 例）。
   修复：在 `src/test/resources/application-test.yml` 补 `account.jwt-secret`（≥32 字符，仅测试用，禁止复用到生产）。
2. **`AccountServiceTest.login_success_clearsFailureState` 测试语义矛盾**：原用例把 `lockedUntil` 设为未来 5 分钟，但 `login` 锁定校验优先于登录成功，必然抛 `ACCOUNT_LOCKED`。
   修复：将 `lockedUntil` 改为「已过期」（过去 1 分钟），正确验证登录成功后清零 `failedLoginCount` 与 `lockedUntil`。
3. **`RateLimitServiceEvictionTest` OOM（既有隐患，非本次引入）**：`TimestampRing` 构造器按 `capacity+1` 预分配 `AtomicLongArray`；该测试把 `emailPurchaseMax` 设为 100000 → 单窗口 800KB × 5000 key ≈ 4GB。
   修复：测试阈值改小为 10（每 key 仅调用 1 次，同样不触发限流、且仍超过驱逐阈值 4096 验证回收）——属修正放大参数，非掩盖；**生产级隐患（大 max 配置仍会爆堆）已记入项目 memory，待重构为动态容器治本**。

### 阻塞提示
- [ ] **上线前必做**：配置真实 SMTP 并实测「注册验证码 / 找回密码」两封邮件可达（否则功能形同虚设）
