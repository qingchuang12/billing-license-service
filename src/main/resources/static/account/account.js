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
    // plan-4.1：订单区退款入口所需的缓存（语言切换时按缓存重渲染，保住插值文案）
    orders: [],
    refundOrder: null,
    // plan-7.0：许可证区解绑入口所需的缓存（同上）
    licenses: [],
    unbindLicense: null,
    // plan-7.0 账户基础功能 / Q3：管理员代重置后须先改密，此时隐藏资产区块只留改密入口
    mustChangePassword: false,
    // 两阶段登录（plan-7.0 / M3）：密码已过、待第二因子时持有的一次性票据。
    // 只存内存（刷新即失效），与找回表单的 localStorage 令牌不同源。
    mfaTicket: '',
    // 第二因子可用方式缓存：语言切换时据此重写 mfaStepDesc（含邮箱兜底可用性）
    mfaMethods: null,
    mfaEmailReadyAt: 0
  };

  function $(id) { return document.getElementById(id); }

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
      'auth.emailLabel': '电子邮箱',
      'auth.passwordLabel': '密码',
      'auth.login': '登录',
      'auth.loginBusy': '登录中…',
      'auth.toReset': '未注册 / 忘记密码？用邮箱验证码设置密码',
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
      'license.desc': '激活软件所用的许可证；点击「复制」后粘贴到客户端激活框。',
      'license.key': '许可证',
      'license.product': '产品',
      'license.status': '状态',
      'license.issuedAt': '签发时间',
      'license.expiresAt': '有效期至',
      'license.machine': '绑定机器码',
      'license.error': '证书加载失败',
      'sub.title': '我的订阅',
      'sub.desc': '订阅由支付渠道托管续费；取消后本周期结束即失效。',
      'sub.product': '产品',
      'sub.channel': '渠道',
      'sub.status': '状态',
      'sub.period': '当前周期',
      'sub.cancelNote': '到期不再续订',
      'sub.error': '订阅加载失败',
      'order.title': '我的订单',
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
      'common.copied': '已复制',
      'common.none': '—',
      'common.forever': '永久',
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
      'auth.emailLabel': 'Email',
      'auth.passwordLabel': 'Password',
      'auth.login': 'Sign in',
      'auth.loginBusy': 'Signing in…',
      'auth.toReset': 'Forgot password? Set a new one with an email code',
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
      'license.desc': 'Licenses used to activate the software; click "Copy" and paste into the activation box.',
      'license.key': 'License',
      'license.product': 'Product',
      'license.status': 'Status',
      'license.issuedAt': 'Issued',
      'license.expiresAt': 'Expires',
      'license.machine': 'Machine',
      'license.error': 'Failed to load licenses',
      'sub.title': 'My Subscriptions',
      'sub.desc': 'Subscriptions are billed by the payment provider; after cancellation they end with the current period.',
      'sub.product': 'Product',
      'sub.channel': 'Channel',
      'sub.status': 'Status',
      'sub.period': 'Current period',
      'sub.cancelNote': 'cancels at period end',
      'sub.error': 'Failed to load subscriptions',
      'order.title': 'My Orders',
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
      'common.copied': 'Copied',
      'common.none': '—',
      'common.forever': 'Lifetime',
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
   * 故直接隐藏、只留改密区块，避免一排报错。改密成功后回到登录态。
   */
  function setForcedChangeMode(on) {
    state.mustChangePassword = !!on;
    $('licensePanel').hidden = state.mustChangePassword;
    $('subPanel').hidden = state.mustChangePassword;
    $('orderPanel').hidden = state.mustChangePassword;
    $('chpwdForceNote').hidden = !state.mustChangePassword;
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
    $('mfaCode').value = '';
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
    $('mfaCode').value = '';
    $('mfaLoginForm').hidden = false;
    $('mfaCode').focus();
  }

  /** 返回密码表单：丢弃票据（旧票据随 tokenVersion 递增或 TTL 自然失效，不主动复用）。 */
  function backToLoginForm() {
    state.mfaTicket = '';
    state.mfaMethods = null;
    $('mfaCode').value = '';
    $('mfaLoginForm').hidden = true;
    $('loginForm').hidden = false;
    setFormError('mfaError', '');
    $('loginPassword').focus();
  }

  /** 第二步提交：票据 + 动态码（或邮箱兜底码）换正式令牌。 */
  function handleMfaVerify(event) {
    event.preventDefault();
    var btn = $('mfaVerifyBtn');
    setFormError('mfaError', '');
    var code = $('mfaCode').value.trim();
    if (!code) return setFormError('mfaError', t('err.required'));
    if (!state.mfaTicket) {
      backToLoginForm();
      setFormError('formError', t('mfa.ticketLost'));
      return;
    }

    setBusy(btn, t('mfa.verifyBusy'));
    apiPost('/api/account/mfa/verify', { ticket: state.mfaTicket, code: code })
      .then(function (data) {
        unsetBusy(btn);
        finishAccountLogin(data, 'mfaError');
      }, function (err) {
        unsetBusy(btn);
        // 票据失效（过期/账号信息已变更）：留在本表单只会反复失败，退回密码步重新走
        if (err && err.code === 'MFA_TICKET_INVALID') {
          backToLoginForm();
          setFormError('formError', errText(err, 'mfa.ticketLost'));
          return;
        }
        setFormError('mfaError', errText(err, 'err.generic'));
      });
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
    if (amount === null || amount === undefined) return t('common.none');
    return amount + ' ' + (currency || '');
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
    return '<span class="badge badge--' + cls + '">' + escapeHtml(status) + '</span>';
  }

  function escapeHtml(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function setSectionError(id, key, err) {
    var el = $(id);
    el.textContent = errText(err, key);
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
      return '<tr>'
        + '<td>' + escapeHtml(item.productName || item.productSku || t('common.none')) + '</td>'
        + '<td>' + escapeHtml(item.provider || t('common.none')) + '</td>'
        + '<td>' + badge(item.status) + '</td>'
        + '<td class="mono">' + period + cancelNote + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('subBody', 'subEmpty', rows);
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
        + '<td>' + refundCell(item) + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('orderBody', 'orderEmpty', rows);
  }

  /**
   * 退款入口单元格（plan-4.1）：refundable / refundableAmount 由服务端按
   * License 剩余有效期折算后回填，前端不做资格判断，避免两处口径漂移。
   */
  function refundCell(item) {
    if (!item.refundable) return '<span class="muted">' + t('common.none') + '</span>';
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

    apiGet('/api/account/licenses').then(function (list) {
      renderLicenses(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      if (err && err.code === 'PASSWORD_CHANGE_REQUIRED') return enterForcedChange();
      setSectionError('licenseError', 'license.error', err);
    });

    apiGet('/api/account/subscriptions').then(function (list) {
      renderSubscriptions(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      if (err && err.code === 'PASSWORD_CHANGE_REQUIRED') return enterForcedChange();
      setSectionError('subError', 'sub.error', err);
    });

    apiGet('/api/account/orders').then(function (list) {
      renderOrders(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      if (err && err.code === 'PASSWORD_CHANGE_REQUIRED') return enterForcedChange();
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
        announce(result && result.fullRefund
          ? t('refund.okFull', { amount: amount })
          : t('refund.okPartial', { amount: amount }));
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
        // 设备列需刷新，才能落成「未绑定」并撤掉解绑按钮
        loadAll();
      }, function (err) {
        unsetBusy(btn);
        setFormError('unbindError', errText(err, 'err.generic'));
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

    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') return;
      if (!$('refundModal').hidden) closeRefund();
      if (!$('unbindModal').hidden) closeUnbind();
    });
  }

  function copyText(text, btn) {
    var done = function () {
      var original = t('common.copy');
      btn.textContent = t('common.copied');
      setTimeout(function () { btn.textContent = original; }, 1500);
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
    applyLang(state.lang);
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
