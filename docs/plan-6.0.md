# plan-3.0：管理员与普通消费者统一账户密码登录

## 结论先行
- **根缺口不是"缺登录页"，而是 `User` 实体根本没有角色字段** —— 管理员目前根本不是"人"，
  只是配置里的一段静态串（`security.admin-api-keys`），连一条用户记录都没有。
  所以「统一登录」的第一步是把管理员变成 users 表里的一行记录。
- 好消息：消费者侧账号体系已经很完整（BCrypt 口令、JWT、踢下线、防爆破锁定），
  **管理员可以直接整套接过来复用**，不用重建一套。
- 真正的风险点在**提权**：整个改造必须保证"注册不能注册管理员""降权要能立刻生效"。

---

## 一、现状盘点（已核实，非推测）

### 1.1 已具备（可直接复用）
| 能力 | 证据 |
|---|---|
| 邮箱 + 密码登录、BCrypt(12) | `SecurityConfig.java:60-66` 口令编码器；`AccountController.java:78` 登录端点 |
| JWT(HS256) + `tokenVersion` 踢下线 | `JwtTokenService`；`JwtAuthFilter.java:76` 版本比对 |
| 防爆破：失败计数 + 锁定窗口 | `User.java:56-63` `failedLoginCount` / `lockedUntil` |
| 注册 / 邮箱验证码 / 找回密码 | `AccountController` 多个端点；公开端点已在 `SecurityConfig:110-114` 逐条放行 |
| 管理端点已有 `@Audit` 留痕 | `AdminController.java:111` 等 |

### 1.2 缺口（本主题要解决）
1. **`User` 无任何角色字段**（`User.java:28-72` 全字段枚举：仅 email / passwordHash / status /
   emailVerified / tokenVersion / failedLoginCount / lockedUntil / lastLoginAt）。表里分不出管理员与消费者。
2. **`JwtAuthFilter` 硬编码 `ROLE_USER`**（`JwtAuthFilter.java:80`）——JWT 永远拿不到 ROLE_ADMIN。
3. 管理员凭据是**静态串**：`ApiKeyAuthFilter.java:47` 授予 ROLE_ADMIN，但 principal 是常量字符串
   `"api-key"`，没有密码、不能改密、无法被单个停用（只能改配置重启）。
4. **管理控制台没有登录**：`/admin/index.html:50` 和 `admin.js:161` 显示，现在是把 X-API-Key
   直接粘进页面、存在浏览器里（`state.key`）逐请求带 header，**没有任何登录态**。
5. **连带陷阱（易漏）**：登出与改密都在 `/api/account/**`，而该前缀要求 `ROLE_USER`
   （`SecurityConfig.java:119`）。若管理员令牌只有 ROLE_ADMIN，**管理员连自助登出都调不了**。
6. 审计主体语义割裂：API Key 的 principal 是 `"api-key"`，JWT 的 principal 是 userId，
   换成密码登录后 `@Audit` 留痕的主体口径需要统一，否则事后追责追不到具体人。

---

## 二、设计

### 2.1 目标形态
管理员 = `users` 表中 `role = ADMIN` 的一行，走**同一个** `POST /api/account/login`。
登录后拿到的仍是同一套 JWT，只是该用户拥有 ADMIN 角色，因此能进 `/api/admin/**`。

### 2.2 角色存储：V5 迁移给 users 加列（严禁改动 V1–V4）
```
users.role VARCHAR(16) NOT NULL DEFAULT 'USER'   -- USER / ADMIN
```
注意：**不要把 role 写进 JWT**。理由见下，这是本设计的关键选择。

### 2.3 权限授予：改 `JwtAuthFilter`，从 DB 现查角色（推荐）
`JwtAuthFilter` 为了校验 `tokenVersion` 与 `status`，**每次请求本来就会查 User**（:60-78），
所以在那里按 `user.getRole()` 授予 authority 是**零额外查询成本**的。

**不把角色塞进 JWT 的理由**（安全收益很实在）：
- 角色写进 token 后，管理员被降权/停用也**无法立即使已有 token 失效**——那张 token 还能用满 7 天；
- 现查则天然即时生效，且可复用既有的 `tokenVersion + 1` 机制做"立即踢下线"。

### 2.4 必须解决的连带问题（决策 B1）
管理员是否需要 `ROLE_USER`？我的建议是 **ADMIN 同时授予 ROLE_USER + ROLE_ADMIN**：
- 否则管理员无法调用 `/api/account/logout`（登出）与改密，只能等 7 天自然过期，这在安防上是不可接受的；
- 副作用是管理员也能用"我的许可证/订单"等自助端点，考虑到管理员本身就是账号主体之一，可接受。
替代方案是引入 Spring Security 的 `RoleHierarchy`（ADMIN implies USER），语义更干净但改动略大。

### 2.5 首个管理员怎么来（决策 B3）
必须在有鉴权的种子机制下产生，**绝不能靠注册接口**。候选：
- SQL/运维脚本直接 `UPDATE users SET role='ADMIN'`（最简单，不入 API）
- 受保护的引导端点或启动器，仅在无 ADMIN 时允许执行一次

### 2.6 安全红线（务必逐条落实）
1. `POST /api/account/register` **必须强制 role=USER**，请求体一律不接受角色字段，防止自助提权。
2. 降权 / 停用管理员时**必须 `tokenVersion + 1`**，令其所有已签发令牌立即失效。
3. 管理员登录建议套用更强的口令策略（长度/复杂度），并保留既有的失败锁定。
4. X-API-Key 通道若保留（决策 B2），应明确降级为"机器用途备份通道"，与真人操作在审计上可区分。
5. 改造后须有回归用例明确证明：普通用户token访问 `/api/admin/**` 仍返回 403。

---

## TODOS（仅保留未完成项）

### 需开发（服务端）
- [ ] A6 管理员降权/停用时 `tokenVersion + 1` 立即踢下线（依赖角色变更入口，目前尚无该入口）
- [ ] A7 首个管理员的初始化手段（脚本或受保护引导端点）
- [ ] A8 统一 `@Audit` 的操作主体口径（区分 api-key 机器调用 vs 真人 userId）
- [ ] A11 编译/回归验证：本机无 mvn，须经 IDEA MCP 执行 `mvn test`，确认 A1–A5 改动零编译错误

### 需开发（前端）
- [ ] A9 管理控制台 `/admin` 增加登录页：邮箱密码 → JWT → 本地存证，替代粘贴 API Key
- [ ] A10 控制台请求改为携带 `Authorization: Bearer`，并处理 401 跳回登录

### 需决策（阻塞，需川哥拍板）
- [ ] B1 `ADMIN` 是否同时授予 `ROLE_USER`（推荐：是，否则管理员无法自助登出/改密）——
      **代码已按推荐项实现**（ADMIN 同时获 ROLE_USER + ROLE_ADMIN，见 `JwtAuthFilter`），待你确认；
      若不认可，删掉其中一个 authority 即可回退。
- [ ] B2 X-API-Key 是否保留？建议保留作机读备份通道，但需与真人操作在审计上区分
- [ ] B3 首个管理员的产生方式（运维 SQL vs 受保护引导端点）
- [ ] B4 管理员是否强制更强口令策略 / 是否要求二次因子（MFA）
- [ ] B5 管理员能否使用消费侧能力（查看/购买、我的许可证），还是严格限定只进管理端

### 待核实
- [ ] C1 现有集成测试与部署脚本对 X-API-Key 的依赖面，改造时需同步调整的范围
- [ ] C2 `/admin` 控制台是否还有其他入口依赖 key 的企业内部脚本（改废弃策略前务必确认）
