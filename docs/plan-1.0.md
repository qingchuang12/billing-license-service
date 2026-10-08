# plan-1.0 · 修复 Jackson 缺类导致启动失败

> 本文件只登记**未完成任务**。完成即删条目、不留痕迹；全绿即删文件。
> 自检：回复里出现「待决策 / 遗留 / 暂不处理 / 已知降级」，本文件必须有对应条目。

## TODOS

### ③【需你处理】AI 无法代做，须你在自己环境执行

- [ ] **IDEA 联网 build+run 验证启动恢复**：在 IDEA 执行应用启动（或 `mvn spring-boot:run` / 打包运行），确认不再报 `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonApplyView`、Flyway 迁移与 `entityManagerFactory` 正常初始化、应用成功起来。核销：启动日志无该 `NoClassDefFoundError` 且出现 Flyway 迁移记录 / Tomcat 监听端口。
  - 已修正（`jackson-annotations` 钉到 `2.22`）：`jackson-annotations` 2.x 线**最高仅发布到 `2.22`（无 `2.22.3`、也无 `2.23.x`）**，`2.22` 已联网核验（阿里云 200 可拉取，jar 内含 `com/fasterxml/jackson/annotation/JsonApplyView.class`）。原先钉 `2.22.3` 因版本不存在导致「未解析的依赖项」已消除。`2.22.3` 钉法废弃。
  - 若仍报同类 `NoClassDefFoundError`：根因不在 annotations 版本，而在手动钉的 3.x `jackson-databind:3.2.3` 对 `JsonApplyView` 的硬引用（本地字节码核验 3.2.3 在 `JacksonAnnotationIntrospector`/`BeanPropertyWriter` 引用该类，而 SB4 托管的 3.1.5 不引用）。兜底：去掉 `tools.jackson.core:jackson-databind`/`jackson-core` 两个 `3.2.3` 钉，回退 SB4 托管 `3.1.5`。

## 硬约束（勿回头改）

1. 2.x Jackson 三件套按 minor `2.22` 对齐：databind/core 钉 `2.22.3`、annotations 钉 `2.22`（annotations 2.x 线无 patch，最高仅 `2.22`）。**不得把 annotations 钉成 `2.22.3`/`2.23.x`（仓库不存在）**。
2. 3.x Jackson（`tools.jackson.core`）与 2.x Jackson（`com.fasterxml.jackson.core`）在本项目并存是既定结构，勿为修此问题整体替换其中一套。
3. `jackson-annotations` 必须在 `<dependencies>` 显式声明（无 version），版本由 `dependencyManagement` 统一钉 `2.22`，确保进 runtime classpath（3.x databind 3.2.3 初始化期需 `com.fasterxml.jackson.annotation.JsonApplyView`）。

## 暂不做（勿回头扩范围）

微信/支付宝/Stripe/Paddle/PayPal 渠道 SDK · KMS 接入 · 账号登录子系统（account login）

## 验证命令

- 依赖树核对（确认 annotations 解析到 2.22）：`mvn dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-annotations`
- 启动：IDEA 运行 `BillingLicenseApplication` 主类，或 `mvn spring-boot:run`
- ⚠️ 本地无 mvn，构建统一走 IDEA MCP；校验类命令禁止接管道。
