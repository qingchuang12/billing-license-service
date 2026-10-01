# plan-7.0 · 剩余收口（实机验证与外部待办）

> 本仓**唯一活动 plan**，只承载未完成项；已完成内容与决策叙述不留存（历史结论查 `git log -- docs/`）。
> 权威口径落点：契约与实现现状 → `README.md`（API 总览 / 凭证激活 / 二次因子）、`接口调用时序图.md`（42 个端点 + 横切机制）；上线执行与验收 → `上线准备工作.md`（§0 Go/No-Go、§二 渠道与证书、§3.1 必填变量、§五 安全加固、§6.6 回滚、§6.7 静态页缓存版本号、§七 验收清单 A/B/C/D/E、§九 风险登记）；授权域「明确不做」与红线 → `授权设计方案.md` §十八；管理台 / MFA / 退款范围边界 → `架构与业务流程设计.md`「附：范围边界定案」；客户端复核节奏与红线 → `ai-tools/doc/license-recheck-design.md`（§0.4 现行口径、§1.5 时钟回拨、§8 共享知识）。**测试数据与清理 SQL 全在 `D:\ProductSpace\.e1b-ledger.md`。**

## TODOS（仅未完成）

- [ ] **【需你决策】SEC-4 · 是否把「未开 MFA」提示式横幅升级为拦截式**：现状实测＝管理台检测 ADMIN 未开 MFA → `#mfaBanner` 引导绑定（`static/admin/index.html:121`，逻辑 `admin.js:896-898`，契约级 `AdminStaticPageContractTest`），`security/` 下**没有任何**按 MFA 状态拦截的过滤器（只有 `TraceIdFilter` / `JwtAuthFilter` / `MustChangePasswordFilter`）。
  - 选项①**保持提示式**：零新代码；代价＝管理员可长期不带第二因子。
  - 选项②**仿 `MustChangePasswordFilter` 做拦截式**：未开 MFA 的 ADMIN 仅放行 MFA 绑定 / 改密 / 登出；更安全，但**唯一管理员在 TOTP 不可用且邮箱兜底被关闭时会自锁**，须同时定死应急出口（运维手动跑 `scripts/db/reset-admin-mfa.sql`）。
  - **推荐①**——与已定案的「不做管理员口令加严」同一取舍尺度，且邮箱兜底已覆盖认证器丢失/换机场景（其强度档次差异已在 `README.md`「二次因子」标明，不得当作等同 TOTP）。
- [ ] **【需你处理】E1 · 真人付款腿（唯一未跑的功能腿）** 真人扫支付宝沙箱码 → 触发 `fulfillOrder` 直签 + 两封真邮件（支付通知 / 含 licenseKey）+ 客户端 60s 到账轮询循环本身（`startPurchasePolling` 首 tick 固定 `PURCHASE_POLL_INTERVAL_MS=60s`、窗口 30 分钟，`purchase-poll.ts:58-63` + `constants.ts:107-108`；`pollOnce` 私有不可注入）。**⚠️ 须重新下单**：原备单 `ORD-1790691168309-616DF8FE`（pro-buyout ¥712.80，`machine_code=5E01-7EB8-3661-E06A`）二维码会话已于 2026-09-30 00:12 过期。其余三条腿已实跑通过：服务端 curl 腿（V9/V10/V11 真库执行、pending 命中与一次性消费、`nextCheckAfterMs=1296000000`、换机 400 `MACHINE_MISMATCH`、同机幂等、解绑后再激活 200、频控第 46 次起 429 空响应体）、客户端真模块腿（真 Ed25519 验签 + 到账 + 31 天提醒态功能不减 + 61 天且断网才停用 + 复网自愈）、邮件腿（本机 SMTP sink 实收验证码邮件）。
- [ ] **【需你处理】E3 · 实机走查合批（与 SEC-0 同一趟）** 一次跑完 `上线准备工作.md` §七 的 **A9**（跨仓登录后自动到账，客户端代码已在 `ai-tools` 落地：`account.ts` + `redeem.ts` 列表拉取 + `claim.ts`）、**A10** 自助解绑、**A11/A12** MFA 全流程与反绕过、**A13** License 处置、**B1/B4** 真实小额与退款吊销、**B7** 渠道部分退款、**B8** Paddle 续费 payload、**E 段 1–5** 管理台与账户走查 + 报错文案 GUI 复测。
- [ ] **【需你处理】SEC-0 · 2026-09-25 admin.js hotfix 收尾** 剩余动作＝用管理员真实凭据 + 动态码走一遍完整登录（并入上条 E 段），完成后由你随本批 SEC 一起提交。**更正**：旧文本写「已 bump `?v=20260925-2`」，该值在两仓代码中**不存在**；admin 页实测为 `?v=20260930-1`（`static/admin/index.html:17,563`），account 页 `?v=20260925-1`（`static/account/index.html:16,284`），后续改动须按 `上线准备工作.md` §6.7 逐页 bump。
- [ ] **【需你处理】F4 · 仓库卫生** 本仓 commit `be3631e` 的 message 是一段 AI 生成的英文自述（「Based on the diff…Here's a commit message following your conventions:」+ 结尾「如果你希望把类型改为 refactor…」），不符仓库中文约定；实测 `git show --stat be3631e` ＝ 6 文件 +114/−19（时序图文档、`License.java`、`LicenseRepository`/`LicenseService`、`V11__*.sql`、`LicenseServiceTest`），且**已在 `origin/main`**（`git branch -r --contains be3631e`）→ `--amend` 属改写已发布历史，**不在建议范围**；推荐后续提交规范起 message 让历史自明，坚持改写须 force-push 并由你明示。ai-tools 侧同类问题见 `ai-tools/doc/plan-4.1.md`「仓库卫生」。
- [ ] **【需你处理】支付宝公钥证书重下** `keys/alipay/alipayPublicCert.crt` 叶子证书 2026-07-29、同文件中间 CA 2024-08-01 **已过期**（`openssl x509 -enddate` 实测）；SDK 验签不校验有效期故**不会报错**，须到开放平台重下 `alipayCertPublicKey_RSA2.crt` 覆盖。完整有效期清单与位置见 `上线准备工作.md` §2.2.1。
