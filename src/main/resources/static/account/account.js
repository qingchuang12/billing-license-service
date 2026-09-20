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
    sendCodeReadyAt: 0
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
      'account.sub': '查看购买/兑换时所用邮箱名下的 License 证书、订阅与订单。',
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
      'err.required': '请填写完整信息',
      'err.invalidEmail': '邮箱格式不正确',
      'err.network': '网络异常，请稍后重试',
      'err.generic': '操作失败，请稍后重试',
      'err.sessionExpired': '登录状态已过期，请重新登录',
      'license.title': '我的证书（License）',
      'license.desc': '激活软件所用的密钥；点击「复制」后粘贴到客户端激活框。',
      'license.key': '密钥',
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
      'order.error': '订单加载失败',
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
      'err.required': 'Please fill in all fields',
      'err.invalidEmail': 'Invalid email address',
      'err.network': 'Network error, please retry later',
      'err.generic': 'Operation failed, please retry later',
      'err.sessionExpired': 'Session expired, please sign in again',
      'err.accessDenied': 'You do not have access to this resource',
      'err.invalidCredentials': 'Incorrect email or password',
      'err.emailNotPurchased': 'No purchase found for this email. Please buy or redeem first',
      'license.title': 'My Licenses',
      'license.desc': 'Keys used to activate the software; click "Copy" and paste into the activation box.',
      'license.key': 'Key',
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
      'order.error': 'Failed to load orders',
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
    ACCESS_DENIED: 'err.accessDenied'
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
      var token = data && data.accessToken;
      if (!token) return setFormError('formError', t('err.generic'));
      saveSession(token, data.user && data.user.email);
      $('loginPassword').value = '';
      showContent(data.user && data.user.email);
      loadAll();
    }, function (err) {
      unsetBusy(btn);
      setFormError('formError', errText(err, 'err.generic'));
    });
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
    // 服务端令牌吊销尽力而为：带上刚清除的令牌直接调用，结果不影响本地登出
    if (token) {
      fetch('/api/account/logout', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json' }
      }).catch(function () { /* 忽略 */ });
    }
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
    var rows = (list || []).map(function (item) {
      return '<tr>'
        + '<td><span class="key-cell"><code>' + escapeHtml(item.licenseKey) + '</code>'
        + '<button type="button" class="copy-btn" data-copy="' + escapeHtml(item.licenseKey)
        // data-i18n 让语言切换时 applyLang 一并刷新动态渲染的按钮文案
        + '" data-i18n="common.copy">' + escapeHtml(t('common.copy')) + '</button></span></td>'
        + '<td>' + escapeHtml(item.productSku || t('common.none')) + '</td>'
        + '<td>' + badge(item.status) + '</td>'
        + '<td class="mono">' + fmtDate(item.issuedAt) + '</td>'
        + '<td class="mono">' + (item.expiresAt ? fmtDate(item.expiresAt) : t('common.forever')) + '</td>'
        + '<td class="mono">' + escapeHtml(item.machineCode || t('common.none')) + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('licenseBody', 'licenseEmpty', rows);
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
    var rows = (list || []).map(function (item) {
      return '<tr>'
        + '<td class="mono">' + escapeHtml(item.orderNumber) + '</td>'
        + '<td class="mono">' + escapeHtml(money(item.totalAmount, item.currency)) + '</td>'
        + '<td>' + badge(item.status) + '</td>'
        + '<td>' + badge(item.paymentStatus) + '</td>'
        + '<td class="mono">' + fmtDate(item.createdAt) + '</td>'
        + '</tr>';
    }).join('');
    showEmpty('orderBody', 'orderEmpty', rows);
  }

  /** 拉取三个区块；任一 401 都视为会话失效，回到登录态 */
  function loadAll() {
    clearSectionError('licenseError');
    clearSectionError('subError');
    clearSectionError('orderError');

    apiGet('/api/account/licenses').then(function (list) {
      renderLicenses(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      setSectionError('licenseError', 'license.error', err);
    });

    apiGet('/api/account/subscriptions').then(function (list) {
      renderSubscriptions(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
      setSectionError('subError', 'sub.error', err);
    });

    apiGet('/api/account/orders').then(function (list) {
      renderOrders(list);
    }, function (err) {
      if (err && err.status === 401) return onSessionExpired();
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

  /* ======================= 7. 事件绑定与启动 ======================= */

  function bindEvents() {
    $('langSwitch').addEventListener('click', function () {
      applyLang(state.lang === 'zh' ? 'en' : 'zh');
    });

    $('loginForm').addEventListener('submit', handleLogin);
    $('resetForm').addEventListener('submit', handleReset);
    $('sendCodeBtn').addEventListener('click', handleSendCode);
    $('logoutBtn').addEventListener('click', handleLogout);

    // 登录 / 找回两张表单切换
    $('showResetBtn').addEventListener('click', function () {
      $('loginForm').hidden = true;
      $('resetForm').hidden = false;
      setFormError('formError', '');
    });
    $('backLoginBtn').addEventListener('click', function () {
      $('resetForm').hidden = true;
      $('loginForm').hidden = false;
      setFormError('resetError', '');
      setFormError('codeError', '');
    });

    // 复制密钥（事件委托：表格行由脚本渲染）
    document.addEventListener('click', function (event) {
      var btn = event.target.closest('[data-copy]');
      if (!btn) return;
      copyText(btn.getAttribute('data-copy'), btn);
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

  function start() {
    state.lang = readLang();
    applyLang(state.lang);
    bindEvents();

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
        loadAll();
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
