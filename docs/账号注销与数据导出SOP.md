# 账号注销与数据导出 SOP（billing）

> 适用：收到用户「导出我的数据 / 注销账号」请求时的运维处置。
> 政策依据：`src/main/resources/static/account/legal/privacy.html` §6 / §8。
> 当前没有用户自助注销/导出端点，按本 SOP 由运维执行脚本；脚本落点 `scripts/ops/account_erasure.sh`。

## 一、身份核验（先做，不做跳过）

1. 用户须从**注册邮箱**发来请求，并至少提供以下两项之一：
   - 任一订单号（`order_number`）；
   - 任一 License 密钥（`license_key`）或兑换码。
2. 核对请求邮箱与库内 `users.email` 一致；不一致时要求补充证明，不得直接处理。
3. 管理员账号不适用本 SOP：先走管理员降权/交接，再由脚本拒绝处理。

## 二、导出（先留档再注销）

```bash
./scripts/ops/account_erasure.sh export <注册邮箱>
```

- 输出为 JSON：账号资料、License、订单、订阅；**不含**密码哈希、MFA 密钥、验证码。
- 将输出保存为工单附件，交付用户时使用加密压缩包或临时下载链接，禁止明文邮件正文直发。
- 导出后核对：`user` 非 null；License/订单数量与用户描述一致；没有把其他用户数据带出。

## 三、注销（匿名化）

前置：

- 已完成身份核验与导出留档；
- 已确认目标不是管理员；
- 已按上线手册完成数据库备份（`scripts/db/backup.sh`）。

执行：

```bash
./scripts/ops/account_erasure.sh erase <注册邮箱> --yes
```

脚本在同一事务内：

| 数据 | 处置 |
|---|---|
| `users.email` | 改为 `deleted+<userId>@deleted.invalid` |
| 密码 / MFA / 令牌版本 | 密码置不可登录值；清 MFA 密钥与启用状态；`token_version+1` 踢下线 |
| `verification_codes` | 按邮箱删除 |
| `checkout_sessions` | 清空邮箱与机器码 |
| `orders.machine_code` / `licenses.machine_code` / `signed_token` | 清空设备绑定与签名令牌 |
| `orders` / `licenses` / 订阅 / 资金账 | **保留**，用于财务与法定留存（订单 ≥3 年） |
| `audit_logs` | 写入 `ACCOUNT_ERASURE` 审计行 |

## 四、完成后的核销

```sql
SELECT id, email, status, token_version FROM users WHERE id = '<userId>';
SELECT count(*) FROM audit_logs WHERE action = 'ACCOUNT_ERASURE' AND target = '<userId>';
```

- 账号邮箱已变为 `deleted+...`、状态 `DISABLED`；
- 原邮箱不再能登录/找回；
- 审计行存在且 `success=true`；
- 在工单中记录：请求时间、核验方式、导出文件名、执行时间、操作人、审计行 id。

## 五、边界

- 不物理删除订单、License、资金流水（政策要求保留）；如用户要求「全部删除」，按政策解释法定留存例外。
- 注销后用户如需恢复购买记录，只能凭订单号走客服核验，不恢复账号登录。
- 脚本输出含敏感数据，终端日志与工单附件按内部敏感信息管理，保留期不超过客服沟通 180 天。
