/* ==========================================================================
   晏宁科技 · 我的授权页脚本（后端内嵌 /account/，U2）
   结构与惯用法与 checkout.js 保持一致：
   1. i18n（data-i18n + 字典 + localStorage，共用同一语言键 yaning-lang）
   2. requestJson 单点剥统一响应壳 {success, code, data, ...}（业务字段在 $.data）
   3. 登录 / 找回（访客认领）/ 登出 → JWT 存 localStorage
   4. 三个资产区块（License / 订阅 / 订单）加载与渲染
   无外部依赖；无脚本环境下内容由 index.html 兜底可见。
   ========================================================================== */
(function () {
  'use strict';

  /* ======================= 1. 常量与状态 ======================= */

  // 与 checkout.js 共用同一语言键：整站语言偏好一致
  var LANG_KEY = 'yaning-lang';
  var TOKEN_KEY = 'yaning-account-token';
  var EMAIL_KEY = 'yaning-account-email';
  var SEND_CODE_COOLDOWN_MS = 60 * 1000;

  var state = {
    lang: 'zh',
    token: '',
    email: '',
    sendCodeReadyAt: 0,
    regCodeReadyAt: 0,
    // plan-4.1：订单区退款入口所需的缓存（语言切换时按缓存重渲染，保住插值文案）
    orders: [],
    refundOrder: null,
    // plan-7.0：许可证区解绑入口所需的缓存（同上）
    licenses: [],
    unbindLicense: null,
    // 订阅缓存：取消弹层据此显示产品与周期；语言切换时重渲染
    subscriptions: [],
    // 取消订阅弹层当前操作的订阅 id（A2 自助取消）
    cancelSubId: null,
    // plan-7.0 账户基础功能 / Q3：管理员代重置后须先改密，此时隐藏资产区块只留改密入口
    mustChangePassword: false,
    // 两阶段登录（plan-7.0 / M3）：密码已过、待第二因子时持有的一次性票据。
    // 只存内存（刷新即失效），与找回表单的 localStorage 令牌不同源。
    mfaTicket: '',
    // 第二因子可用方式缓存：语言切换时据此重写 mfaStepDesc（含邮箱兜底可用性）
    mfaMethods: null,
    mfaEmailReadyAt: 0,
    // P15：资产分区当前 Tab（license / sub / order / security）
    assetTab: 'license'
  };

  function $(id) { return document.getElementById(id); }
  function queryAll(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /* ======================= 1.1 动态码分格（P14） ======================= */

  /** 6 格动态码输入：自动前进 / 退格回跳 / 粘贴分发 / 方向键移动；同步到隐藏输入供表单读取 */
  function initOtp(boxId, hiddenId, onComplete) {
    var box = $(boxId);
    var hidden = $(hiddenId);
    if (!box || !hidden) return { clear: function () {}, focus: function () {} };
    var inputs = queryAll('.otp__inp', box);
    function sync() {
      hidden.value = inputs.map(function (input) { return input.value; }).join('');
    }
    function complete() {
      sync();
      if (hidden.value.length === 6) onComplete();
    }
    inputs.forEach(function (input, index) {
      input.addEventListener('input', function () {
        input.value = String(input.value || '').replace(/\D/g, '').slice(0, 1);
        if (input.value && inputs[index + 1]) inputs[index + 1].focus();
        complete();
      });
      input.addEventListener('keydown', function (event) {
        if (event.key === 'Backspace' && !input.value && inputs[index - 1]) {
          event.preventDefault();
          inputs[index - 1].focus();
          inputs[index - 1].value = '';
          sync();
        } else if (event.key === 'ArrowLeft' && inputs[index - 1]) {
          event.preventDefault();
          inputs[index - 1].focus();
        } else if (event.key === 'ArrowRight' && inputs[index + 1]) {
          event.preventDefault();
          inputs[index + 1].focus();
        }
      });
      input.addEventListener('paste', function (event) {
        var text = (event.clipboardData || window.clipboardData).getData('text') || '';
        var digits = text.replace(/\D/g, '').slice(0, 6);
        if (!digits) return;
        event.preventDefault();
        inputs.forEach(function (boxInput, boxIndex) {
          boxInput.value = digits.charAt(boxIndex) || '';
        });
        var last = Math.min(digits.length, inputs.length) - 1;
        if (last >= 0) inputs[last].focus();
        complete();
      });
    });
    return {
      clear: function () {
        inputs.forEach(function (input) { input.value = ''; });
        hidden.value = '';
      },
      focus: function () {
        if (inputs[0]) inputs[0].focus();
      }
    };
  }

  var mfaOtp = { clear: function () {}, focus: function () {} };

  /* ======================= 2. i18n ======================= */

  var I18N = {
    zh: {
      'a11y.skip': '跳到主要内容',
      'brand.name': '晏宁科技',
      'nav.home': '返回首页',
      'account.eyebrow': '我的授权',
      'account.title': '我的授权管理',
      'account.sub': '查看购买/兑换时所用邮箱名下的许可证、订阅与订单。',
      'auth.loginTitle': '登录查看我的授权',
      'auth.loginDesc': '使用购买或兑换时填写的邮箱登录；当时未注册的邮箱，可用「设置密码」认领账户。',
      'auth.step1': '第 1 步 · 输入密码',
      'auth.step2': '第 2 步 · 二次验证',
      'auth.emailLabel': '电子邮箱',
      'auth.passwordLabel': '密码',
      'auth.login': '登录',
      'legal.consentPrefix': '我已阅读并同意',
      'legal.privacy': '《隐私政策》',
      'legal.and': '与',
      'legal.terms': '《服务条款》',
      'legal.consentRequired': '请先阅读并勾选同意《隐私政策》与《服务条款》。',
      'auth.loginBusy': '登录中…',
      'auth.toReset': '忘记密码？用邮箱验证码重新设置',
      'auth.toRegister': '还没有账号？注册一个',
      'auth.regPwdHint': '8–72 位，须同时包含字母与数字。',
      'auth.regConfirmLabel': '确认密码',
      'reg.desc': '用邮箱注册一个账号，即可在这里查看名下的许可证、订阅与订单。注册成功后自动登录。',
      'reg.submit': '注册并登录',
      'reg.busy': '注册中…',
      'reg.mismatch': '两次输入的密码不一致。',
      'reg.pwdPolicy': '密码须为 8–72 位，且同时包含字母与数字。',
      'reg.ok': '注册成功，已登录。',
      'reg.codeSent': '验证码已发送，请查收邮箱。',
      'auth.codeLabel': '邮箱验证码',
      'auth.sendCode': '发送验证码',
      'auth.sendCodeBusy': '发送中…',
      'auth.sendCodeCountdown': '{s} 秒后重发',
      'auth.newPasswordLabel': '设置新密码',
      'auth.reset': '设置密码并登录',
      'auth.resetBusy': '提交中…',
      'auth.toLogin': '返回登录',
      'auth.logout': '退出登录',
      'auth.codeSent': '验证码已发送，请查收邮箱（若未配置邮件服务则不可达）。',
      'auth.resetOk': '密码已设置，正在登录…',
      'mfa.codeLabel': '动态码（或邮箱验证码）',
      'mfa.sendEmail': '发送邮箱验证码',
      'mfa.sendEmailBusy': '发送中…',
      'mfa.sendEmailCountdown': '{s} 秒后重发',
      'mfa.verify': '验证并登录',
      'mfa.verifyBusy': '验证中…',
      'mfa.autoHint': '输满 6 位将自动验证',
      'mfa.back': '返回重新输入密码',
      'mfa.descWithFallback': '密码已通过。请输入认证器 App 显示的 6 位动态码；认证器不可用时可点击「发送邮箱验证码」改用邮箱码。',
      'mfa.descTotpOnly': '密码已通过。请输入认证器 App 显示的 6 位动态码。',
      'mfa.codeSent': '验证码已发送至你的邮箱，10 分钟内有效。',
      'mfa.ticketLost': '登录会话已失效，请重新输入密码。',
      'err.mfaCodeInvalid': '动态码或邮箱验证码无效，请重试',
      'err.mfaTicketInvalid': '登录会话已失效，请重新登录',
      'err.mfaVerifyLimit': '尝试过于频繁，请稍后再试',
      'err.mfaEmailFallbackDisabled': '未开放邮箱验证码兜底，请使用认证器动态码',
      'err.codeSendTooFrequent': '验证码发送过于频繁，请稍后再试',
      'chpwd.title': '修改密码',
      'chpwd.desc': '修改成功后所有已登录会话将失效，需用新密码重新登录。',
      'chpwd.forceNote': '管理员已重置你的密码：请先设置新密码，才能继续使用其他功能。',
      'chpwd.oldLabel': '当前密码',
      'chpwd.newLabel': '新密码',
      'chpwd.policyHint': '8–72 位，须同时包含字母与数字，且不得与旧密码相同。',
      'chpwd.confirmLabel': '确认新密码',
      'chpwd.submit': '确认修改',
      'chpwd.busy': '提交中…',
      'chpwd.done': '密码已修改，请用新密码重新登录。',
      'chpwd.mismatch': '两次输入的新密码不一致。',
      'err.required': '请填写完整信息',
      'err.invalidEmail': '邮箱格式不正确',
      'err.network': '网络异常，请稍后重试',
      'err.generic': '操作失败，请稍后重试',
      'err.sessionExpired': '登录状态已过期，请重新登录',
      'err.oldPasswordMismatch': '当前密码不正确',
      'err.passwordPolicy': '密码须为 8–72 位且同时包含字母与数字，且不得与旧密码相同',
      'err.changePwdLimit': '改密操作过于频繁，请稍后再试',
      'err.mustChangePassword': '请先修改密码后再使用其他功能',
      'license.title': '我的许可证（License）',
      'license.tab': '许可证',
      'license.desc': '激活软件所用的许可证；点击「复制」后粘贴到客户端激活框。',
      'license.key': '许可证',
      'license.product': '产品',
      'license.status': '状态',
      'license.issuedAt': '签发时间',
      'license.expiresAt': '有效期至',
      'license.machine': '绑定机器码',
      'license.error': '证书加载失败',
      'status.ACTIVE': '正常',
      'status.PAID': '已支付',
      'status.COMPLETED': '已完成',
      'status.PENDING': '待支付',
      'status.CONFIRMED': '已确认',
      'status.PROCESSING': '处理中',
      'status.EXPIRED': '已过期',
      'status.PAST_DUE': '欠费',
      'status.UNPAID': '未支付',
      'status.REVOKED': '已作废',
      'status.REISSUED': '已重发',
      'status.REFUNDED': '已退款',
      'status.PARTIALLY_REFUNDED': '部分退款',
      'status.REFUND_FAILED': '退款失败',
      'status.FAILED': '失败',
      'status.CANCELED': '已取消',
      'status.CANCELLED': '已取消',
      'sub.title': '我的订阅',
      'sub.tab': '订阅',
      'sub.desc': '订阅由支付渠道托管续费；取消后本周期结束即失效。',
      'sub.product': '产品',
      'sub.channel': '渠道',
      'sub.status': '状态',
      'sub.period': '当前周期',
      'sub.nextCharge': '下次扣费',
      'sub.cancelNote': '到期不再续订',
      'sub.cancelNoteLong': '取消是「本周期结束即停」，已付到期的权益会保留到那天，不会当场失效。',
      'sub.cancelAction': '取消订阅',
      'sub.action': '操作',
      'sub.error': '订阅加载失败',
      'cancelSub.title': '取消订阅',
      'cancelSub.desc': '将取消订阅：{product}（{period}）。',
      'cancelSub.note': '取消后本周期不会再自动扣费，当前已付到期的权益保留到周期结束那天。已取消的订阅无法恢复，如需继续使用请重新购买。',
      'cancelSub.cancel': '暂不取消',
      'cancelSub.confirm': '确认取消',
      'cancelSub.busy': '提交中…',
      'cancelSub.ok': '已提交取消，本周期结束后停用。',
      'order.title': '我的订单',
      'order.tab': '订单',
      'order.continuePay': '继续支付',
      'order.desc': '购买与兑换产生的订单记录。',
      'order.number': '订单号',
      'order.amount': '金额',
      'order.status': '订单状态',
      'order.payment': '支付状态',
      'order.createdAt': '下单时间',
      'order.action': '操作',
      'order.error': '订单加载失败',
      'refund.apply': '申请退款',
      'refund.title': '申请退款',
      'refund.descFull': '可退金额 {amount}（按许可证剩余有效期折算）。确认后该订单全部许可证将立即作废，且不可撤销。',
      'refund.reasonLabel': '退款原因（可选）',
      'refund.reasonPlaceholder': '如：买错了版本',
      'refund.cancel': '取消',
      'refund.confirm': '确认退款',
      'refund.busy': '退款中…',
      'refund.hint': '可退 {amount}',
      'refund.okFull': '已全额退款 {amount}',
      'refund.okPartial': '已退 {amount}，该订单许可证已作废',
      'err.refundFailed': '支付渠道退款未成功，请联系 service@ywhome.top 人工处理',
      'err.notRefundable': '该订单当前不满足退款条件（未支付 / 未发货 / 权益已到期 / 订阅订单 / 可退金额过低）',
      'err.alreadyRefunded': '该订单已退款，不可重复申请',
      'err.refundLimit': '退款申请过于频繁，请稍后再试',
      'err.orderNotFound': '订单不存在或无权访问',
      'unbind.action': '解绑',
      'unbind.title': '释放设备绑定',
      'unbind.desc': '将释放该许可证当前绑定的设备「{machine}」。释放后可在新设备上重新激活；只解绑，不会吊销许可证本身。',
      'unbind.cancel': '取消',
      'unbind.confirm': '确认解绑',
      'unbind.busy': '解绑中…',
      'unbind.ok': '已释放设备绑定，可在新设备上重新激活',
      'err.licenseNotFound': '许可证不存在或不属于当前账号',
      'err.licenseRevoked': '该许可证已吊销，无法解绑',
      'err.accessDenied': '无权访问该资源',
      'err.invalidCredentials': '邮箱或密码不正确',
      'err.emailNotPurchased': '该邮箱暂无购买记录，请先购买或兑换',
      'common.empty': '暂无记录',
      'common.copy': '复制',
      'common.retry': '重试',
      'common.none': '—',
      'common.forever': '永久',
      'security.tab': '安全',
      'toast.copied': '已复制到剪贴板',
      'footer.contact': '如有疑问请联系 '
    },
    en: {
      'a11y.skip': 'Skip to main content',
      'brand.name': 'Yaning Labs',
      'nav.home': 'Home',
      'account.eyebrow': 'My Licenses',
      'account.title': 'My Licenses',
      'account.sub': 'View the licenses, subscriptions and orders under the email used at purchase or redemption.',
      'auth.loginTitle': 'Sign in to view your licenses',
      'auth.loginDesc': 'Sign in with the email used at purchase or redemption. If that email was never registered, claim the account via "Set password".',
      'auth.step1': 'Step 1 · Password',
      'auth.step2': 'Step 2 · Verification',
      'auth.emailLabel': 'Email',
      'auth.passwordLabel': 'Password',
      'auth.login': 'Sign in',
      'legal.consentPrefix': 'I have read and agree to the',
      'legal.privacy': 'Privacy Policy',
      'legal.and': 'and',
      'legal.terms': 'Terms of Service',
      'legal.consentRequired': 'Please read and accept the Privacy Policy and Terms of Service first.',
      'auth.loginBusy': 'Signing in…',
      'auth.toReset': 'Forgot password? Set a new one with an email code',
      'auth.toRegister': 'No account yet? Register one',
      'auth.regPwdHint': '8–72 characters with both letters and digits.',
      'auth.regConfirmLabel': 'Confirm password',
      'reg.desc': 'Register with your email to view your licenses, subscriptions and orders here. You will be signed in automatically.',
      'reg.submit': 'Register & Sign In',
      'reg.busy': 'Registering…',
      'reg.mismatch': 'Passwords do not match.',
      'reg.pwdPolicy': 'Password must be 8–72 characters with both letters and digits.',
      'reg.ok': 'Registered and signed in.',
      'reg.codeSent': 'Verification code sent. Please check your inbox.',
      'auth.codeLabel': 'Email verification code',
      'auth.sendCode': 'Send code',
      'auth.sendCodeBusy': 'Sending…',
      'auth.sendCodeCountdown': 'Resend in {s}s',
      'auth.newPasswordLabel': 'New password',
      'auth.reset': 'Set password & sign in',
      'auth.resetBusy': 'Submitting…',
      'auth.toLogin': 'Back to sign in',
      'auth.logout': 'Sign out',
      'auth.codeSent': 'Code sent. Please check your inbox (unreachable if mail service is not configured).',
      'auth.resetOk': 'Password set. Signing in…',
      'mfa.codeLabel': 'Code (or emailed code)',
      'mfa.sendEmail': 'Email me a code',
      'mfa.sendEmailBusy': 'Sending…',
      'mfa.sendEmailCountdown': 'Resend in {s}s',
      'mfa.verify': 'Verify & sign in',
      'mfa.verifyBusy': 'Verifying…',
      'mfa.autoHint': 'Verification starts automatically at 6 digits',
      'mfa.back': 'Back to password',
      'mfa.descWithFallback': 'Password accepted. Enter the 6-digit code from your authenticator app; if unavailable, click "Email me a code".',
      'mfa.descTotpOnly': 'Password accepted. Enter the 6-digit code from your authenticator app.',
      'mfa.codeSent': 'Code sent to your inbox, valid for 10 minutes.',
      'mfa.ticketLost': 'Sign-in session expired. Please enter your password again.',
      'err.mfaCodeInvalid': 'The code is invalid, please retry',
      'err.mfaTicketInvalid': 'Sign-in session expired, please sign in again',
      'err.mfaVerifyLimit': 'Too many attempts, please try again later',
      'err.mfaEmailFallbackDisabled': 'Email fallback is disabled; use your authenticator code',
      'err.codeSendTooFrequent': 'Codes sent too frequently, please wait',
      'chpwd.title': 'Change Password',
      'chpwd.desc': 'After changing, all signed-in sessions are signed out. Sign in again with the new password.',
      'chpwd.forceNote': 'An administrator reset your password: set a new one before using other features.',
      'chpwd.oldLabel': 'Current password',
      'chpwd.newLabel': 'New password',
      'chpwd.policyHint': '8–72 characters with both letters and digits; must differ from the current one.',
      'chpwd.confirmLabel': 'Confirm new password',
      'chpwd.submit': 'Change password',
      'chpwd.busy': 'Submitting…',
      'chpwd.done': 'Password changed. Please sign in with the new password.',
      'chpwd.mismatch': 'The two new passwords do not match.',
      'err.required': 'Please fill in all fields',
      'err.invalidEmail': 'Invalid email address',
      'err.network': 'Network error, please retry later',
      'err.generic': 'Operation failed, please retry later',
      'err.sessionExpired': 'Session expired, please sign in again',
      'err.oldPasswordMismatch': 'The current password is incorrect',
      'err.passwordPolicy': 'Password must be 8–72 characters with both letters and digits, and differ from the current one',
      'err.changePwdLimit': 'Too many attempts, please try again later',
      'err.mustChangePassword': 'Please change your password before using other features',
      'err.accessDenied': 'You do not have access to this resource',
      'err.invalidCredentials': 'Incorrect email or password',
      'err.emailNotPurchased': 'No purchase found for this email. Please buy or redeem first',
      'license.title': 'My Licenses',
      'license.tab': 'Licenses',
      'license.desc': 'Licenses used to activate the software; click "Copy" and paste into the activation box.',
      'license.key': 'License',
      'license.product': 'Product',
      'license.status': 'Status',
      'license.issuedAt': 'Issued',
      'license.expiresAt': 'Expires',
      'license.machine': 'Machine',
      'license.error': 'Failed to load licenses',
      'status.ACTIVE': 'Active',
      'status.PAID': 'Paid',
      'status.COMPLETED': 'Completed',
      'status.PENDING': 'Awaiting payment',
      'status.CONFIRMED': 'Confirmed',
      'status.PROCESSING': 'Processing',
      'status.EXPIRED': 'Expired',
      'status.PAST_DUE': 'Past due',
      'status.UNPAID': 'Unpaid',
      'status.REVOKED': 'Revoked',
      'status.REISSUED': 'Reissued',
      'status.REFUNDED': 'Refunded',
      'status.PARTIALLY_REFUNDED': 'Partially refunded',
      'status.REFUND_FAILED': 'Refund failed',
      'status.FAILED': 'Failed',
      'status.CANCELED': 'Canceled',
      'status.CANCELLED': 'Canceled',
      'sub.title': 'My Subscriptions',
      'sub.tab': 'Subscriptions',
      'sub.desc': 'Subscriptions are billed by the payment provider; after cancellation they end with the current period.',
      'sub.product': 'Product',
      'sub.channel': 'Channel',
      'sub.status': 'Status',
      'sub.period': 'Current period',
      'sub.nextCharge': 'Next charge',
      'sub.cancelNote': 'cancels at period end',
      'sub.cancelNoteLong': 'Cancelling stops auto-renewal; paid benefits remain until the period ends, not immediately.',
      'sub.cancelAction': 'Cancel subscription',
      'sub.action': 'Action',
      'sub.error': 'Failed to load subscriptions',
      'cancelSub.title': 'Cancel Subscription',
      'cancelSub.desc': 'You are about to cancel: {product} ({period}).',
      'cancelSub.note': 'Auto-renewal stops for the current period; paid benefits remain until it ends. Cancellations cannot be undone — purchase again if you need the service.',
      'cancelSub.cancel': 'Keep It',
      'cancelSub.confirm': 'Cancel Subscription',
      'cancelSub.busy': 'Submitting…',
      'cancelSub.ok': 'Cancelled. The subscription ends when the current period ends.',
      'order.title': 'My Orders',
      'order.tab': 'Orders',
      'order.continuePay': 'Continue payment',
      'order.desc': 'Orders created by purchase or redemption.',
      'order.number': 'Order No.',
      'order.amount': 'Amount',
      'order.status': 'Order status',
      'order.payment': 'Payment',
      'order.createdAt': 'Created',
      'order.action': 'Action',
      'order.error': 'Failed to load orders',
      'refund.apply': 'Request refund',
      'refund.title': 'Request a refund',
      'refund.descFull': 'Refundable {amount} (prorated by the remaining license term). Once confirmed, all licenses of this order are revoked immediately and this cannot be undone.',
      'refund.reasonLabel': 'Reason (optional)',
      'refund.reasonPlaceholder': 'e.g. bought the wrong edition',
      'refund.cancel': 'Cancel',
      'refund.confirm': 'Confirm refund',
      'refund.busy': 'Refunding…',
      'refund.hint': 'Refundable {amount}',
      'refund.okFull': 'Refunded {amount} in full',
      'refund.okPartial': 'Refunded {amount}; licenses of this order have been revoked',
      'err.refundFailed': 'The payment provider rejected the refund. Please contact service@ywhome.top',
      'err.notRefundable': 'This order is not eligible for a refund right now',
      'err.alreadyRefunded': 'This order has already been refunded',
      'err.refundLimit': 'Too many refund requests, please try again later',
      'err.orderNotFound': 'Order not found or not accessible',
      'unbind.action': 'Unbind',
      'unbind.title': 'Release device binding',
      'unbind.desc': 'This releases the device "{machine}" currently bound to the license. You can then activate it on a new device; the license itself is not revoked.',
      'unbind.cancel': 'Cancel',
      'unbind.confirm': 'Confirm unbind',
      'unbind.busy': 'Unbinding…',
      'unbind.ok': 'Device binding released; you can now activate on a new device',
      'err.licenseNotFound': 'License not found or not owned by this account',
      'err.licenseRevoked': 'This license has been revoked and cannot be unbound',
      'common.empty': 'No records yet',
      'common.copy': 'Copy',
      'common.retry': 'Retry',
      'common.none': '—',
      'common.forever': 'Lifetime',
      'security.tab': 'Security',
      'toast.copied': 'Copied to clipboard',
      'footer.contact': 'Questions? Contact us at '
    }
  };

  function readLang() {
    try {
      var saved = localStorage.getItem(LANG_KEY);
      return (saved === 'zh' || saved === 'en') ? saved : 'zh';
    } catch (e) { return 'zh'; }
  }

  function saveLang(value) {
    try { localStorage.setItem(LANG_KEY, value); } catch (e) { /* 隐私模式下忽略 */ }
  }

  function t(key, vars) {
    var dict = I18N[state.lang] || I18N.zh;
    var text = dict[key] !== undefined ? dict[key] : (I18N.zh[key] || key);
    if (vars) {
      Object.keys(vars).forEach(function (name) {
        text = text.replace('{' + name + '}', String(vars[name]));
      });
    }
    return text;
  }

  /** 静态文案替换：遍历 data-i18n，同时更新 html lang 与语言开关标签 */
  function applyLang(nextLang) {
    state.lang = nextLang;
    saveLang(nextLang);
    document.documentElement.lang = nextLang === 'zh' ? 'zh-CN' : 'en';
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (I18N[nextLang] && I18N[nextLang][key] !== undefined) {
        el.textContent = I18N[nextLang][key];
      }
    });
    // 订单区为脚本渲染且含插值文案（可退金额），静态 data-i18n 覆盖不到 → 按缓存重渲染
    if (state.licenses.length) renderLicenses(state.licenses);
    if (state.subscriptions.length) renderSubscriptions(state.subscriptions);
    if (state.orders.length) renderOrders(state.orders);
    // 第二因子说明文案按当前可用方式重写（mfaMethods 在第二步表单打开期间有值）
    if (state.mfaMethods) renderMfaStepDesc();
    $('langCurrent').textContent = nextLang === 'zh' ? '中文' : 'EN';
    $('langOther').textContent = nextLang === 'zh' ? 'EN' : '中文';
  }

  /* ======================= 3. HTTP 层（与 checkout.js 同惯用法） ======================= */

  function defaultCodeFor(status) {
    if (status === 400) return 'VALIDATION_ERROR';
    if (status === 401 || status === 403) return 'UNAUTHORIZED';
    if (status === 404 || status === 405) return 'ENDPOINT_NOT_FOUND';
    if (status >= 500) return 'INTERNAL_ERROR';
    return 'UNKNOWN';
  }

  /**
   * 错误码 → i18n 文案键（N5）：服务端消息是中文，英文界面下不应直出中文。
   * 命中此表时优先用页面语言，未命中才回显服务端 message。
   */
  var ERROR_TEXT = {
    EMAIL_NOT_PURCHASED: 'err.emailNotPurchased',
    INVALID_CREDENTIALS: 'err.invalidCredentials',
    UNAUTHORIZED: 'err.sessionExpired',
    ACCESS_DENIED: 'err.accessDenied',
    // plan-4.1 退款：服务端错误码 → 本地化文案（英文界面下不直出中文 message）
    ORDER_NOT_FOUND: 'err.orderNotFound',
    NOT_REFUNDABLE: 'err.notRefundable',
    ALREADY_REFUNDED: 'err.alreadyRefunded',
    REFUND_LIMIT: 'err.refundLimit',
    REFUND_FAILED: 'err.refundFailed',
    // plan-7.0 解绑：归属被拒时服务端刻意用 LICENSE_NOT_FOUND（不泄露他人许可证存在性）
    LICENSE_NOT_FOUND: 'err.licenseNotFound',
    LICENSE_REVOKED: 'err.licenseRevoked',
    // plan-7.0 账户基础功能：改密 / 强制改密文案
    OLD_PASSWORD_MISMATCH: 'err.oldPasswordMismatch',
    PASSWORD_POLICY_VIOLATION: 'err.passwordPolicy',
    CHANGE_PASSWORD_LIMIT: 'err.changePwdLimit',
    PASSWORD_CHANGE_REQUIRED: 'err.mustChangePassword',
    // plan-7.0 / M3：两阶段登录的第二因子错误（服务端 message 为中文，英文界面不直出）
    MFA_CODE_INVALID: 'err.mfaCodeInvalid',
    MFA_TICKET_INVALID: 'err.mfaTicketInvalid',
    MFA_VERIFY_LIMIT: 'err.mfaVerifyLimit',
    MFA_EMAIL_FALLBACK_DISABLED: 'err.mfaEmailFallbackDisabled',
    CODE_SEND_TOO_FREQUENT: 'err.codeSendTooFrequent'
  };

  /**
   * 把非 2xx 响应归一成 {kind:'api', status, code, message}（N4）
   * - code 兼容两种壳体：新壳 `$.code` 与旧自拼体的 `$.errorCode`（成功壳的 SUCCESS 不算错误码）
   * - message 兜底到内层 `$.data.message`，防止「结构一变就只剩通用文案」重演
   */
  function buildApiError(status, payload) {
    var raw = payload || {};
    var rawCode = raw.errorCode || (raw.code && raw.code !== 'SUCCESS' ? raw.code : '');
    var message = raw.message
      || (raw.data && raw.data.message)
      || '';
    return {
      kind: 'api',
      status: status,
      code: rawCode ? String(rawCode) : defaultCodeFor(status),
      message: String(message)
    };
  }

  /**
   * 统一请求入口：服务端经 ApiResponseAdvice 统一包壳，业务字段在 $.data，
   * 在此单点剥壳后向上层返回业务对象（保持对无壳扁平结构的兼容）。
   */
  function requestJson(url, options) {
    return fetch(url, options).then(function (res) {
      return res.text().then(function (raw) {
        var payload = null;
        if (raw) {
          try { payload = JSON.parse(raw); } catch (e) { payload = null; }
        }
        if (!res.ok) throw buildApiError(res.status, payload);
        var data = payload && payload.data;
        if (data && typeof data === 'object') {
          if (data.success === false) throw buildApiError(res.status, data);
          return data;
        }
        return payload || {};
      });
    }, function () {
      return Promise.reject({ kind: 'network', status: 0, code: 'NETWORK', message: '' });
    });
  }

  function authHeaders() {
    var headers = { 'Accept': 'application/json' };
    if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
    return headers;
  }

  function apiGet(path) {
    return requestJson(path, { method: 'GET', headers: authHeaders() });
  }

  function apiPost(path, body) {
    var headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
    return requestJson(path, { method: 'POST', headers: headers, body: body ? JSON.stringify(body) : undefined });
  }

  /* ======================= 4. 视图切换与会话 ======================= */

  function saveSession(token, email) {
    state.token = token;
    state.email = email || '';
    try {
      localStorage.setItem(TOKEN_KEY, token);
      if (state.email) localStorage.setItem(EMAIL_KEY, state.email);
    } catch (e) { /* 隐私模式下忽略 */ }
  }

  function clearSession() {
    state.token = '';
    state.email = '';
    state.mfaTicket = '';
    state.mfaMethods = null;
    // 清掉上一个账号的订单缓存，避免换账号后语言切换重渲染出他人数据
    state.orders = [];
    state.subscriptions = [];
    state.licenses = [];
    state.assetTab = 'license';
    try {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(EMAIL_KEY);
    } catch (e) { /* 忽略 */ }
  }

  function showAuth() {
    $('authPanel').hidden = false;
    $('contentPanel').hidden = true;
  }

  function showContent(email) {
    $('authPanel').hidden = true;
    $('contentPanel').hidden = false;
    $('userEmail').textContent = email || '';
  }

  /**
   * 强制改密模式（plan-7.0 / Q3）：管理员代重置后 must_change_password=true，
   * 服务端过滤器只放行「读自己 / 改密 / 登出」——资产区块必然全部 403，
   * 故直接隐藏分区导航、只留改密区块，避免一排报错。改密成功后回到登录态。
   */
  function setForcedChangeMode(on) {
    state.mustChangePassword = !!on;
    $('assetTabs').hidden = state.mustChangePassword;
    $('chpwdForceNote').hidden = !state.mustChangePassword;
    setAssetTab(state.mustChangePassword ? 'security' : (state.assetTab || 'license'));
  }

  /** P15：资产分区切换（一屏一主任务；强制改密时锁在「安全」） */
  function setAssetTab(name) {
    if (state.mustChangePassword) name = 'security';
    state.assetTab = name;
    queryAll('[data-asset-tab]').forEach(function (btn) {
      var active = btn.getAttribute('data-asset-tab') === name;
      btn.classList.toggle('is-active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
      btn.tabIndex = active ? 0 : -1;
    });
    queryAll('[data-asset-panel]').forEach(function (panel) {
      panel.hidden = panel.getAttribute('data-asset-panel') !== name;
    });
  }

  function errText(err, fallbackKey) {
    if (err && err.kind === 'network') return t('err.network');
    var mapped = (err && err.code) ? ERROR_TEXT[err.code] : '';
    if (mapped) return t(mapped);
    // 401/403：此前空 body 落兜底文案，现在补统一 JSON，这里再按状态兜一层
    if (err && (err.status === 401 || err.status === 403)) return t('err.sessionExpired');
    if (err && err.message) return err.message;
    return t(fallbackKey || 'err.generic');
  }

  /* ======================= 5. 登录 / 找回 / 登出 ======================= */

  function setFormError(id, message) {
    var el = $(id);
    if (message) {
      el.textContent = message;
      el.hidden = false;
    } else {
      el.textContent = '';
      el.hidden = true;
    }
  }

  function setBusy(btn, busyText) {
    btn.disabled = true;
    btn.dataset.label = btn.textContent;
    btn.textContent = busyText;
  }

  function unsetBusy(btn) {
    btn.disabled = false;
    if (btn.dataset.label) btn.textContent = btn.dataset.label;
  }

  var EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

  function handleLogin(event) {
    event.preventDefault();
    var btn = $('loginBtn');
    setFormError('formError', '');
    var email = $('loginEmail').value.trim();
    var password = $('loginPassword').value;
    if (!email || !password) return setFormError('formError', t('err.required'));
    if (!EMAIL_RE.test(email)) return setFormError('formError', t('err.invalidEmail'));
    if (!$('loginLegalConsent').checked) return setFormError('formError', t('legal.consentRequired'));

    setBusy(btn, t('auth.loginBusy'));
    apiPost('/api/account/login', { email: email, password: password }).then(function (data) {
      unsetBusy(btn);
      // 两阶段登录（plan-7.0 / M3）：开启 MFA 的账号此处只拿到一次性票据，
      // 令牌要等第二因子通过才有——不能把「待二次验证」当「登录失败」。
      if (data && data.mfaRequired && data.mfaTicket) {
        state.mfaTicket = data.mfaTicket;
        $('loginPassword').value = '';
        showMfaLoginForm(data.mfaMethods);
        return;
      }
      var token = data && data.accessToken;
      if (!token) return setFormError('formError', t('err.generic'));
      finishAccountLogin(data, 'formError');
    }, function (err) {
      unsetBusy(btn);
      setFormError('formError', errText(err, 'err.generic'));
    });
  }

  /**
   * 两步共用的登录收尾：存会话、进内容视图，并按 mustChangePassword 决定
   * 「直接加载资产」还是「强制改密态」（Q3：管理员代重置后只放行改密/登出）。
   */
  function finishAccountLogin(data, errorId) {
    var token = data && data.accessToken;
    if (!token) { setFormError(errorId, t('err.generic')); return; }
    saveSession(token, data.user && data.user.email);
    state.mfaTicket = '';
    state.mfaMethods = null;
    $('loginPassword').value = '';
    mfaOtp.clear();
    $('mfaLoginForm').hidden = true;
    showContent(data.user && data.user.email);
    var forced = !!(data.user && data.user.mustChangePassword);
    setForcedChangeMode(forced);
    if (forced) {
      announce(t('chpwd.forceNote'));
      $('chpwdOld').focus();
    } else {
      loadAll();
    }
  }

  /* ======================= 5.1 两阶段登录第二步（plan-7.0 / M3） ======================= */

  function renderMfaStepDesc() {
    var emailFallback = !state.mfaMethods || state.mfaMethods.indexOf('EMAIL') !== -1;
    $('mfaStepDesc').textContent = t(emailFallback ? 'mfa.descWithFallback' : 'mfa.descTotpOnly');
    $('mfaSendBtn').hidden = !emailFallback;
  }

  /** 切到第二因子表单：密码已过，凭内存票据换令牌（刷新页面即失效，须重新输密码）。 */
  function showMfaLoginForm(methods) {
    state.mfaMethods = methods || null;
    $('loginForm').hidden = true;
    $('resetForm').hidden = true;
    setFormError('formError', '');
    setFormError('mfaError', '');
    renderMfaStepDesc();
    mfaOtp.clear();
    $('mfaLoginForm').hidden = false;
    mfaOtp.focus();
  }

  /** 返回密码表单：丢弃票据（旧票据随 tokenVersion 递增或 TTL 自然失效，不主动复用）。 */
  function backToLoginForm() {
    state.mfaTicket = '';
    state.mfaMethods = null;
    mfaOtp.clear();
    $('mfaLoginForm').hidden = true;
    $('loginForm').hidden = false;
    setFormError('mfaError', '');
    $('loginPassword').focus();
  }

  /**
   * 第二步提交：票据 + 动态码（或邮箱兜底码）换正式令牌。
   *
   * <p>返回 Promise，供「输满 6 位自动提交」与按钮/回车两条入口共用；
   * 验证失败时清空输入框并聚焦，用户可直接重输并再次触发自动提交。
   */
  function verifyMfaCode(code) {
    var btn = $('mfaVerifyBtn');
    setFormError('mfaError', '');
    if (!code) { setFormError('mfaError', t('err.required')); return Promise.resolve(); }
    if (!state.mfaTicket) {
      backToLoginForm();
      setFormError('formError', t('mfa.ticketLost'));
      return Promise.resolve();
    }

    setBusy(btn, t('mfa.verifyBusy'));
    return apiPost('/api/account/mfa/verify', { ticket: state.mfaTicket, code: code })
      .then(function (data) {
        finishAccountLogin(data, 'mfaError');
      }, function (err) {
        // 票据失效（过期/账号信息已变更）：留在本表单只会反复失败，退回密码步重新走
        if (err && err.code === 'MFA_TICKET_INVALID') {
          backToLoginForm();
          setFormError('formError', errText(err, 'mfa.ticketLost'));
          return;
        }
        setFormError('mfaError', errText(err, 'err.generic'));
        mfaOtp.clear();
        mfaOtp.focus();
      })
      .then(function () { unsetBusy(btn); });
  }

  function handleMfaVerify(event) {
    event.preventDefault();
    if ($('mfaVerifyBtn').disabled) return;
    verifyMfaCode($('mfaCode').value.trim());
  }

  /** 动态码输满 6 位即自动验证（键盘输入与粘贴均触发）；不足 6 位或正在验证时不发请求。 */
  function autoVerifyMfaOnSixDigits() {
    if ($('mfaVerifyBtn').disabled) return;
    var code = $('mfaCode').value.trim();
    if (!/^\d{6}$/.test(code)) return;
    verifyMfaCode(code);
  }

  /** 请求邮箱兜底验证码（认证器不可用时的恢复路径）；受服务端 60s 邮箱冷却约束。 */
  function handleMfaSendEmail() {
    var btn = $('mfaSendBtn');
    setFormError('mfaError', '');
    if (!state.mfaTicket) {
      backToLoginForm();
      setFormError('formError', t('mfa.ticketLost'));
      return;
    }
    var now = Date.now();
    if (now < state.mfaEmailReadyAt) return;

    setBusy(btn, t('mfa.sendEmailBusy'));
    apiPost('/api/account/mfa/challenge', { ticket: state.mfaTicket })
      .then(function () {
        state.mfaEmailReadyAt = now + SEND_CODE_COOLDOWN_MS;
        startMfaCountdown(btn);
        announce(t('mfa.codeSent'));
      }, function (err) {
        unsetBusy(btn);
        // 与 verify 同口径：票据失效就退回密码步，留在本表单只会反复失败
        if (err && err.code === 'MFA_TICKET_INVALID') {
          backToLoginForm();
          setFormError('formError', errText(err, 'mfa.ticketLost'));
          return;
        }
        setFormError('mfaError', errText(err, 'err.generic'));
      });
  }

  function startMfaCountdown(btn) {
    var tick = function () {
      var remain = Math.ceil((state.mfaEmailReadyAt - Date.now()) / 1000);
      if (remain <= 0) {
        btn.disabled = false;
        btn.textContent = t('mfa.sendEmail');
        return;
      }
      btn.textContent = t('mfa.sendEmailCountdown', { s: remain });
      setTimeout(tick, 1000);
    };
    tick();
  }

  /** 发送找回密码验证码（公开端点，用途 RESET_PASSWORD） */
  function handleSendCode() {
    var btn = $('sendCodeBtn');
    setFormError('codeError', '');
    var email = $('resetEmail').value.trim();
    if (!email) return setFormError('codeError', t('err.required'));
    if (!EMAIL_RE.test(email)) return setFormError('codeError', t('err.invalidEmail'));
    var now = Date.now();
    if (now < state.sendCodeReadyAt) return;

    setBusy(btn, t('auth.sendCodeBusy'));
    apiPost('/api/account/verification-code', { email: email, purpose: 'RESET_PASSWORD' }).then(function () {
      state.sendCodeReadyAt = now + SEND_CODE_COOLDOWN_MS;
      startCountdown(btn);
      setFormError('codeError', '');
      announce(t('auth.codeSent'));
    }, function (err) {
      unsetBusy(btn);
      setFormError('codeError', errText(err, 'err.generic'));
    });
  }

  function startCountdown(btn) {
    var tick = function () {
      var remain = Math.ceil((state.sendCodeReadyAt - Date.now()) / 1000);
      if (remain <= 0) {
        btn.disabled = false;
        btn.textContent = t('auth.sendCode');
        return;
      }
      btn.textContent = t('auth.sendCodeCountdown', { s: remain });
      setTimeout(tick, 1000);
    };
    tick();
  }

  /** 找回/设置密码：成功后用新密码自动登录（重置会令旧令牌全部失效） */
  function handleReset(event) {
    event.preventDefault();
    var btn = $('resetBtn');
    setFormError('resetError', '');
    var email = $('resetEmail').value.trim();
    var code = $('resetCode').value.trim();
    var newPassword = $('newPassword').value;
    if (!email || !code || !newPassword) return setFormError('resetError', t('err.required'));
    if (!EMAIL_RE.test(email)) return setFormError('resetError', t('err.invalidEmail'));

    setBusy(btn, t('auth.resetBusy'));
    apiPost('/api/account/password/reset', { email: email, code: code, newPassword: newPassword })
      .then(function () {
        announce(t('auth.resetOk'));
        // 自动登录：复用登录处理（带着新密码走一遍标准登录，一并完成会话建立）
        $('loginEmail').value = email;
        $('loginPassword').value = newPassword;
        handleLogin({ preventDefault: function () {} });
      }, function (err) {
        unsetBusy(btn);
        setFormError('resetError', errText(err, 'err.generic'));
      });
  }

  function handleLogout() {
    var token = state.token;
    clearSession();
    showAuth();
    setForcedChangeMode(false);
    // 服务端令牌吊销尽力而为：带上刚清除的令牌直接调用，结果不影响本地登出
    if (token) {
      fetch('/api/account/logout', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json' }
      }).catch(function () { /* 忽略 */ });
    }
  }

  /* ======================= 4.5 注册（A2） ======================= */

  /** 密码策略前端预检：与后端 PasswordPolicy 对齐（8–72 位，含字母与数字）。 */
  var PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;

  /** 发送注册验证码（公开端点，用途 REGISTER；与找回表单共用 60s 冷却节奏但不共享计时器）。 */
  function handleRegSendCode() {
    var btn = $('regSendBtn');
    setFormError('regCodeError', '');
    var email = $('regEmail').value.trim();
    if (!email) return setFormError('regCodeError', t('err.required'));
    if (!EMAIL_RE.test(email)) return setFormError('regCodeError', t('err.invalidEmail'));
    var now = Date.now();
    if (now < state.regCodeReadyAt) return;

    setBusy(btn, t('auth.sendCodeBusy'));
    apiPost('/api/account/verification-code', { email: email, purpose: 'REGISTER' }).then(function () {
      state.regCodeReadyAt = now + SEND_CODE_COOLDOWN_MS;
      unsetBusy(btn);
      startRegCountdown(btn);
      setFormError('regCodeError', '');
      announce(t('reg.codeSent'));
    }, function (err) {
      unsetBusy(btn);
      setFormError('regCodeError', errText(err, 'err.generic'));
    });
  }

  function startRegCountdown(btn) {
    var tick = function () {
      var remain = Math.ceil((state.regCodeReadyAt - Date.now()) / 1000);
      if (remain <= 0) {
        btn.disabled = false;
        btn.textContent = t('auth.sendCode');
        return;
      }
      btn.textContent = t('auth.sendCodeCountdown', { s: remain });
      setTimeout(tick, 1000);
    };
    tick();
  }

  /** 注册：成功即返回令牌（与登录同构的 AuthResponse），走同一收尾建立会话。 */
  function handleRegister(event) {
    event.preventDefault();
    var btn = $('regBtn');
    setFormError('regError', '');
    var email = $('regEmail').value.trim();
    var code = $('regCode').value.trim();
    var password = $('regPassword').value;
    var confirm2 = $('regPasswordConfirm').value;
    if (!email || !code || !password || !confirm2) return setFormError('regError', t('err.required'));
    if (!EMAIL_RE.test(email)) return setFormError('regError', t('err.invalidEmail'));
    if (!PASSWORD_RE.test(password)) return setFormError('regError', t('reg.pwdPolicy'));
    if (password !== confirm2) return setFormError('regError', t('reg.mismatch'));
    if (!$('registerLegalConsent').checked) return setFormError('regError', t('legal.consentRequired'));

    setBusy(btn, t('reg.busy'));
    apiPost('/api/account/register', { email: email, emailCode: code, password: password })
      .then(function (data) {
        unsetBusy(btn);
        announce(t('reg.ok'));
        finishAccountLogin(data, 'regError');
      }, function (err) {
        unsetBusy(btn);
        setFormError('regError', errText(err, 'err.generic'));
      });
  }

  /** 登录 ↔ 注册 ↔ 找回三张表单互斥切换；注册态清空密码与验证码输入。 */
  function showRegisterForm() {
    $('loginForm').hidden = true;
    $('resetForm').hidden = true;
    $('mfaLoginForm').hidden = true;
    $('registerForm').hidden = false;
    setFormError('formError', '');
    setFormError('resetError', '');
    setFormError('codeError', '');
    $('regPassword').value = '';
    $('regPasswordConfirm').value = '';
    $('regCode').value = '';
    setFormError('regError', '');
    setFormError('regCodeError', '');
    $('regEmail').focus();
  }

  function backToLoginFromRegister() {
    $('registerForm').hidden = true;
    $('loginForm').hidden = false;
    setFormError('regError', '');
    setFormError('regCodeError', '');
    $('loginPassword').focus();
  }

  /* ======================= 5.1 修改密码（plan-7.0 / P1 + Q3） ======================= */

  /** 资产区命中 PASSWORD_CHANGE_REQUIRED 时同样进入强制改密（如旧页面缓存的会话）。 */
  function enterForcedChange() {
    setForcedChangeMode(true);
    announce(t('chpwd.forceNote'));
    $('chpwdOld').focus();
  }

  /** 已登录自助改密：成功后旧令牌全部失效 → 清本地会话回登录卡片。 */
  function handleChangePassword(event) {
    event.preventDefault();
    var btn = $('chpwdBtn');
    setFormError('chpwdError', '');
    var oldPwd = $('chpwdOld').value;
    var newPwd = $('chpwdNew').value;
    var confirmPwd = $('chpwdConfirm').value;
    if (!oldPwd || !newPwd || !confirmPwd) return setFormError('chpwdError', t('err.required'));
    if (newPwd !== confirmPwd) return setFormError('chpwdError', t('chpwd.mismatch'));
    if (newPwd === oldPwd) return setFormError('chpwdError', t('err.passwordPolicy'));

    setBusy(btn, t('chpwd.busy'));
    apiPost('/api/account/password/change', { oldPassword: oldPwd, newPassword: newPwd })
      .then(function () {
        unsetBusy(btn);
        clearSession();
        showAuth();
        setForcedChangeMode(false);
        $('chpwdOld').value = '';
        $('chpwdNew').value = '';
        $('chpwdConfirm').value = '';
        setFormError('formError', t('chpwd.done'));
        announce(t('chpwd.done'));
      }, function (err) {
        unsetBusy(btn);
        setFormError('chpwdError', errText(err, 'err.generic'));
      });
  }

  /* ======================= 6. 资产加载与渲染 ======================= */

  function fmtDate(value) {
    return value ? String(value).slice(0, 16).replace('T', ' ') : t('common.none');
  }

  function money(amount, currency) {
    if (amount === null || amount === undefined || amount === '') return t('common.none');
    var n = Number(amount);
    var text = isFinite(n) ? n.toFixed(2) : String(amount);
    return currency ? text + ' ' + currency : text;
  }

  /** 状态徽标：语义色映射（ACTIVE/PAID 绿、PENDING/EXPIRED 黄、REVOKED/FAILED 红、其余灰） */
  var STATUS_STYLE = {
    ACTIVE: 'ok', PAID: 'ok', COMPLETED: 'ok',
    PENDING: 'warn', CONFIRMED: 'warn', PROCESSING: 'warn', EXPIRED: 'warn', PAST_DUE: 'warn', UNPAID: 'warn',
    REVOKED: 'danger', REFUND_FAILED: 'danger', FAILED: 'danger', CANCELED: 'muted', CANCELLED: 'muted',
    REISSUED: 'muted', REFUNDED: 'muted', PARTIALLY_REFUNDED: 'muted'
  };

  function badge(status) {
    if (!status) return '<span class="badge badge--muted">' + t('common.none') + '</span>';
    var cls = STATUS_STYLE[status] || 'muted';
    var key = 'status.' + status;
    var label = (I18N[state.lang] && I18N[state.lang][key] !== undefined)
      ? I18N[state.lang][key]
      : (I18N.zh[key] !== undefined ? I18N.zh[key] : status);
    return '<span class="badge badge--' + cls + '">' + escapeHtml(label) + '</span>';
  }

  function escapeHtml(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function setSectionError(id, key, err) {
    var el = $(id);
    el.textContent = errText(err, key);
    var retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'btn btn--ghost btn--sm';
    retry.textContent = t('common.retry');
    retry.addEventListener('click', function () { loadAll(); });
    el.appendChild(document.createTextNode(' '));
    el.appendChild(retry);
    el.hidden = false;
  }

  function clearSectionError(id) {
    var el = $(id);
    el.textContent = '';
    el.hidden = true;
  }

  function showEmpty(listId, emptyId, rows) {
    $(listId).innerHTML = rows;
    $(emptyId).hidden = rows !== '';
  }

  /** P18/G3：加载骨架，避免表格在请求期间空白 */
  function skeletonRows(cols) {
    var row = '<tr><td colspan="' + cols + '"><div class="sk-row">'
      + '<span class="sk" style="max-width:160px"></span><span class="sk"></span>'
      + '<span class="sk" style="max-width:72px"></span></div></td></tr>';
    return row + row + row;
  }

  function showSkeleton(bodyId, countId, cols) {
    $(bodyId).innerHTML = skeletonRows(cols);
    if ($(countId)) $(countId).textContent = '–';
  }

  function setCount(countId, value) {
    if ($(countId)) $(countId).textContent = String(value);
  }

  function renderLicenses(list) {
    state.licenses = list || [];
    var rows = state.licenses.map(function (item) {
      return '<tr>'
        + '<td><span class="key-cell"><code>' + escapeHtml(item.licenseKey) + '</code>'
        + '<button type="button" class="copy-btn" data-copy="' + escapeHtml(item.licenseKey)
        // data-i18n 让语言切换时 applyLang 一并刷新动态渲染的按钮文案
        + '" data-i18n="common.copy">' + escapeHtml(t('common.copy')) + '</button></span></td>'
        + '<td>' + escapeHtml(item.productSku || t('common.none')) + '</td>'
        + '<td>' + badge(item.status) + '</td>'
        + '<td class="mono">' + fmtDate(item.issuedAt) + '</td>'
        + '<td class="mono">' + (item.expiresAt ? fmtDate(item.expiresAt) : t('common.forever')) + '</td>'
        + '<td class="mono">' + machineCell(item) + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('licenseBody', 'licenseEmpty', rows);
    setCount('licenseCount', state.licenses.length);
  }

  /**
   * 设备列（plan-7.0 决策 B6）：已绑定时给出「解绑」入口。
   *
   * <p>存在的理由：activate 端在「该授权已绑定到别的机器」时返回 MACHINE_MISMATCH 而**不自动改绑**，
   * 用户必须先在账号页手动解绑，才能把授权重新激活到新设备。没有这个按钮，那条错误提示就是死路。
   *
   * <p>未绑定、或已吊销（无可解绑对象）时只显示占位符。
   */
  function machineCell(item) {
    var bound = item.machineCode;
    var text = escapeHtml(bound || t('common.none'));
    if (!bound || item.status === 'REVOKED') return text;
    return '<span class="unbind-cell">' + text
      + '<button type="button" class="unbind-btn" data-unbind="' + escapeHtml(item.licenseKey)
      + '" data-i18n="unbind.action">' + escapeHtml(t('unbind.action')) + '</button></span>';
  }

  function renderSubscriptions(list) {
    var rows = (list || []).map(function (item) {
      var period = fmtDate(item.currentPeriodStart) + ' ~ ' + fmtDate(item.currentPeriodEnd);
      var cancelNote = item.cancelAtPeriodEnd ? '（' + t('sub.cancelNote') + '）' : '';
      // 仅「生效中且未挂取消标记」的订阅给取消入口；已取消/已过期的行不再重复提供
      var canCancel = item.status === 'ACTIVE' && !item.cancelAtPeriodEnd;
      var actionCell = canCancel
        ? '<button type="button" class="unbind-btn" data-cancel="' + escapeHtml(item.id)
          + '" data-i18n="sub.cancelAction">' + escapeHtml(t('sub.cancelAction')) + '</button>'
        : '<span class="muted">—</span>';
      return '<tr>'
        + '<td>' + escapeHtml(item.productName || item.productSku || t('common.none')) + '</td>'
        + '<td>' + escapeHtml(item.provider || t('common.none')) + '</td>'
        + '<td>' + badge(item.status) + '</td>'
        + '<td class="mono">' + period + cancelNote + '</td>'
        + '<td class="mono">' + fmtDate(item.currentPeriodEnd) + '</td>'
        + '<td>' + actionCell + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('subBody', 'subEmpty', rows);
    setCount('subCount', (list || []).length);
  }

  function renderOrders(list) {
    state.orders = list || [];
    var rows = state.orders.map(function (item) {
      return '<tr>'
        + '<td class="mono">' + escapeHtml(item.orderNumber) + '</td>'
        + '<td class="mono">' + escapeHtml(money(item.totalAmount, item.currency)) + '</td>'
        + '<td>' + badge(item.status) + '</td>'
        + '<td>' + badge(item.paymentStatus) + '</td>'
        + '<td class="mono">' + fmtDate(item.createdAt) + '</td>'
        + '<td>' + orderActionCell(item) + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('orderBody', 'orderEmpty', rows);
    setCount('orderCount', state.orders.length);
  }

  /**
   * 订单操作列：P16「继续支付」优先（服务端仅对未支付且会话未过期回填 checkoutId），
   * 其次 P 号退款入口。两者可以并存时上下排列。
   */
  function orderActionCell(item) {
    var html = '';
    if (item.checkoutId) {
      var resumeUrl = '../checkout/?checkoutId=' + encodeURIComponent(item.checkoutId);
      if (item.productSku) resumeUrl += '&productId=' + encodeURIComponent(item.productSku);
      html += '<a class="pay-btn" href="' + escapeHtml(resumeUrl) + '">'
        + escapeHtml(t('order.continuePay')) + '</a>';
    }
    var refund = refundCell(item);
    if (refund) {
      html += (html ? '' : '') + refund;
    }
    return html ? '<div class="order-action-cell">' + html + '</div>' : '<span class="muted">' + t('common.none') + '</span>';
  }

  /**
   * 退款入口单元格（plan-4.1）：refundable / refundableAmount 由服务端按
   * License 剩余有效期折算后回填，前端不做资格判断，避免两处口径漂移。
   */
  function refundCell(item) {
    if (!item.refundable) return '';
    var amount = money(item.refundableAmount, item.currency);
    return '<span class="refund-cell">'
      + '<button type="button" class="refund-btn" data-refund="' + escapeHtml(item.orderNumber)
      // data-i18n 让语言切换时 applyLang 一并刷新动态渲染的按钮文案
      + '" data-i18n="refund.apply">' + escapeHtml(t('refund.apply')) + '</button>'
      + '<span class="refund-hint">' + escapeHtml(t('refund.hint', { amount: amount })) + '</span>'
      + '</span>';
  }

  /** 拉取三个区块；任一 401 都视为会话失效，回到登录态；PASSWORD_CHANGE_REQUIRED 进强制改密 */
  function loadAll() {
    if (state.mustChangePassword) return; // 强制改密时不发资产请求（发了也会被过滤器拦）
    clearSectionError('licenseError');
    clearSectionError('subError');
    clearSectionError('orderError');
    showSkeleton('licenseBody', 'licenseCount', 6);
    showSkeleton('subBody', 'subCount', 7);
    showSkeleton('orderBody', 'orderCount', 6);
    $('licenseEmpty').hidden = true;
    $('subEmpty').hidden = true;
    $('orderEmpty').hidden = true;

    apiGet('/api/account/licenses').then(function (list) {
      renderLicenses(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      if (err && err.code === 'PASSWORD_CHANGE_REQUIRED') return enterForcedChange();
      $('licenseBody').innerHTML = '';
      setCount('licenseCount', 0);
      setSectionError('licenseError', 'license.error', err);
    });

    apiGet('/api/account/subscriptions').then(function (list) {
      state.subscriptions = list || [];
      renderSubscriptions(state.subscriptions);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      if (err && err.code === 'PASSWORD_CHANGE_REQUIRED') return enterForcedChange();
      $('subBody').innerHTML = '';
      setCount('subCount', 0);
      setSectionError('subError', 'sub.error', err);
    });

    apiGet('/api/account/orders').then(function (list) {
      renderOrders(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      if (err && err.code === 'PASSWORD_CHANGE_REQUIRED') return enterForcedChange();
      $('orderBody').innerHTML = '';
      setCount('orderCount', 0);
      setSectionError('orderError', 'order.error', err);
    });
  }

  function onSessionExpired() {
    clearSession();
    showAuth();
    setFormError('formError', t('err.sessionExpired'));
  }

  function announce(text) {
    var region = $('liveRegion');
    if (region) region.textContent = text;
  }

  /** G1/P17：统一 toast 反馈（2 秒自动消失，可堆积） */
  function toast(message) {
    var wrap = $('toastWrap');
    if (!wrap) return;
    var el = document.createElement('div');
    el.className = 'toast';
    el.textContent = message;
    wrap.appendChild(el);
    setTimeout(function () {
      el.classList.add('is-out');
      setTimeout(function () { el.remove(); }, 300);
    }, 2000);
  }

  /* ======================= 6.1 退款流程（plan-4.1） ======================= */

  function findOrder(orderNumber) {
    for (var i = 0; i < state.orders.length; i++) {
      if (state.orders[i].orderNumber === orderNumber) return state.orders[i];
    }
    return null;
  }

  /** 打开确认弹层：明示折算金额与「全部 License 立即作废」的不可逆后果 */
  function openRefund(orderNumber) {
    var order = findOrder(orderNumber);
    if (!order) return;
    state.refundOrder = order;
    $('refundDesc').textContent = t('refund.descFull', {
      amount: money(order.refundableAmount, order.currency)
    });
    $('refundReason').value = '';
    $('refundReason').placeholder = t('refund.reasonPlaceholder');
    $('refundCount').textContent = '0/200';
    setFormError('refundError', '');
    $('refundModal').hidden = false;
    $('refundReason').focus();
  }

  function closeRefund() {
    $('refundModal').hidden = true;
    state.refundOrder = null;
    unsetBusy($('refundConfirmBtn'));
  }

  function confirmRefund() {
    var order = state.refundOrder;
    if (!order) return;
    var btn = $('refundConfirmBtn');
    setFormError('refundError', '');
    setBusy(btn, t('refund.busy'));
    apiPost('/api/account/orders/' + encodeURIComponent(order.orderNumber) + '/refund',
      { reason: $('refundReason').value.trim() })
      .then(function (result) {
        var amount = money(result && result.refundedAmount, order.currency);
        closeRefund();
        // 实际结果可能因渠道不支持部分退款而降级为全额，文案以返回的 fullRefund 为准
        var text = result && result.fullRefund
          ? t('refund.okFull', { amount: amount })
          : t('refund.okPartial', { amount: amount });
        announce(text);
        toast(text);
        // 退款会吊销该订单全部 License，故订单与许可证两块都要刷新
        loadAll();
      }, function (err) {
        unsetBusy(btn);
        setFormError('refundError', errText(err, 'err.generic'));
      });
  }

  /* ======================= 6.2 释放设备绑定（plan-7.0 决策 B6） ======================= */

  function findLicense(licenseKey) {
    for (var i = 0; i < state.licenses.length; i++) {
      if (state.licenses[i].licenseKey === licenseKey) return state.licenses[i];
    }
    return null;
  }

  /** 打开确认弹层：明示将释放哪台设备，避免误点把在用设备解绑 */
  function openUnbind(licenseKey) {
    var license = findLicense(licenseKey);
    if (!license) return;
    state.unbindLicense = license;
    $('unbindDesc').textContent = t('unbind.desc', { machine: license.machineCode || '' });
    setFormError('unbindError', '');
    $('unbindModal').hidden = false;
  }

  function closeUnbind() {
    $('unbindModal').hidden = true;
    state.unbindLicense = null;
    unsetBusy($('unbindConfirmBtn'));
  }

  function confirmUnbind() {
    var license = state.unbindLicense;
    if (!license) return;
    var btn = $('unbindConfirmBtn');
    setFormError('unbindError', '');
    setBusy(btn, t('unbind.busy'));
    apiPost('/api/account/licenses/' + encodeURIComponent(license.licenseKey) + '/unbind', {})
      .then(function () {
        closeUnbind();
        announce(t('unbind.ok'));
        toast(t('unbind.ok'));
        // 设备列需刷新，才能落成「未绑定」并撤掉解绑按钮
        loadAll();
      }, function (err) {
        unsetBusy(btn);
        setFormError('unbindError', errText(err, 'err.generic'));
      });
  }

  /* ======================= 6.5 订阅自助取消（A2）：期末生效，弹层明示后果 ======================= */

  /** 打开确认弹层：明示将取消哪份订阅与当前周期，避免误点。 */
  function openCancelSub(subscriptionId) {
    if (!subscriptionId) return;
    state.cancelSubId = subscriptionId;
    var sub = (state.subscriptions || []).filter(function (s) { return String(s.id) === String(subscriptionId); })[0];
    var product = sub ? (sub.productName || sub.productSku || '') : '';
    var period = sub ? (fmtDate(sub.currentPeriodStart) + ' ~ ' + fmtDate(sub.currentPeriodEnd)) : '';
    $('cancelSubDesc').textContent = t('cancelSub.desc', { product: product, period: period });
    setFormError('cancelSubError', '');
    $('cancelSubModal').hidden = false;
  }

  function closeCancelSub() {
    $('cancelSubModal').hidden = true;
    state.cancelSubId = null;
    unsetBusy($('cancelSubConfirmBtn'));
  }

  function confirmCancelSub() {
    var id = state.cancelSubId;
    if (!id) return;
    var btn = $('cancelSubConfirmBtn');
    setFormError('cancelSubError', '');
    setBusy(btn, t('cancelSub.busy'));
    apiPost('/api/account/subscriptions/' + encodeURIComponent(id) + '/cancel', {})
      .then(function () {
        closeCancelSub();
        announce(t('cancelSub.ok'));
        toast(t('cancelSub.ok'));
        // 重拉列表：挂上「到期不再续订」标记并撤掉取消按钮
        loadAll();
      }, function (err) {
        unsetBusy(btn);
        setFormError('cancelSubError', errText(err, 'err.generic'));
      });
  }

  /* ======================= 7. 事件绑定与启动 ======================= */

  function bindEvents() {
    $('langSwitch').addEventListener('click', function () {
      applyLang(state.lang === 'zh' ? 'en' : 'zh');
    });

    $('loginForm').addEventListener('submit', handleLogin);
    $('resetForm').addEventListener('submit', handleReset);
    $('sendCodeBtn').addEventListener('click', handleSendCode);
    $('logoutBtn').addEventListener('click', handleLogout);
    $('changePwdForm').addEventListener('submit', handleChangePassword);

    // 两阶段登录第二步（plan-7.0 / M3）：提交校验 / 邮箱兜底发码 / 返回密码步
    $('mfaLoginForm').addEventListener('submit', handleMfaVerify);
    $('mfaSendBtn').addEventListener('click', handleMfaSendEmail);
    $('mfaBackBtn').addEventListener('click', backToLoginForm);

    // 登录 / 找回两张表单切换
    $('showResetBtn').addEventListener('click', function () {
      showResetForm();
    });
    $('backLoginBtn').addEventListener('click', function () {
      state.mfaTicket = '';
      state.mfaMethods = null;
      $('resetForm').hidden = true;
      $('mfaLoginForm').hidden = true;
      $('loginForm').hidden = false;
      setFormError('resetError', '');
      setFormError('codeError', '');
    });

    // 资产分区 Tab（P15）：点击切换 + 左右方向键移动
    queryAll('#assetTabs [data-asset-tab]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        setAssetTab(btn.getAttribute('data-asset-tab'));
      });
    });
    $('assetTabs').addEventListener('keydown', function (event) {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      var tabs = queryAll('#assetTabs [data-asset-tab]');
      var index = tabs.indexOf(document.activeElement);
      if (index === -1) return;
      event.preventDefault();
      var next = tabs[(index + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
      next.focus();
      setAssetTab(next.getAttribute('data-asset-tab'));
    });

    // 退款原因字数计数（P19：200 字上限可见）
    $('refundReason').addEventListener('input', function () {
      $('refundCount').textContent = String($('refundReason').value.length) + '/200';
    });

    // 复制许可证 / 申请退款 / 释放设备绑定（事件委托：表格行由脚本渲染）
    document.addEventListener('click', function (event) {
      var refundBtn = event.target.closest('[data-refund]');
      if (refundBtn) {
        openRefund(refundBtn.getAttribute('data-refund'));
        return;
      }
      var unbindBtn = event.target.closest('[data-unbind]');
      if (unbindBtn) {
        openUnbind(unbindBtn.getAttribute('data-unbind'));
        return;
      }
      var cancelSubBtn = event.target.closest('[data-cancel]');
      if (cancelSubBtn) {
        openCancelSub(cancelSubBtn.getAttribute('data-cancel'));
        return;
      }
      var btn = event.target.closest('[data-copy]');
      if (!btn) return;
      copyText(btn.getAttribute('data-copy'), btn);
    });

    // 退款弹层：取消 / 点遮罩 / Esc 关闭，确认则提交
    $('refundCancelBtn').addEventListener('click', closeRefund);
    $('refundBackdrop').addEventListener('click', closeRefund);
    $('refundConfirmBtn').addEventListener('click', confirmRefund);

    // 解绑弹层（plan-7.0 决策 B6）
    $('unbindCancelBtn').addEventListener('click', closeUnbind);
    $('unbindBackdrop').addEventListener('click', closeUnbind);
    $('unbindConfirmBtn').addEventListener('click', confirmUnbind);

    // 取消订阅弹层（A2 自助取消）
    $('cancelSubCancelBtn').addEventListener('click', closeCancelSub);
    $('cancelSubBackdrop').addEventListener('click', closeCancelSub);
    $('cancelSubConfirmBtn').addEventListener('click', confirmCancelSub);

    // 注册表单（A2）：发送验证码 / 提交 / 返回登录
    $('registerForm').addEventListener('submit', handleRegister);
    $('regSendBtn').addEventListener('click', handleRegSendCode);
    $('showRegisterBtn').addEventListener('click', showRegisterForm);
    $('regBackBtn').addEventListener('click', backToLoginFromRegister);

    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape' && event.key !== 'Tab') return;
      var modal = ['refundModal', 'unbindModal', 'cancelSubModal'].map($).filter(function (el) {
        return el && !el.hidden;
      })[0];
      if (!modal) return;
      if (event.key === 'Escape') {
        if (modal.id === 'refundModal') closeRefund();
        else if (modal.id === 'unbindModal') closeUnbind();
        else closeCancelSub();
        return;
      }
      trapModalFocus(event, modal);
    });
  }

  /** G4：把 Tab 循环圈在弹层内，避免焦点跑到遮罩后的页面 */
  function trapModalFocus(event, modal) {
    var focusables = queryAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])', modal)
      .filter(function (el) { return !el.disabled && el.offsetParent !== null; });
    if (!focusables.length) return;
    var first = focusables[0];
    var last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function copyText(text, btn) {
    var done = function () {
      toast(t('toast.copied'));
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
    } else {
      fallbackCopy(text);
      done();
    }
  }

  function fallbackCopy(text) {
    var textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    try { document.execCommand('copy'); } catch (e) { /* 忽略 */ }
    document.body.removeChild(textarea);
  }

  /** 切到找回 / 认领表单（「忘记密码」入口与管理台跳转 ?mode=reset 共用）。 */
  function showResetForm() {
    state.mfaTicket = '';
    state.mfaMethods = null;
    $('loginForm').hidden = true;
    $('mfaLoginForm').hidden = true;
    $('resetForm').hidden = false;
    setFormError('formError', '');
  }

  function start() {
    state.lang = readLang();
    mfaOtp = initOtp('mfaOtp', 'mfaCode', autoVerifyMfaOnSixDigits);
    applyLang(state.lang);
    setAssetTab(state.assetTab);
    bindEvents();

    // 管理台找回入口跳转 ?mode=reset：直接展开找回表单（不新增后端 API）
    if (window.location.search.indexOf('mode=reset') !== -1) {
      showResetForm();
    }

    // 恢复会话：有令牌先用 /me 校验（顺带取邮箱），失效则清掉回登录态
    var savedToken = '';
    var savedEmail = '';
    try {
      savedToken = localStorage.getItem(TOKEN_KEY) || '';
      savedEmail = localStorage.getItem(EMAIL_KEY) || '';
    } catch (e) { /* 忽略 */ }

    if (savedToken) {
      state.token = savedToken;
      apiGet('/api/account/me').then(function (user) {
        showContent(user && user.email);
        // Q3：恢复的会话也可能带着强制改密标记（如改密中途刷新页面）
        var forced = !!(user && user.mustChangePassword);
        setForcedChangeMode(forced);
        if (forced) {
          $('chpwdOld').focus();
        } else {
          loadAll();
        }
      }, function () {
        clearSession();
        showAuth();
      });
    } else {
      showAuth();
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
