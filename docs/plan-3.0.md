# plan v3.0 · 上线验证 + 授权硬化（合并活动 plan）

> 版本：v3.13（2026-09-20）· 唯一活动 plan。仓库内可改项已收口；剩余为决策 / 外部资源 / 跨仓任务。K8-billing 与 K16 已落地（见「已完成」）。
> **v3.13 追加（2026-09-20）**：N1-N5「错误提示回传修复」——用户报 `/account/`「设置密码并登录」恒报「操作失败，请稍后重试」。已确认根因（见「已完成」N1）。决策：**新邮箱不允许单独建号**，只有购买时由系统帮用户建号。
> **v3.12 追加（2026-09-19）**：新增 U1-U3「用户密钥自助管理」（已确认：JWT 登录视角 / 证书+订阅+订单 / API+简单静态页）。
> 当前状态：K8 支付页按新要求**迁入本服务**（与 API 同源），官网仅保留购买入口链接（交易页由本服务 `/checkout/` 托管，详见 `上线准备工作.md`）。
> **2026-09-19 追记**：客户端侧激活联调完成（见「已完成」S1）；支付渠道（select-provider 的支付宝 INTERNAL_ERROR）**用户指示暂不处理**；`/checkout/**` 静态页放行已改 `SecurityConfig`，**待服务重启生效**。

## 范围
授权硬化 + 上线验证闸门 + 内嵌支付页托管。设计依据见专项文档（`授权设计方案.md` / `上线准备工作.md`）。

## Flyway 纪律
迁移文件一经提交即受 checksum 保护，不得再改文件；需变更请新增版本（当前应为 V12）。迁移由 Flyway 在上线发布时自动执行，不是 task。

## TODOS（仅未完成）

- [ ] **N2 服务端：异常响应不再被二次包壳（`exception/GlobalExceptionHandler.java` + `dto/ApiResponse.java`）**
      现状：全局异常处理器返回的是自拼 Map，被 `ApiResponseAdvice` 再包一层「成功壳」，真提示沉到 `data.message`，HTTP 状态虽是 400 但外层 `success=true`，第三方对接也会被误导。
      做法：`ApiResponse` 补带 traceId 的 `fail(...)`；各 handler 改返回 `ResponseEntity<ApiResponse<Void>>`。`ApiResponseAdvice.beforeBodyWrite` 对 `body instanceof ApiResponse` 原样放行，不依赖 `supports()` 里那条已失效的包名判断。
- [ ] **N3 鉴权：401 / 403 补统一 JSON 响应体（`security/` + `SecurityConfig`）**
      现状：`anyRequest().denyAll()` 与无令牌访问受保护端点都返回空 body，客户端取不到任何提示。
      做法：新增 authenticationEntryPoint（401）与 accessDeniedHandler（403），写 `ApiResponse.fail` JSON，结构与其他端点一致。
- [ ] **N4 前端：错误提示兜底（`static/account/account.js` + `static/checkout/checkout.js`）**
      做法：`buildApiError` 兼容 `payload.code || payload.errorCode`，message 兜底到内层 `payload.data.message`（防重演今日这类「结构变了就只报通用文案」）；401 单独给「登录状态已过期，请重新登录」。两页同改，口径一致。
- [ ] **N5 文案与口径（后端 + 页面双语文案）**
      已确认决策：**新邮箱不允许单独建号**，只有购买 / 兑换时由系统建号。
      做法：`AccountService#resetPassword` 校验通过但查无此账户时，从伪装成 `CODE_INVALID` 改为明确错误码（如 `EMAIL_NOT_PURCHASED`）+ 文案「该邮箱名下暂无购买记录…」；`/account/` 页面去掉「未注册也能设置密码」的承诺（zh/en 字典与 index.html 静态兜底文案同步改）。
