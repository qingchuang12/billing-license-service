/* ==========================================================================
   晏宁科技 · 管理统计页（/admin/）页面脚本
   依赖后端：/api/admin/accounting/**（6 个只读端点，X-API-Key + ROLE_ADMIN）。

   结构（与 account.js 同风格，ES5）：
   1. 状态与 Key 存取（localStorage / sessionStorage 按是否勾选「记住本机」）
   2. 统一请求入口（ApiResponseAdvice 统一包壳，业务字段在 $.data，在此单点剥壳）
   3. 时间范围（默认近 30 天，与后端缺省口径一致）
   4. 各分区渲染（总览 / 分渠道 / 分产品 / 交易流水 / 趋势 / 对账差异）
   ========================================================================== */

(function () {
  'use strict';

  var KEY_STORAGE = 'billing-admin-key';          /* 勾选「记住本机」→ localStorage */
  var KEY_STORAGE_SESSION = 'billing-admin-key-session'; /* 未勾选 → sessionStorage */

  var state = {
    key: '',
    from: '',
    to: '',
    tab: 'overview',
    txPage: 0,
    txTotalPages: 0
  };

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

  function loadStoredKey() {
    try {
      return localStorage.getItem(KEY_STORAGE) || sessionStorage.getItem(KEY_STORAGE_SESSION) || '';
    } catch (e) { return ''; }
  }

  function saveKey(key, remember) {
    try {
      if (remember) localStorage.setItem(KEY_STORAGE, key);
      else sessionStorage.setItem(KEY_STORAGE_SESSION, key);
    } catch (e) { /* 隐私模式下忽略 */ }
  }

  function clearKey() {
    try {
      localStorage.removeItem(KEY_STORAGE);
      sessionStorage.removeItem(KEY_STORAGE_SESSION);
    } catch (e) { /* 忽略 */ }
    state.key = '';
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

  function unlock(key, remember) {
    state.key = key;
    saveKey(key, remember);
    /* 未验证的 key 不预存视图：首次加载任一分区失败（401/403）会自动退回解锁卡片 */
    showMain();
    resetRangeToDefault();
    loadTab(state.tab);
  }

  function lock() {
    clearKey();
    $('keyInput').value = '';
    showAuth();
  }

  /* 401/403：Key 无效或无权限——清掉本地 key，退回解锁卡片 */
  function handleAuthFailure() {
    lock();
    showError('authError', '管理 API Key 无效或无 ROLE_ADMIN 权限，请重新输入。');
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
   * 统一请求入口：服务端经 ApiResponseAdvice 统一包壳，业务字段在 $.data，
   * 在此单点剥壳后向上层返回业务对象（保持对无壳扁平结构的兼容）。
   */
  function requestJson(url) {
    return fetch(url, {
      method: 'GET',
      headers: { 'Accept': 'application/json', 'X-API-Key': state.key }
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
  }

  function loadTab(tab) {
    switchTab(tab);
    if (tab === 'overview') loadOverview();
    else if (tab === 'channel') loadChannel();
    else if (tab === 'product') loadProduct();
    else if (tab === 'transactions') loadTransactions();
    else if (tab === 'trend') loadTrend();
    else if (tab === 'discrepancies') loadDiscrepancies();
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

  /* ======================= 5. 事件绑定与初始化 ======================= */

  function bindEvents() {
    $('unlockForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var key = $('keyInput').value.trim();
      if (!key) { showError('authError', '请输入管理 API Key。'); return; }
      hideError('authError');
      unlock(key, $('rememberKey').checked);
    });

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
    var stored = loadStoredKey();
    if (stored) {
      $('rememberKey').checked = !!localStorage.getItem(KEY_STORAGE);
      unlock(stored, $('rememberKey').checked);
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
