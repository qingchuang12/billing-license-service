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

### 2.5 首个管理员怎么来（决策 B3，已拍板：运维 SQL 直改）
必须在有鉴权的种子机制下产生，**绝不能靠注册接口**。

- **拍板（川哥 2026-09-22）**：采用运维 SQL 直改，不引入受保护引导端点。
- **落地脚本**：`scripts/db/promote-to-admin.sql`（非 Flyway 迁移，运维在目标环境手动执行一次）。
- **前提**：目标账号先经普通注册流程存在（role 默认 USER），再被 `UPDATE` 为 ADMIN。
- **脚本已含**：预检 SELECT → `UPDATE ... SET role='ADMIN', token_version=token_version+1` → 后检 SELECT →
  注释版降级回滚语句。提升时一并 `token_version+1` 以清掉可能残留的 USER 令牌。

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
- [x] A7 首个管理员的初始化手段（运维 SQL 直改，见 `scripts/db/promote-to-admin.sql`）
- [ ] A8 统一 `@Audit` 的操作主体口径（区分 api-key 机器调用 vs 真人 userId）
- [ ] A11 编译/回归验证：本机无 mvn，须经 IDEA MCP 执行 `mvn test`，确认 A1–A5 改动零编译错误
- [x] A12 移除 X-API-Key 通道（B2 已拍板「移除」，2026-09-23 全部落地）：① 删 `ApiKeyAuthFilter.java`；② `SecurityConfig` 去注入/注册/CORS header + 更新注释；③ `application.yml`/`application-test.yml` 删 `admin-api-keys`/`api-key-header`；④ `AuditAspect` 去 X-API-Key 回退、统一 userId（解 A8）；⑤ `OpenApiConfig` scheme 改 Bearer + `requiresApiKey` 改；⑥ `OpenApiCustomizerTest` 断言改 Bearer；⑦ 前端 `/admin` 改 JWT 登录（吸收 A9/A10 最小集）；⑧ `README`/`接口调用时序图`/`上线准备工作` 的 X-API-Key 描述与 curl 示例全改 JWT；⑨ 操作类脚本同步：`docker-compose.yml`/`scripts/deploy/up.sh`/`scripts/deploy/run.sh`/`scripts/README.md` 去除 `ADMIN_API_KEYS` 依赖与 fail-fast 断言，`AccountProperties.java` 注释清理，`架构与业务流程设计.md` 补 A12 历史注记

### 需开发（前端）
- [x] A9/A10 管理控制台登录（**吸收进 A12 最小集**：邮箱密码登录拿 JWT → 存证 → 请求带 `Authorization: Bearer` → 401 跳登录；完整控制台体验完善不再单列）

### 需决策（阻塞，需川哥拍板）
- [ ] B1 `ADMIN` 是否同时授予 `ROLE_USER`（推荐：是，否则管理员无法自助登出/改密）——
      **代码已按推荐项实现**（ADMIN 同时获 ROLE_USER + ROLE_ADMIN，见 `JwtAuthFilter`），待你确认；
      若不认可，删掉其中一个 authority 即可回退。
- [x] B2 X-API-Key 是否保留？**已拍板：移除**（川哥 2026-09-23）。理由：B3 已落地、管理员账号可由运维 SQL 产生，JWT(ADMIN) 已能授 ROLE_ADMIN，X-API-Key 成冗余且更弱的管理员凭证（无法单个停用/改密/降权、审计主体割裂）。落地见 A12。
- [x] B3 首个管理员的产生方式（运维 SQL 直改，川哥 2026-09-22 拍板；脚本见 `scripts/db/promote-to-admin.sql`）
- [ ] B4 管理员是否强制更强口令策略 / 是否要求二次因子（MFA）
- [ ] B5 管理员能否使用消费侧能力（查看/购买、我的许可证），还是严格限定只进管理端

### 待核实
- [x] C1 现有集成测试与部署脚本对 X-API-Key 的依赖面，改造时需同步调整的范围（已全量排查并清除：`docker-compose.yml`/`scripts/deploy/{up,run}.sh`/`scripts/README.md` 的 `ADMIN_API_KEYS` 依赖与 fail-fast 断言已移除；仓库内无残留操作脚本依赖该变量）
- [ ] C2 `/admin` 控制台是否还有其他入口依赖 key 的企业内部脚本（改废弃策略前务必确认；本仓库内已无，外部脚本不在此范围）