- [ ] **N6 验证**：`mvn -o -B test` 全绿（新增契约测试：错误响应不得双层包裹、邮箱无购买记录的码）；静态资源同步 `target/classes` 后浏览器复测一次真实报错文案。
- [ ] **K5 `update_until` / `max_major_version` 客户端不拦截（P2，待产品决策）**：客户端已解析两 claim 但未据此拦截——「硬阻断版本超范围」vs「仅作更新门控（updater）」，影响付费用户，需拍板。
- [ ] **C5/C7 客户端离线 / 在线复核（跨仓，待产品决策）**：订阅到期 / 退款吊销延迟生效 vs 补在线复核端点，需拍板。
- [ ] **C9 收银台读取产品 / 档位入口参数（跨仓，官网侧已就绪）**：官网产品区已按 `?product=ai-tools&productId=<sku>` 生成购买链接（SKU 与 `products.sku` 一致），但收银台页 `static/checkout/checkout.js` 的 `getQueryParams()` 目前仅消费 `machineId` / `checkoutId`，`init()`（约 L1704-1733）未读取产品参数 → 用户点档位后仍须在页内重新选档，"买哪个产品/档位"未被承接。需后端读取并预选：按 `item.sku === params.productId` 命中后写入 `state.product` 再 `renderPlanCards()`。多产品上线前必须补齐。

## 已完成（本 plan 收口）

- **N1 错误提示全被吞的根因定位（2026-09-20，用户报 `/account/` 点「设置密码并登录」恒报「操作失败，请稍后重试」）**：
  - **根因**：全局异常处理器 `GlobalExceptionHandler` 返回自拼 Map，被统一响应壳 `ApiResponseAdvice` **再包一层成功壳** —— HTTP 状态仍是 400，但响应体是 `{success:true, code:"SUCCESS", data:{success:false, errorCode:"CODE_INVALID", message:"验证码无效或已被使用"}}`。前端 `account.js` 只读顶层 `payload.message`（NON_NULL 下为 null）→ 取空 → 退回兜底文案 `操作失败，请稍后重试`。
  - **已排除的猜测**：不是限流、不是 CSRF（`csrf.disable()`）、不是 SMTP；`@ExceptionHandler` 所在包名 `com.billing.license.exception` 含 `.exception.`，`ApiResponseAdvice.supports()` 里那条「异常包不包裹」的判断在 Spring Boot 4 下**对异常返回值不生效**（实测证据确凿），不能依赖它。
  - **影响面（实测）**：所有 `GlobalExceptionHandler` 产出的错误（业务异常 / 参数校验 / 畸形 JSON）全部失真；此外 401/403/未知路径返回**空 body**，同样只能落到兜底文案。受影响的调用方不止本页（`checkout.js` 同写法）。
  - **附带发现**：该按钮对「从未购买过的新邮箱」始终失败——`AccountService#resetPassword` 查无此账户直接抛 `CODE_INVALID`，与页面「未注册也能设置密码」的承诺相反（决策见 N5）。
  - **副产物**：`/actuator/health` 与 liveness/readiness 探针实测均 HTTP 200，健康。

- **U1-U3 用户密钥自助管理（2026-09-19，已确认范围：JWT 登录视角 / 证书+订阅+订单 / API+简单静态页）**：
  - **U1 API**：`GET /api/account/licenses|subscriptions|orders`（ROLE_USER，复用 `/api/account/**` 保护区，SecurityConfig 接口侧零改动）。新增 `AccountAssetService` + `AccountAssetController`（同挂 `/api/account` 前缀）；`CurrentUserResolver` 从 AccountController 提取共用；仓储新增三个按 customerId 时间倒序派生查询；新 DTO `SubscriptionView`（产品 SKU/名称批量解析防 N+1）；`LicenseResponse` 加 `machineCode`（加法改动，adminView 同步回填）。脱敏口径：licenseKey 完整回显、signedToken 不外发。
  - **U2 静态页**：`/account/`（static/account/ 三件套），登录 + 找回/设密码认领（验证码 RESET_PASSWORD）+ 三区块 + 复制密钥 + 登出 + 中英双语（共用 `yaning-lang` 语言键）；样式复用 `../checkout/checkout.css` 设计体系；`SecurityConfig` 放行 `/account/**` 静态资产。
  - **附带修复（已定位根因）**：Spring Boot 3 不再解析子目录 `index.html` 欢迎页——干净启动下 `GET /checkout/` 与 `GET /account/` 均 500（`NoResourceFoundException`→GlobalExceptionHandler），而 `/checkout/index.html`=200。新增 `WebMvcConfig` 显式 forward 两页的目录路径（含尾斜杠），冒烟实测两页均 200。**随下次重启对用户实例生效**。
  - **验证**：`mvn test` 194 全绿（新增 4 用例）；H2 冒烟实例端到端走通 注册→管理端发码→兑换出证→三端点查询（licenseKey/machineCode/customerEmail 正确、signedToken=null）；浏览器实测页面渲染（截图）、登录、三区块列表（含空态）、双语切换、登出、旧令牌自动回登录态。复制按钮动态文案随语言刷新的修复（按钮加 `data-i18n="common.copy"`）因 IAB 自动化桥中途故障未做端到端复测，机制与已验证的静态节点切换一致，待下次人工过一遍即可。

