#!/usr/bin/env bash
# 账号数据导出 / 注销（合规 B2）
#
# 用法：
#   ./scripts/ops/account_erasure.sh export <email>
#   ./scripts/ops/account_erasure.sh erase  <email> --yes
#
# 说明：
# - 连接信息复用项目根 .env（DB_URL / DB_USERNAME / DB_PASSWORD）。
# - export 只输出该用户的账号、License、订单、订阅 JSON；不输出密码哈希 / MFA 密钥。
# - erase 为「匿名化」而非物理删除：保留订单/License 财务记录（政策要求 ≥3 年），
#   抹掉账号 PII、MFA、验证码与设备绑定；管理员账号拒绝由脚本处理。
# - 执行前必须先 export 留档，并确认邮箱已在工单中完成身份核验。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：
  ./scripts/ops/account_erasure.sh export <email>
  ./scripts/ops/account_erasure.sh erase  <email> --yes
EOF
  exit 1
}

mode="${1:-}"
email="${2:-}"
confirm="${3:-}"
[[ "$mode" == "export" || "$mode" == "erase" ]] || usage
[[ -n "$email" ]] || usage
if [[ "$mode" == "erase" && "$confirm" != "--yes" ]]; then
  echo "erase 会匿名化账号，必须显式追加 --yes" >&2
  exit 1
fi

cd "$(dirname "$0")/../.."

[[ -f .env ]] || { echo "未找到 .env（见 .env.example）" >&2; exit 1; }
set -a; . ./.env; set +a
: "${DB_URL:?DB_URL 未设置（见 .env）}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置（见 .env）}"

url="${DB_URL#jdbc:postgresql://}"
hostport="${url%%/*}"
db_and_args="${url#*/}"
db="${db_and_args%%\?*}"
host="${hostport%%:*}"
port="${hostport##*:}"
[[ "$port" == "$host" ]] && port=5432

command -v psql >/dev/null 2>&1 || {
  echo "未找到 psql（PostgreSQL 客户端）；容器部署可在容器内执行本脚本的 SQL。" >&2
  exit 1
}

export PGPASSWORD="$DB_PASSWORD"
psql_base=(psql -h "$host" -p "$port" -U "${DB_USERNAME:-postgres}" -d "$db" -v ON_ERROR_STOP=1 -P pager=off)

if [[ "$mode" == "export" ]]; then
  "${psql_base[@]}" -v email="$email" <<'SQL'
SELECT jsonb_pretty(jsonb_build_object(
  'exportedAt', now(),
  'user', (
    SELECT jsonb_build_object(
      'id', u.id, 'email', u.email, 'status', u.status, 'role', u.role,
      'emailVerified', u.email_verified, 'createdAt', u.created_at, 'lastLoginAt', u.last_login_at)
    FROM users u WHERE lower(u.email) = lower(:'email')
  ),
  'licenses', COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
      'licenseKey', l.license_key, 'status', l.status, 'productId', l.product_id,
      'issuedAt', l.issued_at, 'expiresAt', l.expires_at,
      'machineCode', l.machine_code, 'lastVerifiedAt', l.last_verified_at) ORDER BY l.issued_at)
    FROM licenses l JOIN users u ON u.id = l.customer_id
    WHERE lower(u.email) = lower(:'email')
  ), '[]'::jsonb),
  'orders', COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
      'orderNumber', o.order_number, 'title', o.title, 'amount', o.total_amount,
      'currency', o.currency, 'status', o.status, 'paymentStatus', o.payment_status,
      'createdAt', o.created_at, 'paidAt', o.paid_at) ORDER BY o.created_at)
    FROM orders o JOIN users u ON u.id = o.customer_id
    WHERE lower(u.email) = lower(:'email')
  ), '[]'::jsonb),
  'subscriptions', COALESCE((
    SELECT jsonb_agg(jsonb_build_object(
      'provider', s.provider, 'status', s.status,
      'currentPeriodStart', s.current_period_start, 'currentPeriodEnd', s.current_period_end,
      'cancelAtPeriodEnd', s.cancel_at_period_end, 'createdAt', s.created_at) ORDER BY s.created_at)
    FROM subscriptions s JOIN users u ON u.id = s.customer_id
    WHERE lower(u.email) = lower(:'email')
  ), '[]'::jsonb)
));
SQL
  exit 0
fi

# erase：先解析目标用户并做护栏，再在同一事务内匿名化
target_id="$("${psql_base[@]}" -tA -v email="$email" <<'SQL'
SELECT id FROM users WHERE lower(email) = lower(:'email');
SQL
)"
target_role="$("${psql_base[@]}" -tA -v email="$email" <<'SQL'
SELECT role FROM users WHERE lower(email) = lower(:'email');
SQL
)"

if [[ -z "$target_id" ]]; then
  echo "未找到账号：$email" >&2
  exit 1
fi
if [[ "$target_role" == "ADMIN" ]]; then
  echo "拒绝处理管理员账号（$email）；请先走管理员降权/交接流程。" >&2
  exit 1
fi

echo "==> 将匿名化账号 ${email}（userId=${target_id}）"
echo "==> 订单 / License 财务记录保留，账号 PII、MFA、验证码、设备绑定清除"

"${psql_base[@]}" -v email="$email" -v uid="$target_id" <<'SQL'
BEGIN;

DELETE FROM verification_codes WHERE lower(email) = lower(:'email');
UPDATE checkout_sessions
   SET email = NULL, machine_id = NULL
 WHERE lower(email) = lower(:'email');
UPDATE orders SET machine_code = NULL, updated_at = now() WHERE customer_id = :'uid'::uuid;
UPDATE licenses
   SET machine_code = NULL, signed_token = NULL, updated_at = now()
 WHERE customer_id = :'uid'::uuid;
UPDATE users
   SET email = 'deleted+' || id::text || '@deleted.invalid',
       password_hash = '!',
       status = 'DISABLED',
       email_verified = false,
       token_version = token_version + 1,
       failed_login_count = 0,
       locked_until = NULL,
       last_login_at = NULL,
       mfa_secret_cipher = NULL,
       mfa_enabled = false,
       mfa_enrolled_at = NULL,
       mfa_last_used_step = NULL,
       must_change_password = false,
       updated_at = now()
 WHERE id = :'uid'::uuid;

INSERT INTO audit_logs (id, actor, action, target, success, detail, created_at)
VALUES (gen_random_uuid(), 'ops-script', 'ACCOUNT_ERASURE', :'uid', true,
        'Anonymized account after verified erasure request; orders/licenses retained per policy', now());

COMMIT;
SQL

echo "==> 完成。审计动作：ACCOUNT_ERASURE，target=${target_id}"
