/* ==========================================================================
   晏宁科技 · 管理统计页（/admin/）页面脚本
   依赖后端：/api/admin/accounting/**（6 个只读端点，管理员 JWT + ROLE_ADMIN）。

   结构（与 account.js 同风格，ES5）：
   1. 状态与令牌存取（localStorage / sessionStorage 按是否勾选「记住本机」）
   2. 统一请求入口（ApiResponseAdvice 统一包壳，业务字段在 $.data，在此单点剥壳）
   3. 时间范围（默认近 30 天，与后端缺省口径一致）
   4. 各分区渲染（总览 / 分渠道 / 分产品 / 交易流水 / 趋势 / 对账差异）
  5. 二次验证（MFA）：登录第二步、安全设置面板（绑定 / 激活 / 解绑）

   登录为两阶段：密码通过后若账号已开启 MFA，服务端只回一次性票据（300 秒），
   须再过第二因子才拿得到访问令牌；故 accessToken 缺失不等于登录失败。
   ========================================================================== */

(function () {
  'use strict';

  var TOKEN_STORAGE = 'billing-admin-token';          /* 勾选「记住本机」→ localStorage */
  var TOKEN_STORAGE_SESSION = 'billing-admin-token-session'; /* 未勾选 → sessionStorage */

  var state = {
    token: '',
    from: '',
    to: '',
    tab: 'overview',
    txPage: 0,
    txTotalPages: 0
  };

  /** 半认证态：密码已通过、尚待第二因子时持有的票据（不落存储，刷新即失效） */
  var pending = { mfaTicket: '', remember: false };

  /* ======================= 0. 小工具 ======================= */

  function $(id) { return document.getElementById(id); }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* BigDecimal 序列化可能是数字也可能是字符串，统一转可显示字符串 */
  function money(amount, currency) {
    if (amount === null || amount === undefined || amount === '') return '—';
    var n = Number(amount);
    var text = isNaN(n) ? String(amount) : n.toFixed(2);
    return currency ? text + ' ' + currency : text;
  }

  function fmtDateTime(iso) {
    if (!iso) return '—';
    return String(iso).replace('T', ' ').slice(0, 19);
  }

  function none(value) {
    return (value === null || value === undefined || value === '') ? '—' : escapeHtml(value);
  }

  /* 支付状态 → 徽标语义色 */
  function statusBadge(status, desc) {
    var map = {
      SUCCESS: 'ok', PAID: 'ok',
      REFUNDED: 'warn', PENDING: 'warn', PROCESSING: 'warn',
      FAILED: 'danger', REFUND_FAILED: 'danger',
      CANCELLED: 'muted', UNKNOWN: 'muted'
    };
    var cls = map[status] || 'muted';
    return '<span class="badge badge--' + cls + '">' + escapeHtml(desc || status) + '</span>';
  }

  /* 对账差异类型 → 简短中文标签（详细说明由后端 description 提供） */
  function discrepancyBadge(type) {
    var map = {
      PAID_BUT_NO_SUCCESS_PAYMENT: '已支付无成功支付',
      AMOUNT_MISMATCH: '金额不一致',
      REFUNDED_BUT_NO_PAYMENT: '已退款无支付记录',
      SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID: '成功支付订单未标记'
    };
    return '<span class="badge badge--danger">' + escapeHtml(map[type] || type) + '</span>';
  }

  /* ======================= 1. Key 存取与解锁 ======================= */

  function loadStoredToken() {
    try {
      return localStorage.getItem(TOKEN_STORAGE) || sessionStorage.getItem(TOKEN_STORAGE_SESSION) || '';
    } catch (e) { return ''; }
  }

  function saveToken(token, remember) {
    try {
      if (remember) localStorage.setItem(TOKEN_STORAGE, token);
      else sessionStorage.setItem(TOKEN_STORAGE_SESSION, token);
    } catch (e) { /* 隐私模式下忽略 */ }
  }

  function clearToken() {
    try {
      localStorage.removeItem(TOKEN_STORAGE);
      sessionStorage.removeItem(TOKEN_STORAGE_SESSION);
    } catch (e) { /* 忽略 */ }
    state.token = '';
  }

  function showError(id, message) {
    var el = $(id);
    el.textContent = message;
    el.hidden = false;
  }

  function hideError(id) { $(id).hidden = true; }

  function showAuth() {
    $('authPanel').hidden = false;
    $('mainPanel').hidden = true;
  }

  function showMain() {
    $('authPanel').hidden = true;
    $('mainPanel').hidden = false;
    $('keyScopeNote').textContent =
      $('rememberKey').checked ? '（localStorage）' : '（sessionStorage，关闭标签页失效）';
  }

  /**
   * 登录第一步（邮箱 + 密码）。
   *
   * <p>账号已开启 MFA 时，服务端**不签发访问令牌**，只回一枚 300 秒的一次性票据；
   * 必须再过第二因子才拿得到令牌。故此处不能只看 accessToken 是否存在，
   * 否则会把「待二次验证」当成「登录失败」。
   */
  function login(email, password, remember) {
    hideError('authError');
    apiPost('/api/account/login', { email: email, password: password }).then(function (data) {
      if (data && data.mfaRequired && data.mfaTicket) {
        pending.mfaTicket = data.mfaTicket;
        pending.remember = remember;
        showMfaStep(data.mfaMethods);
        return;
      }
      finishLogin(data, remember, 'authError');
    }).catch(function (err) {
      showError('authError', err && err.message ? err.message : '登录失败，请检查邮箱与密码。');
    });
  }

  /** 登录第二步（第二因子）：票据换正式令牌。 */
  function verifyMfa(code) {
    if (!pending.mfaTicket) {
      backToLoginStep();
      showError('authError', '登录票据已失效，请重新输入密码。');
      return;
    }
    hideError('mfaLoginError');
    apiPost('/api/account/mfa/verify', { ticket: pending.mfaTicket, code: code }).then(function (data) {
      finishLogin(data, pending.remember, 'mfaLoginError');
    }).catch(function (err) {
      showError('mfaLoginError', err && err.message ? err.message : '验证失败，请重试。');
    });
  }

  /** 请求邮箱兜底验证码（认证器不可用时的恢复路径）。 */
  function sendMfaEmailCode() {
    if (!pending.mfaTicket) {
      backToLoginStep();
      showError('authError', '登录票据已失效，请重新输入密码。');
      return;
    }
    hideError('mfaLoginError');
    var email = $('loginEmail').value.trim();
    apiPost('/api/account/mfa/challenge', { ticket: pending.mfaTicket }).then(function () {
      /* 成功提示不占用错误位（那里是红色语义），改写说明文案 */
      $('mfaStepDesc').textContent =
        '验证码已发送至 ' + email + '，请在下方输入（有效期 10 分钟）。';
      $('mfaLoginCode').focus();
    }).catch(function (err) {
      showError('mfaLoginError', err && err.message ? err.message : '验证码发送失败，请稍后重试。');
    });
  }

  /** 两步共用的收尾：校验令牌与非管理员拦截，均落在同一处。 */
  function finishLogin(data, remember, errorId) {
    var token = data && data.accessToken;
    var role = data && data.user && data.user.role;
    if (!token) { showError(errorId, '登录失败，未返回令牌。'); return; }
    if (role !== 'ADMIN') {
      showError(errorId, '该账号不是管理员，无法进入管理后台。');
      return;
    }
    state.token = token;
    saveToken(token, remember);
    pending.mfaTicket = null;
    pending.remember = false;
    $('loginPassword').value = '';
    $('mfaLoginCode').value = '';
    $('loginStep').hidden = false;
    $('mfaStep').hidden = true;
    showMain();
    resetRangeToDefault();
    loadTab(state.tab);
  }

  function showMfaStep(methods) {
    hideError('authError');
    hideError('mfaLoginError');
    $('loginStep').hidden = true;
    $('mfaStep').hidden = false;
    var emailFallback = !methods || methods.indexOf('EMAIL') !== -1;
    $('mfaStepDesc').textContent = emailFallback
      ? '密码已通过。请输入认证器 App 显示的 6 位动态码；认证器不可用时可改用邮箱验证码。'
      : '密码已通过。请输入认证器 App 显示的 6 位动态码。';
    $('mfaEmailBtn').hidden = !emailFallback;
    $('mfaLoginCode').value = '';
    $('mfaLoginCode').focus();
  }

  function backToLoginStep() {
    pending.mfaTicket = null;
    pending.remember = false;
    $('mfaLoginCode').value = '';
    $('mfaStep').hidden = true;
    $('loginStep').hidden = false;
    hideError('mfaLoginError');
    $('loginPassword').focus();
  }

  function lock() {
    clearToken();
    pending.mfaTicket = null;
    pending.remember = false;
    $('loginEmail').value = '';
    $('loginPassword').value = '';
    $('mfaLoginCode').value = '';
    $('mfaStep').hidden = true;
    $('loginStep').hidden = false;
    hideError('mfaLoginError');
    showAuth();
  }

  /* 401/403：令牌失效或无权限——清掉本地令牌，退回登录卡片 */
  function handleAuthFailure() {
    lock();
    showError('authError', '登录已失效或无管理员权限，请重新登录。');
  }

  /* ======================= 2. 统一请求入口 ======================= */

  function buildApiError(status, payload) {
    var rawCode = payload && (payload.code || (payload.error && payload.error.code));
    var message = payload && (payload.message || (payload.error && payload.error.message));
    return {
      kind: 'api',
      status: status,
      code: rawCode ? String(rawCode) : ('HTTP_' + status),
      message: String(message || '')
    };
  }

  /**
   * POST：与 requestJson 同源的剥壳逻辑（业务字段在 $.data）。
   *
   * @param withAuth 需要访问令牌时传 true（管理端 MFA 端点）。登录与半认证的
   *                 /api/account/mfa/** 端点不带令牌——此时 401/403 的语义是
   *                 「凭据错误」而非「登录失效」，故不触发 handleAuthFailure，
   *                 否则会把「密码错」误报成「登录已失效」。
   */
  function apiPost(url, body, withAuth) {
    var headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (withAuth) { headers['Authorization'] = 'Bearer ' + state.token; }
    return fetch(url, {
      method: 'POST',
      headers: headers,
      body: JSON.stringify(body)
    }).then(function (res) {
      return res.text().then(function (raw) {
        var payload = null;
        if (raw) { try { payload = JSON.parse(raw); } catch (e) { payload = null; } }
        if (res.status === 401 || res.status === 403) {
          if (withAuth) { handleAuthFailure(); throw buildApiError(res.status, payload); }
          throw { kind: 'api', status: res.status, code: 'AUTH', message: '邮箱或密码错误，或无管理员权限。' };
        }
        if (!res.ok) throw buildApiError(res.status, payload);
        return payload && payload.data ? payload.data : (payload || {});
      });
    });
  }

  /**
   * 统一请求入口：服务端经 ApiResponseAdvice 统一包壳，业务字段在 $.data，
   * 在此单点剥壳后向上层返回业务对象（保持对无壳扁平结构的兼容）。
   */
  function requestJson(url) {
    return fetch(url, {
      method: 'GET',
      headers: { 'Accept': 'application/json', 'Authorization': 'Bearer ' + state.token }
    }).then(function (res) {
      return res.text().then(function (raw) {
        var payload = null;
        if (raw) {
          try { payload = JSON.parse(raw); } catch (e) { payload = null; }
        }
        if (res.status === 401 || res.status === 403) {
          handleAuthFailure();
          throw buildApiError(res.status, payload);
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

  /* ======================= 3. 时间范围 ======================= */

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function toInputValue(date) {
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate())
      + 'T' + pad(date.getHours()) + ':' + pad(date.getMinutes());
  }

  /* datetime-local 值 → 后端 ISO-8601（补秒） */
  function toQueryParam(inputValue) {
    if (!inputValue) return '';
    return inputValue.length === 16 ? inputValue + ':00' : inputValue;
  }

  function resetRangeToDefault() {
    var now = new Date();
    var from = new Date(now.getTime() - 30 * 24 * 3600 * 1000);
    $('fromInput').value = toInputValue(from);
    $('toInput').value = toInputValue(now);
    syncRangeFromInputs();
    markPreset('30d');
  }

  function syncRangeFromInputs() {
    state.from = toQueryParam($('fromInput').value);
    state.to = toQueryParam($('toInput').value);
  }

  function applyRange() {
    var from = $('fromInput').value;
    var to = $('toInput').value;
    if (!from || !to) {
      showError('rangeError', '请填写起始与结束时间。');
      return;
    }
    if (from > to) {
      showError('rangeError', '起始时间不能晚于结束时间。');
      return;
    }
    hideError('rangeError');
    syncRangeFromInputs();
    markPreset('');
    loadTab(state.tab);
  }

  function applyPreset(name) {
    var now = new Date();
    var from = new Date(now);
    if (name === 'today') from.setHours(0, 0, 0, 0);
    else if (name === '7d') from = new Date(now.getTime() - 7 * 24 * 3600 * 1000);
    else if (name === '30d') from = new Date(now.getTime() - 30 * 24 * 3600 * 1000);
    else if (name === 'month') from = new Date(now.getFullYear(), now.getMonth(), 1);
    $('fromInput').value = toInputValue(from);
    $('toInput').value = toInputValue(now);
    syncRangeFromInputs();
    markPreset(name);
    loadTab(state.tab);
  }

  function markPreset(name) {
    var chips = document.querySelectorAll('.chip');
    for (var i = 0; i < chips.length; i++) {
      chips[i].classList.toggle('is-active', chips[i].getAttribute('data-preset') === name);
    }
  }

  function rangeQuery() {
    var parts = [];
    if (state.from) parts.push('from=' + encodeURIComponent(state.from));
    if (state.to) parts.push('to=' + encodeURIComponent(state.to));
    return parts.length ? '?' + parts.join('&') : '';
  }

  /* ======================= 4. 分区加载与渲染 ======================= */

  var TAB_PANEL_PREFIX = 'panel-';

  function switchTab(tab) {
    state.tab = tab;
    var items = document.querySelectorAll('.tabs__item');
    for (var i = 0; i < items.length; i++) {
      var active = items[i].getAttribute('data-tab') === tab;
      items[i].classList.toggle('is-active', active);
      items[i].setAttribute('aria-selected', active ? 'true' : 'false');
    }
    var sections = document.querySelectorAll('.data-panel');
    for (var j = 0; j < sections.length; j++) {
      sections[j].hidden = sections[j].id !== TAB_PANEL_PREFIX + tab;
    }
    /* 时间范围工具栏只服务于统计类分区；安全设置与 License 处置都与时间无关 */
    $('rangePanel').hidden = (tab === 'security' || tab === 'license');
  }

  function loadTab(tab) {
    switchTab(tab);
    if (tab === 'overview') loadOverview();
    else if (tab === 'channel') loadChannel();
    else if (tab === 'product') loadProduct();
    else if (tab === 'transactions') loadTransactions();
    else if (tab === 'trend') loadTrend();
    else if (tab === 'discrepancies') loadDiscrepancies();
    else if (tab === 'license') loadLicense();
    else if (tab === 'security') loadMfa();
  }

  /* 通用：一个分区的加载/空态/错误切换 */
  function renderPanel(prefix, errorText, rowsHtml) {
    if (errorText) {
      showError(prefix + 'Error', errorText);
      $(prefix + 'TableWrap').hidden = true;
      $(prefix + 'Empty').hidden = true;
      return;
    }
    hideError(prefix + 'Error');
    if (rowsHtml) {
      $(prefix + 'TableWrap').innerHTML = rowsHtml;
      $(prefix + 'TableWrap').hidden = false;
      $(prefix + 'Empty').hidden = true;
    } else {
      $(prefix + 'TableWrap').hidden = true;
      $(prefix + 'Empty').hidden = false;
    }
  }

  function errorTextOf(err) {
    if (err.kind === 'network') return '网络错误：无法连接服务，请稍后重试。';
    if (err.status === 401 || err.status === 403) return null; /* 已由 handleAuthFailure 处理 */
    return '查询失败：' + (err.message || err.code || ('HTTP_' + err.status));
  }

  function table(headers, rows) {
    var html = '<table class="data-table"><thead><tr>';
    for (var i = 0; i < headers.length; i++) {
      html += '<th' + (headers[i].num ? ' class="num"' : '') + '>' + escapeHtml(headers[i].label) + '</th>';
    }
    html += '</tr></thead><tbody>';
    html += rows;
    html += '</tbody></table>';
    return html;
  }

  /* ---- 收入总览 ---- */
  function loadOverview() {
    renderPanel('overview', '', '');
    requestJson('/api/admin/accounting/overview' + rangeQuery()).then(function (data) {
      $('overviewRangeNote').textContent =
        '区间：' + fmtDateTime(data.from) + ' ~ ' + fmtDateTime(data.to) + ' · 订单总数 ' + data.totalOrderCount;
      var summaries = data.summaries || [];
      if (!summaries.length) { renderPanel('overview', '', ''); return; }
      var rows = '';
      for (var i = 0; i < summaries.length; i++) {
        var s = summaries[i];
        rows += '<tr>'
          + '<td>' + escapeHtml(s.currency) + '</td>'
          + '<td class="num">' + s.orderCount + '</td>'
          + '<td class="num">' + s.paidOrderCount + '</td>'
          + '<td class="num">' + escapeHtml(money(s.gmv)) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(s.grossReceived)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.refunded)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.net)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.avgOrderValue)) + '</td>'
          + '</tr>';
      }
      renderPanel('overview', '', table([
        { label: '币种' }, { label: '订单数', num: true }, { label: '已支付订单', num: true },
        { label: 'GMV', num: true }, { label: '实收', num: true }, { label: '已退款', num: true },
        { label: '净收入', num: true }, { label: '客单价', num: true }
      ], rows));
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('overview', text, '');
    });
  }

  /* ---- 分渠道 ---- */
  function loadChannel() {
    renderPanel('channel', '', '');
    requestJson('/api/admin/accounting/by-channel' + rangeQuery()).then(function (list) {
      var rows = '';
      for (var i = 0; i < list.length; i++) {
        var s = list[i];
        rows += '<tr>'
          + '<td>' + none(s.channelName || s.channel) + '</td>'
          + '<td>' + escapeHtml(s.currency) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(s.grossReceived)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.refunded)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.net)) + '</td>'
          + '<td class="num">' + s.orderCount + '</td>'
          + '</tr>';
      }
      renderPanel('channel', '', list.length ? table([
        { label: '渠道' }, { label: '币种' }, { label: '实收', num: true },
        { label: '已退款', num: true }, { label: '净收入', num: true }, { label: '订单数', num: true }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('channel', text, '');
    });
  }

  /* ---- 分产品 ---- */
  function loadProduct() {
    renderPanel('product', '', '');
    requestJson('/api/admin/accounting/by-product' + rangeQuery()).then(function (list) {
      var rows = '';
      for (var i = 0; i < list.length; i++) {
        var s = list[i];
        rows += '<tr>'
          + '<td class="mono">' + none(s.sku) + '</td>'
          + '<td>' + none(s.name) + '</td>'
          + '<td>' + none(s.tier) + '</td>'
          + '<td>' + none(s.billingCycle) + '</td>'
          + '<td>' + escapeHtml(s.currency) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(s.grossReceived)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.refunded)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.net)) + '</td>'
          + '<td class="num">' + s.orderCount + '</td>'
          + '</tr>';
      }
      renderPanel('product', '', list.length ? table([
        { label: 'SKU' }, { label: '产品' }, { label: '档位' }, { label: '计费周期' },
        { label: '币种' }, { label: '实收', num: true }, { label: '已退款', num: true },
        { label: '净收入', num: true }, { label: '订单数', num: true }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('product', text, '');
    });
  }

  /* ---- 交易流水（分页） ---- */
  function loadTransactions() {
    renderPanel('tx', '', '');
    var channel = $('txChannel').value;
    var status = $('txStatus').value;
    var currency = $('txCurrency').value.trim();
    var parts = rangeQuery().substring(1).split('&');
    if (channel) parts.push('channel=' + encodeURIComponent(channel));
    if (status) parts.push('status=' + encodeURIComponent(status));
    if (currency) parts.push('currency=' + encodeURIComponent(currency));
    parts.push('page=' + state.txPage);
    parts.push('size=20');
    requestJson('/api/admin/accounting/transactions?' + parts.join('&')).then(function (page) {
      var content = page.content || [];
      var rows = '';
      for (var i = 0; i < content.length; i++) {
        var t = content[i];
        rows += '<tr>'
          + '<td>' + escapeHtml(fmtDateTime(t.createdAt)) + '</td>'
          + '<td>' + none(t.channelName || t.channel) + '</td>'
          + '<td>' + statusBadge(t.status, t.statusDesc) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(t.amount, t.currency)) + '</td>'
          + '<td class="mono">' + none(t.orderId) + '</td>'
          + '<td class="mono">' + none(t.transactionId) + '</td>'
          + '<td>' + escapeHtml(fmtDateTime(t.paidAt)) + '</td>'
          + '</tr>';
      }
      var rowsHtml = content.length ? table([
        { label: '时间' }, { label: '渠道' }, { label: '状态' }, { label: '金额', num: true },
        { label: '订单 ID' }, { label: '交易号' }, { label: '支付时间' }
      ], rows) : '';
      renderPanel('tx', '', rowsHtml);
      state.txTotalPages = page.totalPages || 0;
      var pager = $('txPager');
      pager.hidden = !(page.totalPages > 1);
      if (!pager.hidden) {
        $('txPageInfo').textContent = '第 ' + ((page.number || 0) + 1) + ' / ' + page.totalPages
          + ' 页 · 共 ' + page.totalElements + ' 条';
        $('txPrev').disabled = page.first === true;
        $('txNext').disabled = page.last === true;
      }
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('tx', text, '');
    });
  }

  /* ---- 趋势 ---- */
  function loadTrend() {
    renderPanel('trend', '', '');
    var granularity = $('trendGranularity').value;
    var query = rangeQuery();
    query += (query ? '&' : '?') + 'granularity=' + encodeURIComponent(granularity);
    requestJson('/api/admin/accounting/trend' + query).then(function (list) {
      var rows = '';
      for (var i = 0; i < list.length; i++) {
        var p = list[i];
        rows += '<tr>'
          + '<td class="mono">' + escapeHtml(p.bucket) + '</td>'
          + '<td>' + escapeHtml(p.currency) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(p.grossReceived)) + '</td>'
          + '<td class="num">' + escapeHtml(money(p.refunded)) + '</td>'
          + '<td class="num">' + escapeHtml(money(p.net)) + '</td>'
          + '<td class="num">' + p.orderCount + '</td>'
          + '</tr>';
      }
      renderPanel('trend', '', list.length ? table([
        { label: '区间' }, { label: '币种' }, { label: '实收', num: true },
        { label: '已退款', num: true }, { label: '净收入', num: true }, { label: '订单数', num: true }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('trend', text, '');
    });
  }

  /* ---- 对账差异 ---- */
  function loadDiscrepancies() {
    renderPanel('disc', '', '');
    requestJson('/api/admin/accounting/discrepancies' + rangeQuery()).then(function (list) {
      var rows = '';
      for (var i = 0; i < list.length; i++) {
        var d = list[i];
        rows += '<tr>'
          + '<td>' + escapeHtml(fmtDateTime(d.foundAt)) + '</td>'
          + '<td>' + discrepancyBadge(d.type) + '</td>'
          + '<td>' + escapeHtml(d.description) + '</td>'
          + '<td class="mono">' + none(d.orderNumber || d.orderId) + '</td>'
          + '<td class="num">' + escapeHtml(money(d.orderAmount, d.orderCurrency)) + '</td>'
          + '<td class="num">' + escapeHtml(money(d.paidAmount, d.orderCurrency)) + '</td>'
          + '<td>' + none(d.orderStatus) + '</td>'
          + '</tr>';
      }
      renderPanel('disc', '', list.length ? table([
        { label: '发现时间' }, { label: '类型' }, { label: '说明' }, { label: '订单' },
        { label: '订单金额', num: true }, { label: '支付金额', num: true }, { label: '订单状态' }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('disc', text, '');
    });
  }

  /* ---- 安全设置 · 二次验证（MFA） ---- */

  /**
   * 载入绑定状态。
   *
   * <p>密钥只在 enroll 响应里回显一次且服务端**不落原值**（库中为 AES-GCM 密文），
   * 故刷新/切回本分区一律回到「生成密钥」入口，不留待激活的中间态——
   * 否则会出现「界面在等激活、而密钥已不可见」的死角。
   */
  function loadMfa() {
    hideError('mfaStatusError');
    $('mfaStatusText').hidden = true;
    $('mfaEnrollBox').hidden = true;
    $('mfaSecretBox').hidden = true;
    $('mfaUnbindBox').hidden = true;
    $('mfaSecretText').textContent = '';
    $('mfaPassword').value = '';
    $('mfaUnbindPassword').value = '';
    $('mfaUnbindCode').value = '';
    $('mfaActivateCode').value = '';
    hideError('mfaEnrollError');
    hideError('mfaActivateError');
    hideError('mfaUnbindError');

    requestJson('/api/admin/mfa/status').then(function (data) {
      renderMfaStatus(data);
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) showError('mfaStatusError', text);
    });
  }

  function renderMfaStatus(data) {
    var enabled = !!(data && data.enabled);
    var html = enabled
      ? '当前状态：<span class="badge badge--ok">已开启</span>'
      : '当前状态：<span class="badge badge--muted">未开启</span>';
    if (enabled && data.enrolledAt) html += ' · 开启于 ' + escapeHtml(fmtDateTime(data.enrolledAt));
    html += data && data.emailFallbackEnabled
      ? ' · 邮箱兜底：<span class="badge badge--ok">已开放</span>'
      : ' · 邮箱兜底：<span class="badge badge--warn">未开放</span>';
    $('mfaStatusText').innerHTML = html;
    $('mfaStatusText').hidden = false;

    if (enabled) $('mfaUnbindBox').hidden = false;
    else $('mfaEnrollBox').hidden = false;
  }

  /** 生成密钥（须当前密码）。成功后进入「待激活」态，密钥当场展示。 */
  function enrollMfa() {
    hideError('mfaEnrollError');
    var password = $('mfaPassword').value;
    if (!password) { showError('mfaEnrollError', '请输入当前密码。'); return; }

    $('mfaEnrollBtn').disabled = true;
    apiPost('/api/admin/mfa/enroll', { password: password }, true).then(function (data) {
      $('mfaEnrollBtn').disabled = false;
      $('mfaPassword').value = '';
      $('mfaSecretText').textContent = (data && data.secret) || '';
      $('mfaSecretBox').hidden = false;
      $('mfaEnrollBox').hidden = true;
      $('mfaActivateCode').value = '';
      hideError('mfaActivateError');
      $('mfaActivateCode').focus();
    }).catch(function (err) {
      $('mfaEnrollBtn').disabled = false;
      showError('mfaEnrollError', err && err.message ? err.message : '生成失败，请重试。');
    });
  }

  /** 用认证器当前动态码激活；成功后服务端置 mfa_enabled。 */
  function activateMfa() {
    hideError('mfaActivateError');
    var code = $('mfaActivateCode').value.trim();
    if (!/^\d{6}$/.test(code)) { showError('mfaActivateError', '请输入 6 位数字动态码。'); return; }

    $('mfaActivateBtn').disabled = true;
    apiPost('/api/admin/mfa/activate', { code: code }, true).then(function () {
      $('mfaActivateBtn').disabled = false;
      $('mfaActivateCode').value = '';
      loadMfa();
    }).catch(function (err) {
      $('mfaActivateBtn').disabled = false;
      showError('mfaActivateError', err && err.message ? err.message : '激活失败，请重试。');
    });
  }

  /** 解绑（须密码 + 动态码或邮箱兜底码）——两道都验，防「session 被劫持后静默关掉防线」。 */
  function unbindMfa() {
    hideError('mfaUnbindError');
    var password = $('mfaUnbindPassword').value;
    var code = $('mfaUnbindCode').value.trim();
    if (!password) { showError('mfaUnbindError', '请输入当前密码。'); return; }
    if (!code) { showError('mfaUnbindError', '请输入认证器动态码或邮箱验证码。'); return; }

    $('mfaUnbindBtn').disabled = true;
    apiPost('/api/admin/mfa/unbind', { password: password, code: code }, true).then(function () {
      $('mfaUnbindBtn').disabled = false;
      $('mfaUnbindPassword').value = '';
      $('mfaUnbindCode').value = '';
      loadMfa();
    }).catch(function (err) {
      $('mfaUnbindBtn').disabled = false;
      showError('mfaUnbindError', err && err.message ? err.message : '解绑失败，请重试。');
    });
  }

  /* ---- License 处置（plan-7.0 主体三） ---- */

  /** 上次查询结果（行内「处置」需按 key 取回完整对象） */
  var licList = [];
  /** 当前选中待处置的许可证；null = 未选中 */
  var licSelected = null;
  /** 上次查询条件：处置成功后原样重查，省得管理员再手工查一遍 */
  var licLastQuery = null;

  /** 进入分区：只清残留、不自动查询（无关键词可查，避免无谓请求） */
  function loadLicense() {
    hideError('licError');
    $('licResult').hidden = true;
    licLastQuery = null;
    renderLicTable(null);
    closeLicAction();
  }

  /**
   * 查询：按密钥精确查单件，或按邮箱列出名下全部（含失效件）。
   *
   * @param byEmail    true = 按客户邮箱列表查询
   * @param keepResult 保留已有处置结果提示（处置后刷新时用，避免把新密钥提示冲掉）
   */
  function searchLicense(byEmail, keepResult) {
    var term = $('licSearchInput').value.trim();
    if (!term) {
      showError('licError', byEmail ? '请输入客户邮箱。' : '请输入 License 密钥。');
      return;
    }
    hideError('licError');
    if (!keepResult) { $('licResult').hidden = true; }
    closeLicAction();
    licLastQuery = { byEmail: byEmail, term: term };

    var url = byEmail
      ? '/api/admin/licenses?customerEmail=' + encodeURIComponent(term)
      : '/api/admin/licenses/' + encodeURIComponent(term);

    requestJson(url).then(function (data) {
      // 单件查询返回对象、列表查询返回数组，此处统一收敛为数组
      var list = byEmail
        ? (Array.isArray(data) ? data : [])
        : (data && data.licenseKey ? [data] : []);
      renderLicTable(list);
    }).catch(function (err) {
      renderLicTable(null);
      var text = errorTextOf(err);
      if (text) showError('licError', text);
    });
  }

  /** License 状态 → 徽标语义色（REISSUED 是「旧证退出使用」，用中性色与 REVOKED 区分） */
  function licenseStatusBadge(status) {
    var cls = { ACTIVE: 'ok', EXPIRED: 'warn', REVOKED: 'danger', REISSUED: 'muted' };
    var label = { ACTIVE: '有效', EXPIRED: '已过期', REVOKED: '已作废', REISSUED: '已重发' };
    return '<span class="badge badge--' + (cls[status] || 'muted') + '">'
      + escapeHtml(label[status] || status || '未知') + '</span>';
  }

  function renderLicTable(list) {
    licList = list || [];
    licSelected = null;
    if (!licList.length) {
      $('licTableWrap').hidden = true;
      $('licEmpty').hidden = false;
      return;
    }
    var rows = '';
    for (var i = 0; i < licList.length; i++) {
      var l = licList[i];
      rows += '<tr>'
        + '<td><code class="mono">' + escapeHtml(l.licenseKey) + '</code></td>'
        + '<td>' + licenseStatusBadge(l.status) + '</td>'
        + '<td>' + none(l.customerEmail) + '</td>'
        + '<td><code class="mono">' + none(l.machineCode) + '</code></td>'
        + '<td>' + escapeHtml(fmtDateTime(l.expiresAt)) + '</td>'
        + '<td><button class="btn btn--ghost btn--sm" type="button" data-lic="'
        + escapeHtml(l.licenseKey) + '">处置</button></td>'
        + '</tr>';
    }
    $('licTableWrap').innerHTML = table([
      { label: 'License 密钥' }, { label: '状态' }, { label: '客户邮箱' },
      { label: '机器码' }, { label: '到期时间' }, { label: '操作' }
    ], rows);
    $('licTableWrap').hidden = false;
    $('licEmpty').hidden = true;
  }

  function openLicAction(licenseKey) {
    var found = null;
    for (var i = 0; i < licList.length; i++) {
      if (licList[i].licenseKey === licenseKey) { found = licList[i]; break; }
    }
    if (!found) { return; }
    licSelected = found;
    $('licActionKey').textContent = found.licenseKey;
    $('licActionType').value = 'unbind';
    $('licNewMachine').value = '';
    $('licForce').checked = false;
    $('licReason').value = '';
    hideError('licActionError');
    syncLicActionFields();
    $('licActionBox').hidden = false;
    $('licActionBox').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  /** 动作切换：只有「失效重发」需要新机器码与越限开关 */
  function syncLicActionFields() {
    var isReissue = $('licActionType').value === 'reissue';
    $('licMachineField').hidden = !isReissue;
    $('licForceField').hidden = !isReissue;
  }

  function closeLicAction() {
    licSelected = null;
    $('licActionBox').hidden = true;
    $('licActionKey').textContent = '';
    hideError('licActionError');
  }

  function submitLicAction() {
    if (!licSelected) { closeLicAction(); return; }
    hideError('licActionError');

    var type = $('licActionType').value;
    var reason = $('licReason').value.trim();
    var key = licSelected.licenseKey;

    if (licSelected.status === 'REVOKED') {
      showError('licActionError', '该许可证已作废（终态），无法再处置。');
      return;
    }
    // 作废不可逆，故前端强制填原因（端点侧 reason 仍可选，以兼容既有直调方）
    if (type === 'revoke' && !reason) {
      showError('licActionError', '作废不可逆，请填写处置原因。');
      return;
    }

    var base = '/api/admin/licenses/' + encodeURIComponent(key);
    var url;
    if (type === 'unbind') {
      url = base + '/unbind' + (reason ? '?reason=' + encodeURIComponent(reason) : '');
    } else if (type === 'revoke') {
      url = base + '/revoke?reason=' + encodeURIComponent(reason);
    } else {
      url = base + '/reissue?force=' + ($('licForce').checked ? 'true' : 'false')
        + '&newMachineId=' + encodeURIComponent($('licNewMachine').value.trim());
      if (reason) { url += '&reason=' + encodeURIComponent(reason); }
    }

    $('licActionSubmit').disabled = true;
    apiPost(url, {}, true).then(function (data) {
      $('licActionSubmit').disabled = false;
      closeLicAction();
      var note;
      if (type === 'unbind') {
        note = '已解绑设备绑定——授权仍有效，用户可在新机重新激活。';
      } else if (type === 'revoke') {
        note = '已作废该许可证（不可逆）。';
      } else {
        note = '已失效原证并签发新密钥，新密钥同时会出现在该用户的账号页：'
          + '<code>' + escapeHtml((data && data.licenseKey) || '(响应未返回，请重新查询)') + '</code>';
      }
      $('licResult').innerHTML = note;
      $('licResult').hidden = false;
      if (licLastQuery) { searchLicense(licLastQuery.byEmail, true); }
    }).catch(function (err) {
      $('licActionSubmit').disabled = false;
      showError('licActionError', err && err.message ? err.message : '处置失败，请重试。');
    });
  }

  /* ======================= 5. 事件绑定与初始化 ======================= */

  function bindEvents() {
    $('unlockForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var email = $('loginEmail').value.trim();
      var password = $('loginPassword').value;
      if (!email || !password) { showError('authError', '请输入管理员邮箱与密码。'); return; }
      hideError('authError');
      login(email, password, $('rememberKey').checked);
    });

    /* 登录第二步：第二因子 */
    $('mfaForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var code = $('mfaLoginCode').value.trim();
      if (!/^\d{6}$/.test(code)) { showError('mfaLoginError', '请输入 6 位数字动态码。'); return; }
      verifyMfa(code);
    });
    $('mfaEmailBtn').addEventListener('click', sendMfaEmailCode);
    $('mfaBackBtn').addEventListener('click', backToLoginStep);

    /* 安全设置面板 */
    $('mfaEnrollBtn').addEventListener('click', enrollMfa);
    $('mfaActivateBtn').addEventListener('click', activateMfa);
    $('mfaUnbindBtn').addEventListener('click', unbindMfa);

    /* License 处置面板 */
    $('licSearchByKey').addEventListener('click', function () { searchLicense(false); });
    $('licSearchByEmail').addEventListener('click', function () { searchLicense(true); });
    $('licSearchInput').addEventListener('keydown', function (e) {
      /* 回车即查：含 @ 视为邮箱，否则视为密钥——省一次点选 */
      if (e.key === 'Enter') {
        e.preventDefault();
        searchLicense($('licSearchInput').value.indexOf('@') !== -1);
      }
    });
    $('licTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-lic]') : null;
      if (btn) { openLicAction(btn.getAttribute('data-lic')); }
    });
    $('licActionType').addEventListener('change', syncLicActionFields);
    $('licActionSubmit').addEventListener('click', submitLicAction);
    $('licActionCancel').addEventListener('click', closeLicAction);

    $('lockBtn').addEventListener('click', lock);

    $('applyRange').addEventListener('click', applyRange);

    var chips = document.querySelectorAll('.chip');
    for (var i = 0; i < chips.length; i++) {
      chips[i].addEventListener('click', function () { applyPreset(this.getAttribute('data-preset')); });
    }

    var tabs = document.querySelectorAll('.tabs__item');
    for (var j = 0; j < tabs.length; j++) {
      tabs[j].addEventListener('click', function () { loadTab(this.getAttribute('data-tab')); });
    }

    $('txApply').addEventListener('click', function () {
      state.txPage = 0;
      loadTransactions();
    });
    $('txPrev').addEventListener('click', function () {
      if (state.txPage > 0) { state.txPage -= 1; loadTransactions(); }
    });
    $('txNext').addEventListener('click', function () {
      if (state.txPage < state.txTotalPages - 1) { state.txPage += 1; loadTransactions(); }
    });

    $('trendApply').addEventListener('click', loadTrend);
    $('trendGranularity').addEventListener('change', loadTrend);
  }

  function init() {
    bindEvents();
    var stored = loadStoredToken();
    if (stored) {
      $('rememberKey').checked = !!localStorage.getItem(TOKEN_STORAGE);
      state.token = stored;
      showMain();
      resetRangeToDefault();
      loadTab(state.tab);
    } else {
      showAuth();
      resetRangeToDefault();
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