- **S1 激活接口端到端调通（2026-09-19，客户端 ai-tools ↔ 本服务）**：`POST /api/redeem/redeem` 运行时 `INTERNAL_ERROR` 已定位并修复——根因是 `LocalKmsService` 懒加载签名密钥，yml 缺省路径 `/keys/private.key` 是 **docker 容器路径**，Windows 本机直启解析为**当前盘符根** `D:\keys\`，该目录不存在 → 首次签发（兑换/收款）必然抛「本地密钥加载失败」；加载失败不缓存，故**无需重启**。处置：① 把 `keys/private.key`、`keys/public.key` 副本放到 `D:\keys\`（运行中实例下一次兑换即恢复）② `.env` 追加 `PRIVATE_KEY_PATH`/`PUBLIC_KEY_PATH` 指向仓库 keys（供 .env 生效的启动方式）。全链路实测：管理端发码（`/api/admin/redeem-codes/generate`，X-API-Key=yml 默认值可用）→ 兑换（code JC3Y-MXZV-N9ER-CPQX + 机器码 5E01-7EB8-3661-E06A）→ 签发 3 段 token → 按客户端 verifier 同款算法（Ed25519 over header.payload）**验签通过**，claims 正确（sku=pro-buyout、mid=本机机器码、lic=AE46-7546-70E6-A668、exp=10 年）；`/api/admin/licenses` 落库 ACTIVE。密钥配对独立复核：私钥 seed 派生公钥 == 客户端内置公钥 `73a23b…437`（三处一致：服务端 keys/、客户端 src/public.key、dist 镜像、编译期兜底常量）。
- **S1 附带诊断（2026-09-19 晚）与更正：用户复报「当前地区暂无可用支付方式」——此前「旧标签页缓存旧脚本」的判断**错误**，真实根因**：收银台会话快照存 **localStorage**（跨标签页存活），用户首次成功 create 后快照一直带着 `checkoutId`（status=CREATED）；此后每次打开收银台都走 `restoreCheckout()` → `GET /status` → **状态接口从不返回 `paymentMethods`**（页面端本有消费该字段的恢复逻辑）→ 恢复出的 CREATED 会话拿空列表 → 死路报错。且快照在 localStorage，**刷新无法自愈**，故反复出现。
  - **修复一（页面端，已同步 target 免重启生效）**：restore 拿到 CREATED 且无支付方式时不再报错，改为 `clearSession() + resetToForm()` 回退下单表单（档位选中态/邮箱/机器码均保留，可直接重新提交）；另在 checkout.js 顶部加构建标记（console.info）便于排查缓存脚本。
  - **修复二（服务端，待重启生效）**：`CheckoutService.getStatus` 对 CREATED 会话按 `session.country`（CN→国内渠道，其余→国际）补返 `paymentMethods`（与 create 同口径），刷新/回跳可真正续选支付方式。`mvn test` 全绿。
  - 教训：修前只在「无历史快照」的新环境验证，漏了 localStorage 快照路径；本次已用带快照的浏览器复现并验证修复。
  - 支付环节遗留（搁置中）：点支付宝 → select-provider INTERNAL_ERROR；probable 根因 `.env` 有完整支付宝沙箱配置（APP_ID/密钥对/沙箱网关均非空）但运行实例未加载 .env（同签名密钥类问题，.env 可能因多行值整体解析失败被 optional 跳过）——处理支付时让实例真正加载 .env（IDEA EnvFile 或修正格式）。
- **S1 附带修复：定时清理任务缺事务（2026-09-19，用户报启动日志 ERROR）**：`CheckoutService.cleanupExpiredSessions`（M2，每小时清理过期未支付会话）调用派生删除 `deleteByStatusNotAndExpiresAtBefore`，JPA `remove` 要求活动事务，而定时入口未标 `@Transactional` → 每次调度抛 `TransactionRequiredException`，清理从未生效。已加 `@Transactional`（全工程仅此一个 @Scheduled 任务），`mvn test` 全绿；**随下次重启生效**（当前实例每小时仍会刷一条该 ERROR，无功能影响）。
- **S1 附带修复：收银台静态页 401**：`SecurityConfig` 的 permitAll 有 `/api/checkout/**` 但漏了静态页路径 `/checkout/**`，被 `.anyRequest().denyAll()` 拦成 401 空响应——客户端「在线激活」跳转落地页打不开（用户报障「接口没开发好」的实际现象之一）。已在 `SecurityConfig.java` 补 `"/checkout/**"` permitAll，`mvn test` 全绿；**用户重启后已生效**（页面已能打开）。
- **S1 附带修复：收银台页 API 层未剥响应壳（2026-09-19）**：`static/checkout/checkout.js` 的 `requestJson` 直接把服务端统一壳 `{success, code, data, …}` 返回给上层，全文件 0 处剥 `$.data`——与 2026-09-18 客户端激活契约问题同源（页面按扁平业务对象书写）。实测复现：页面打开正常（`/checkout/**` 放行生效后）但档位区显示「产品信息加载失败」——`fetchProducts` 对壳对象 `Array.isArray` 为 false → 空列表 → 主动 reject；同病还有轮询读 `$.status`（永远等不到 PAID）与创建会话读 `$.checkoutId`。修复：`requestJson` 单点剥壳（`$.data` 为对象/数组时返回之，兼容扁平结构；壳内 `data.success === false` 归一为 API 错误抛给既有 catch），已同步到 `target/classes` **免重启生效**，浏览器实测四个档位全部正常渲染、机器码正确绑定。支付流程（select-provider INTERNAL_ERROR）仍按用户指示搁置。
- **S1 本地配置隐患（待用户处置）**：项目根 `.env`（gitignored）中 `DB_PASSWORD` / `ADMIN_API_KEYS` / `MAIL_PASSWORD` 为**空值**；当前运行实例未导入 .env（admin 默认键可用证明），一旦某次启动实际导入了 .env，空值将压过 yml 默认导致 fail-fast 拒启。建议把真实本地值填进 .env 或 IDEA 运行配置。

- **K8-billing 内嵌支付页（同源托管）**：收银台页面迁移至本服务 `src/main/resources/static/checkout/`，对外 `/checkout/`，与 `/api` 同源（免 CORS）。落实三决策：① 区域跟随 UI 语言（zh→CNY+支付宝/微信，en→USD+Stripe/PayPal）② 客服邮箱统一 `service@ywhome.top` ③ 价格改由 K16 端点 `GET /api/products` 取（不再硬编码）。官网 `mian/` 已删除误落的 `getlicense/` 交易文件；购买入口改为「导航『购买授权』→ 站内产品区 `#pricing` → 档位按钮携 `?product=ai-tools&productId=<sku>` 跳收银台」，跳转地址集中在 `mian/assets/js/site-config.js`（2026-09-19 更新，原为两处裸跳收银台不带产品标识）；`上线准备工作.md` §0/§2.5/§6.5/§8/§10-6 已改为「后端内嵌」口径。验收：静态页自包含（内联 design tokens，无 mian/style.css 依赖）、i18n 双语、三决策已落地；端到端支付走通需真实渠道密钥（见手册 T1）。
- **K16 公开产品目录端点 `GET /api/products`（决策 B）**：permitAll 端点返回 SKU / 名称 / 双档价格 / 档位 / 周期 / 权益；页面 fetch 调用消除硬编码价格漂移。`ProductRepository.findByActiveTrue` + `ProductPublicDto` + `SecurityConfig` 放行 + 单测已落地，`mvn test` 190 全绿（IDEA MCP，其中 2 个新增用例覆盖 K16）。

## 已纳入上线准备手册（不展开）

| 项 | 手册章节 | 阻塞 |
|---|---|---|
| T1 全渠道真实联调 | §10-7 | 渠道测试密钥 + 公网回调 |
| T2 性能压测 | §10-7 | 真实环境 |
| T3 客户端 exe 验签 | §10-7 | 客户端发版 |
| T4 渗透测试 | §10-7 | 公网环境 |
| L2 生产 SMTP | §10-4 | 生产邮箱凭据 |
