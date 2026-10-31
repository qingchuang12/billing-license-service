/**
 * 支付结果页（/payment/success · /payment/cancel）。
 *
 * 只做一件事：把渠道回跳时追加的订单标识回显给用户，便于其核对。
 * 不请求任何接口、不读 Cookie / localStorage——本页是匿名的，拿不到账户上下文，
 * 也不应在这里尝试拿。全部取值走 textContent，杜绝 query 注入成标签。
 */
(function () {
  'use strict';

  // 渠道回跳携带的标识各不相同，按「本地单号优先」取值
  var KEYS = ['request_id', 'order_id', 'orderId', 'order_number', 'out_trade_no', 'session_id', 'checkout_id'];

  function pick() {
    var params;
    try {
      params = new URLSearchParams(window.location.search || '');
    } catch (e) {
      return '';
    }
    for (var i = 0; i < KEYS.length; i++) {
      var value = params.get(KEYS[i]);
      if (value && value.trim()) {
        return value.trim();
      }
    }
    return '';
  }

  function render() {
    var value = pick();
    if (!value) {
      return;
    }
    var meta = document.getElementById('meta');
    var orderNo = document.getElementById('orderNo');
    if (!meta || !orderNo) {
      return;
    }
    orderNo.textContent = value;
    meta.hidden = false;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', render);
  } else {
    render();
  }
})();
