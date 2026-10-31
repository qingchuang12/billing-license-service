/* ==========================================================================
   晏宁科技 · 后端内嵌收银台落地页（/checkout/）交互脚本
   职责：选档位 → 创建收银台 → 选支付方式 → 支付 → 轮询状态 → 展示 License / 兑换码
   依赖：vendor/qrcode.js（本页同目录的本地 MIT 单文件库；缺失时二维码位自动降级）
   i18n：与 index.html 同一套机制（data-i18n + 字典 + localStorage），记忆同一个语言键
   ========================================================================== */
(function () {
  'use strict';

  /* ======================================================================
     1. 配置区
     ====================================================================== */

  /*
   * API 基址：页面由后端（与 /api 同源）托管，故默认走相对路径，无需注入。
   * 仅当跨域托管时才由托管方注入 window.CHECKOUT_API_BASE（生产 HTTPS 地址，不带结尾斜杠）。
   * 未注入时所有请求打到同源 /api/**，与后端静态资源天然同源 → 免 CORS。
   */
  var API_BASE = (typeof window.CHECKOUT_API_BASE === 'string' && window.CHECKOUT_API_BASE.trim())
    ? window.CHECKOUT_API_BASE.trim().replace(/\/+$/, '')
    : '';

  /*
   * 下单区域参数 —— 跟随界面语言（K8 决策 A）。
   * 服务端 CheckoutService#isDomestic 判定：currency==CNY || locale 以 "zh" 开头 → 国内。
   *   · 中文界面（zh）：CNY 计价 + 支付宝/微信（priceCny 来自 K16 端点）
   *   · 英文界面（en）：USD 计价 + Stripe/PayPal（priceUsd 来自 K16 端点）
   * 页面价格展示与下单币种均由 orderParams() 按当前 lang 决定，二者保持一致，不硬编码。
   */
  function orderParams() {
    if (lang === 'zh') return { currency: 'CNY', locale: 'zh-CN' };
    return { currency: 'USD', locale: 'en' };
  }

  /** 客服邮箱（K8 决策 C：全站统一 service@ywhome.top） */
  var SUPPORT_EMAIL = 'service@ywhome.top';

  /** 与 index.html 共用同一个语言记忆键，跨页保持一致 */
  var LANG_STORAGE_KEY = 'yaning-lang';

  /** 收银台会话本地暂存键（用于刷新 / 第三方支付跳回后的断点续做） */
  var SESSION_STORAGE_KEY = 'yaning-checkout-session';

  /* ------------------------------------------------------------------
     产品目录完全由 GET /api/products（K16）驱动：
     SKU / 名称 / 描述 / 双价 / 档位 / 周期 / 权益 / 推荐位均来自后台产品配置，
     页面不再维护 SKU 白名单；新增套餐只要上架即自动出现在收银台。
     价格与档位/周期校验一律以接口返回为准。
     ------------------------------------------------------------------ */
  // 构建标记：用于排查「浏览器标签页缓存了旧脚本」的支持场景（F12 控制台可见）
  console.info('[checkout] build 2026-10-29-1 · 目录完全由 /api/products 驱动（无 SKU 白名单）');

  /** 档位徽标文案 */
  var TIER_LABELS = {
    PRO: { zh: 'Pro', en: 'Pro' },
    PRO_PLUS: { zh: 'Pro Plus', en: 'Pro Plus' }
  };

  function toNumber(v) {
    var n = Number(v);
    return isFinite(n) ? n : null;
  }

  /** 服务端 BillingCycle 枚举 → 页面周期键 */
  function normalizeCycle(v) {
    var s = String(v || '').toUpperCase();
    if (s === 'MONTHLY') return 'monthly';
    if (s === 'HALF_YEAR') return 'halfYear';
    if (s === 'LIFETIME' || s === 'ONE_TIME') return 'lifetime';
    if (s === 'YEARLY') return 'yearly';
    if (s === 'QUARTERLY') return 'quarterly';
    return 'lifetime';
  }

  /** 周期对应的付款说明（双语展示位随周期派生，不再由前端白名单硬编码） */
  function unitForCycle(cycle) {
    if (cycle === 'monthly') return { zh: '每月自动续费，可随时取消', en: 'billed monthly, cancel anytime' };
    if (cycle === 'halfYear') return { zh: '每半年自动续费，可随时取消', en: 'billed every 6 months, cancel anytime' };
    if (cycle === 'quarterly') return { zh: '每季度自动续费，可随时取消', en: 'billed quarterly, cancel anytime' };
    if (cycle === 'yearly') return { zh: '每年自动续费，可随时取消', en: 'billed yearly, cancel anytime' };
    return { zh: '一次性付款', en: 'one-time payment' };
  }

  /** 价格后缀（月/半年/季/年）；一次性与永久不加后缀 */
  function priceSuffix(cycle) {
    if (cycle === 'monthly') return t('plan.priceSuffix.monthly');
    if (cycle === 'halfYear') return t('plan.priceSuffix.halfYear');
    if (cycle === 'quarterly') return t('plan.priceSuffix.quarterly');
    if (cycle === 'yearly') return t('plan.priceSuffix.yearly');
    return '';
  }

  // 权益配置已下线：客户端按 SKU 解锁，收银台只用产品描述表达套餐差异。

  /**
   * 产品目录取值入口（K8 决策 B + K16 + V10 产品维度）。
   * 拉取 GET /api/products（带产品码时走 ?product= 过滤，值经 URL 编码），
   * 按 SKU 合并「展示元数据 + 权威价格」；
   * 双价皆缺的 SKU 直接抛弃，避免脏数据上屏。
   * @param {string} [productCode] 产品码（如 ai-tools）；空 = 全量目录
   * @returns {Promise<Array>}
   */
  function fetchProducts(productCode) {
    var catalogPath = productCode
      ? '/api/products?product=' + encodeURIComponent(productCode)
      : '/api/products';
    return getJson(catalogPath).then(function (list) {
      if (productCode && !(Array.isArray(list) && list.length)) {
        // 兜底：按产品码过滤后目录为空（如新产品漏填 product_code）——回退全量目录。
        // 收银台空页等于阻断收款，宁可多展示档位，也不能白页。
        console.warn('[checkout] product=' + productCode + ' 目录过滤结果为空，回退全量产品目录');
        return getJson('/api/products').then(mergeCatalog);
      }
      return mergeCatalog(list);
    });
  }

  /** 目录 DTO 列表 → 页面档位行（接口驱动；双价皆缺的脏数据不上屏；空目录视为接口异常） */
  function mergeCatalog(list) {
    var rows = Array.isArray(list) ? list : [];
    var merged = [];
    rows.forEach(function (dto) {
      var sku = dto && dto.sku;
      if (!sku) return;
      var cny = toNumber(dto.priceCny);
      var usd = toNumber(dto.priceUsd);
      if (cny == null && usd == null) return;
      var cycle = normalizeCycle(dto.billingCycle);
      var nameZh = dto.name != null ? dto.name : sku;
      var descZh = dto.description != null ? dto.description : '';
      merged.push({
        sku: sku,
        tier: dto.tier || 'PRO',
        cycle: cycle,
        recurring: !!dto.recurring,
        priceCny: cny,
        priceUsd: usd,
        recommended: !!dto.recommended,
        // 渠道能力矩阵：只保留「支持该套餐周期」的已启用渠道，供按区域展示与置灰
        providers: Array.isArray(dto.providers) ? dto.providers : [],
        name: { zh: nameZh, en: (dto.nameEn != null ? dto.nameEn : nameZh) },
        desc: { zh: descZh, en: (dto.descriptionEn != null ? dto.descriptionEn : descZh) },
        unit: unitForCycle(cycle)
      });
    });
    if (!merged.length) {
      return Promise.reject({ kind: 'api', status: 0, code: 'INTERNAL_ERROR', message: '', traceId: '' });
    }
    return merged;
  }

  /** 按当前界面语言取展示价格（K8 决策 A：zh→CNY，en→USD） */
  function pickPrice(product) {
    if (lang === 'zh') {
      var cny = product.priceCny != null ? product.priceCny : product.priceUsd;
      return { symbol: '¥', value: cny };
    }
    var usd = product.priceUsd != null ? product.priceUsd : product.priceCny;
    return { symbol: '$', value: usd };
  }

  /** 金额格式化：千分位 + 两位小数 */
  function formatMoney(value) {
    var n = Number(value);
    if (!isFinite(n)) return '0.00';
    return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  /** 支付渠道展示元信息（键为服务端 PaymentMethod 枚举名，大写） */
  var PAYMENT_METHODS = {
    ALIPAY: {
      label: { zh: '支付宝', en: 'Alipay' },
      note: { zh: '扫码支付 · 人民币 CNY', en: 'Scan to pay · CNY' },
      icon: '支',
      color: '#1677FF'
    },
    WECHAT_PAY: {
      label: { zh: '微信支付', en: 'WeChat Pay' },
      note: { zh: '扫码支付 · 人民币 CNY', en: 'Scan to pay · CNY' },
      icon: '微',
      color: '#07C160'
    },
    STRIPE: {
      label: { zh: 'Stripe 信用卡', en: 'Stripe card' },
      note: { zh: '跳转第三方支付页 · 美元 USD', en: 'Redirect to secure checkout · USD' },
      icon: 'S',
      color: '#635BFF'
    },
    PADDLE: {
      label: { zh: 'Paddle', en: 'Paddle' },
      note: { zh: '跳转第三方支付页 · 美元 USD', en: 'Redirect to secure checkout · USD' },
      icon: 'P',
      color: '#1F2937'
    },
    CREEM: {
      label: { zh: 'Creem', en: 'Creem' },
      note: { zh: '跳转第三方支付页 · 美元 USD', en: 'Redirect to secure checkout · USD' },
      icon: 'C',
      color: '#0F172A'
    },
    PAYPAL: {
      label: { zh: 'PayPal', en: 'PayPal' },
      note: { zh: '跳转第三方支付页 · 美元 USD', en: 'Redirect to secure checkout · USD' },
      icon: 'P',
      color: '#003087'
    }
  };

  /**
   * 当前界面语言对应的结算区域（与后端 createCheckout 的区域判定同口径：zh→国内，其余→国际）。
   */
  function currentRegion() {
    return lang === 'zh' ? 'CHINA' : 'GLOBAL';
  }

  /** 该套餐在当前区域可用的渠道（渠道能力矩阵由 /api/products 的 providers 下发）。 */
  function providersForRegion(product) {
    if (!product || !Array.isArray(product.providers)) return [];
    var region = currentRegion();
    return product.providers.filter(function (pv) {
      return pv && pv.region === region;
    });
  }

  /**
   * 轮询策略：前若干次固定 3 秒，其后指数退避（上限 15 秒）；
   * 另有总次数上限、总时长上限、连续失败上限三重保护，避免打爆服务端。
   */
  var POLL = {
    intervalMs: 3000,
    stableCount: 6,
    maxIntervalMs: 15000,
    maxAttempts: 60,
    maxDurationMs: 12 * 60 * 1000,
    maxConsecutiveErrors: 5,
    backoffFactor: 1.5,
    maxBackoffSteps: 6
  };

  /** 需要继续轮询的状态：CREATED（已建会话未选渠道）+ PENDING（已发起支付待确认） */
  var ACTIVE_STATUS = { CREATED: true, PENDING: true };

  /** 跳转到第三方支付页前的自动跳转倒计时（毫秒） */
  var REDIRECT_DELAY_MS = 2500;

  /* ======================================================================
     2. 文案字典（zh / en 零差集）
     ====================================================================== */
  var I18N = {
    zh: {
      'meta.title': '获取 AI-Tools 授权 · 晏宁科技 Yaning Labs',
      'meta.desc': '选择 AI-Tools 的 Pro / Pro Plus 授权档位并完成支付，即时获取许可证或兑换码。',

      'a11y.skip': '跳到主要内容',
      'a11y.langSwitch': '切换语言：当前为中文，点击切换为英文',

      'brand.name': '晏宁科技',
      'nav.home': '返回首页',

      'checkout.eyebrow': '获取授权',
      'checkout.title': '获取 AI-Tools 授权',
      'checkout.sub': '选择授权档位并完成支付，即可获得许可证或兑换码；支持 Pro / Pro Plus 买断与订阅。',
      'checkout.formTitle': '填写下单信息',

      'steps.choose': '选择档位',
      'steps.confirm': '确认信息',
      'steps.pay': '完成支付',
      'steps.done': '获取授权',

      'plan.legend': '选择授权档位',
      'plan.groupOnce': '一次性付费',
      'plan.groupSubscription': '订阅制 · 按期自动续费',
      'plan.currencyNote': '价格随界面语言切换：中文界面以人民币（CNY）结算，英文界面以美元（USD）结算。',
      'plan.deviceNote': '每份授权仅绑定 1 台设备；多设备需分别购买，换机可在账户页解绑后重新绑定。',
      'plan.recommended': '推荐',
      'plan.unavailable': '当前区域暂不可售',
      'plan.unavailableHint': '切换英文界面即可用美元购买',
      'plan.providers': '可用支付方式',
      'plan.priceSuffix.monthly': '/月',
      'plan.priceSuffix.halfYear': '/半年',
      'plan.priceSuffix.quarterly': '/季',
      'plan.priceSuffix.yearly': '/年',

      'form.emailLabel': '电子邮箱',
      'form.emailHint': '授权凭证与订单通知将发送并绑定到此邮箱。',
      'form.machineLabel': '机器码',
      'common.copy': '复制',
      'form.machineNone': '未提供机器码，支付完成后将发放兑换码。',
      'form.machineHint': '已读取到本机机器码，支付成功后将直接签发绑定该设备的 License。',
      'form.orderNote': '点击下单后将跳转至第三方支付渠道完成付款；发票事宜请联系 ',
      'form.legalPrefix': '点击下单即表示你已阅读并同意',
      'form.legalTerms': '《服务条款》',
      'form.legalAnd': '与',
      'form.legalPrivacy': '《隐私政策》',
      'form.legalSuffix': '。',
      'form.submit': '下一步：确认信息',
      'form.submitting': '正在创建订单…',
      'form.error.planRequired': '请先选择一个授权档位。',
      'form.error.emailRequired': '请填写电子邮箱。',
      'form.error.emailInvalid': '邮箱格式不正确，请检查后重试。',
      'form.error.loadProducts': '产品信息加载失败，请刷新页面重试。',

      'summary.title': '订单摘要',
      'summary.plan': '档位',
      'summary.amount': '金额',
      'summary.email': '邮箱',
      'summary.machine': '机器码',
      'summary.order': '订单号',
      'summary.status': '状态',
      'summary.contact': '需要帮助？联系我们',
      'summary.notSelected': '未选择',
      'summary.notBound': '未绑定',

      'pay.title': '选择支付方式',
      'pay.desc': '请核对订单信息，并选择一种支付方式继续。',
      'pay.installment': '花呗分期',
      'pay.installmentNone': '不分期',
      'pay.installmentOption': '{n} 期',
      'pay.regionChina': '当前区域：中国 · 人民币（CNY）结算',
      'pay.regionGlobal': '当前区域：海外 · 美元（USD）结算',
      'pay.regionSwitchZh': '切换到英文界面后，可用 Stripe / Paddle / Creem / PayPal 以美元结算。',
      'pay.regionSwitchEn': 'Switch to Chinese to pay with Alipay / WeChat Pay in CNY.',
      'pay.recommended': '推荐',
      'pay.recapTitle': '订单信息',
      'pay.backToForm': '← 返回修改订单',
      'pay.refreshStatus': '刷新支付状态',
      'pay.refreshBusy': '查询中…',
      'pay.copyPayLink': '复制支付链接',
      'pay.countdownLabel': '会话有效期剩余',
      'pay.redirectTitle': '即将跳转至支付页',
      'pay.redirectHint': '将在浏览器中打开第三方支付页面，完成付款后会自动回到本页并展示授权凭证。若未自动跳转，请点击下方按钮。',
      'pay.goPay': '前往支付',
      'pay.redirectCountdown': '{{s}} 秒后自动跳转…',
      'pay.qrTitle': '请使用支付应用扫码',
      'pay.qrHint': '二维码有效期以页面倒计时为准；支付完成后本页会自动更新结果，请勿关闭页面。',
      'pay.qrAlt': '支付二维码',
      'pay.qrFallbackTitle': '二维码渲染不可用',
      'pay.qrFallbackHint': '当前环境未能生成本地二维码。请复制下方链接，或在新窗口中打开后完成支付。',
      'pay.copyLink': '复制链接',
      'pay.openLink': '在新窗口打开',
      'pay.copied': '已复制到剪贴板',
      'pay.copyFailed': '复制失败，请手动选中链接复制。',
      'pay.reselectProvider': '重新选择支付方式',
      'pay.reselectHint': '若上一个支付链接已失效或未完成付款，可重新选择支付方式获取新的支付入口。',

      'poll.title': '等待支付结果',
      'poll.hint': '我们正在确认支付结果，请勿关闭本页面。',
      'poll.openPaymentHint': '若你尚未完成付款，可重新打开支付页面继续支付。',
      'poll.elapsed': '已等待 {{s}} 秒',
      'poll.openPayment': '重新获取支付链接',

      'done.pendingTitle': '支付已确认，凭证生成中',
      'done.pendingHint': '授权凭证暂未生成，通常几分钟内即可完成。若长时间未到账，请联系 {{email}} 并提供订单号。',
      'done.orderLabel': '订单号',
      'done.licenseTitle': '授权成功',
      'done.licenseHint': '已为当前设备签发 License，请将下方许可证填入 AI-Tools 客户端即可使用。',
      'done.licenseStep': '在 AI-Tools 客户端中打开「授权 / License」页面，粘贴上述许可证并保存。',
      'done.redeemTitle': '支付成功',
      'done.redeemHint': '请复制下方兑换码，在 AI-Tools 客户端中兑换激活。',
      'done.redeemStep1': '打开 AI-Tools 客户端的「授权 / License」页面，选择「使用兑换码」。',
      'done.redeemStep2': '粘贴兑换码并确认，客户端会自动绑定当前设备并完成授权。',
      'done.copyLicense': '复制许可证',
      'done.copyCode': '复制兑换码',
      'done.download': '下载 .txt',
      'done.downloaded': '已开始下载 .txt',
      'done.goAccount': '前往「我的授权」',
      'done.backTitle': '接下来',
      'done.licenseStep1': '打开 AI-Tools 客户端，进入「授权 / License」页面。',
      'done.licenseStep2': '粘贴上方许可证并保存，完成本机绑定。',
      'done.licenseStep3': '之后可在「我的授权」中随时查看或换设备解绑。',
      'done.redeemStep1': '打开 AI-Tools 客户端的「授权 / License」页面，选择「使用兑换码」。',
      'done.redeemStep2': '粘贴兑换码并确认，客户端会自动绑定当前设备并完成授权。',
      'done.backHint': '凭证已同步发送至邮箱；若客户端已打开，请先重启一次。',
      'done.subscriptionNote': '订阅订单的续费与取消由支付渠道托管，本页不代管；当前权益已按周期生效。',
      'toast.copied': '已复制到剪贴板',
      'toast.copyFailed': '复制失败，请手动复制',

      'status.CREATED': '待选择支付方式',
      'status.PENDING': '等待支付结果',
      'status.PAID': '已支付',
      'status.FAILED': '支付失败',
      'status.EXPIRED': '会话已过期',
      'status.CANCELED': '已取消',
      'status.UNKNOWN': '未知状态',

      'error.title': '未能完成本次操作',
      'error.retry': '重试',
      'error.restart': '重新下单',
      'error.goHome': '返回首页',
      'error.detailLabel': '服务端返回：',
      'error.traceLabel': '追踪码：',
      'error.supportHint': '若多次失败或扣款后未收到凭证，请联系 {{email}}。',

      'err.PRODUCT_NOT_FOUND': '所选档位不存在或已下架，请重新选择。',
      'err.PRODUCT_INACTIVE': '所选档位已停止销售，请重新选择。',
      'err.EMAIL_PURCHASE_LIMIT': '该邮箱下单过于频繁，请稍后再试或更换邮箱。',
      'err.NO_PAYMENT_METHOD': '当前地区暂无可用支付方式，请稍后再试或联系我们。',
      'err.PRICE_NOT_CONFIGURED': '所选档位尚未配置对应价格，暂时无法下单，请稍后再试。',
      'err.CHECKOUT_NOT_FOUND': '收银台会话不存在或已过期，请重新下单。',
      'err.ORDER_NOT_FOUND': '关联订单不存在，请重新下单。',
      'err.UNSUPPORTED_PROVIDER': '不支持所选支付方式，请重新选择。',
      'err.PROVIDER_REQUIRED': '未指定支付方式，请重新选择。',
      'err.PAYMENT_CREATE_FAILED': '支付渠道创建失败，请稍后重试或更换支付方式。',
      'err.CHANNEL_DISABLED': '所选支付渠道暂未启用，请更换支付方式。',
      'err.VALIDATION_ERROR': '提交信息未通过校验，请检查邮箱与档位后重试。',
      'err.INVALID_REQUEST_BODY': '请求格式有误，请刷新页面后重试。',
      'err.ENDPOINT_NOT_FOUND': '接口地址不存在，请检查服务端配置或稍后重试。',
      'err.INTERNAL_ERROR': '服务端出现异常，请稍后重试。',
      'err.ADMIN_UNAUTHORIZED': '未通过服务端鉴权，请联系管理员。',
      'err.UNAUTHORIZED': '请求未通过服务端鉴权，请刷新页面或联系我们。',
      'err.ACCESS_DENIED': '无权访问该接口，请刷新页面或联系我们。',
      'err.MISSING_PARAMETER': '请求缺少必要参数，请刷新页面后重试。',
      'err.METHOD_NOT_ALLOWED': '接口调用方式不正确，请刷新页面后重试。',
      'err.NETWORK': '网络连接失败，请检查网络后重试。',
      'err.TIMEOUT': '未检测到支付结果，已停止等待。若你已完成付款，请刷新本页重试，或联系 {{email}}。',
      'err.EXPIRED': '本次收银台会话已过期，请重新下单。',
      'err.UNKNOWN': '发生未知错误，请稍后重试。',
      'err.noPaymentEntry': '支付渠道未返回支付入口，请重新选择支付方式。',

      'footer.rights': '© 2026 晏宁科技 Yaning Labs. 保留所有权利。'
    },

    en: {
      'meta.title': 'Get your AI-Tools license · Yaning Labs',
      'meta.desc': 'Pick a Pro / Pro Plus tier for AI-Tools and pay online to receive a signed license token or redemption code instantly. Settled in USD.',

      'a11y.skip': 'Skip to main content',
      'a11y.langSwitch': 'Switch language: currently English, click to switch to Chinese',

      'brand.name': 'Yaning Labs',
      'nav.home': 'Back to home',

      'checkout.eyebrow': 'Get a license',
      'checkout.title': 'Get your AI-Tools license',
      'checkout.sub': 'Pick a tier and complete payment to receive a signed license token or a redemption code. Pro / Pro Plus lifetime and monthly plans available.',
      'checkout.formTitle': 'Order details',

      'steps.choose': 'Choose a plan',
      'steps.confirm': 'Confirm details',
      'steps.pay': 'Complete payment',
      'steps.done': 'Get your license',

      'plan.legend': 'Choose a plan',
      'plan.groupOnce': 'One-time purchase',
      'plan.groupSubscription': 'Subscription · auto-renews',
      'plan.currencyNote': 'Pricing follows your interface language: CNY for Chinese, USD for English.',
      'plan.deviceNote': 'Each license binds to one device. Buy separately for additional devices; you can unbind and rebind after a device change.',
      'plan.recommended': 'Recommended',
      'plan.unavailable': 'Not available in this region',
      'plan.unavailableHint': 'Switch to Chinese to pay in CNY',
      'plan.providers': 'Payment methods',
      'plan.priceSuffix.monthly': '/mo',
      'plan.priceSuffix.halfYear': '/6 mo',
      'plan.priceSuffix.quarterly': '/qtr',
      'plan.priceSuffix.yearly': '/yr',

      'form.emailLabel': 'Email address',
      'form.emailHint': 'Your license credential and order updates will be sent to and bound to this email.',
      'form.machineLabel': 'Machine ID',
      'common.copy': 'Copy',
      'form.machineNone': 'No machine ID provided — a redemption code will be issued after payment.',
      'form.machineHint': 'Machine ID detected — a license bound to this device will be issued right after payment.',
      'form.orderNote': 'You will be redirected to a third-party payment page. For invoices, contact ',
      'form.legalPrefix': 'By placing the order you agree to the ',
      'form.legalTerms': 'Terms of Service',
      'form.legalAnd': ' and ',
      'form.legalPrivacy': 'Privacy Policy',
      'form.legalSuffix': '.',
      'form.submit': 'Next: confirm details',
      'form.submitting': 'Creating your order…',
      'form.error.planRequired': 'Please choose a license tier first.',
      'form.error.emailRequired': 'Please enter your email address.',
      'form.error.emailInvalid': 'That email address looks invalid — please double-check.',
      'form.error.loadProducts': 'Failed to load plan information. Please reload the page.',

      'summary.title': 'Order summary',
      'summary.plan': 'Plan',
      'summary.amount': 'Amount',
      'summary.email': 'Email',
      'summary.machine': 'Machine ID',
      'summary.order': 'Order',
      'summary.status': 'Status',
      'summary.contact': 'Need help? Contact us',
      'summary.notSelected': 'Not selected',
      'summary.notBound': 'Not bound',

      'pay.title': 'Choose a payment method',
      'pay.desc': 'Review your order and pick a payment method to continue.',
      'pay.installment': 'Huabei installments',
      'pay.installmentNone': 'No installments',
      'pay.installmentOption': '{n} months',
      'pay.regionChina': 'Region: China · settled in CNY',
      'pay.regionGlobal': 'Region: Global · settled in USD',
      'pay.regionSwitchZh': 'Switch to Chinese to pay with Alipay / WeChat Pay in CNY.',
      'pay.regionSwitchEn': 'Switch to Chinese to pay with Alipay / WeChat Pay in CNY.',
      'pay.recommended': 'Recommended',
      'pay.recapTitle': 'Order details',
      'pay.backToForm': '← Back to edit order',
      'pay.refreshStatus': 'Refresh payment status',
      'pay.refreshBusy': 'Checking…',
      'pay.copyPayLink': 'Copy payment link',
      'pay.countdownLabel': 'Session expires in',
      'pay.redirectTitle': 'Redirecting to checkout',
      'pay.redirectHint': 'The payment provider page opens in this browser. Once paid you return here and your credential appears automatically. If nothing happens, use the button below.',
      'pay.goPay': 'Go to payment',
      'pay.redirectCountdown': 'Redirecting in {{s}}s…',
      'pay.qrTitle': 'Scan this QR code to pay',
      'pay.qrHint': 'The code expires with the countdown above. Keep this page open — the result updates automatically.',
      'pay.qrAlt': 'Payment QR code',
      'pay.qrFallbackTitle': 'QR rendering unavailable',
      'pay.qrFallbackHint': 'The local QR library could not render. Copy the link below or open it in a new window to pay.',
      'pay.copyLink': 'Copy link',
      'pay.openLink': 'Open in new window',
      'pay.copied': 'Copied to clipboard',
      'pay.copyFailed': 'Copy failed — please select the link and copy manually.',
      'pay.reselectProvider': 'Choose another payment method',
      'pay.reselectHint': 'If the previous payment link expired or you did not finish paying, pick a payment method again to get a fresh one.',

      'poll.title': 'Waiting for payment confirmation',
      'poll.hint': 'We are confirming your payment. Please keep this page open.',
      'poll.openPaymentHint': 'If you have not paid yet, you can reopen the payment page and continue.',
      'poll.elapsed': 'Waiting for {{s}}s',
      'poll.openPayment': 'Get a new payment link',

      'done.pendingTitle': 'Payment confirmed, credential pending',
      'done.pendingHint': 'Your credential is still being generated and usually arrives within minutes. If it does not, contact {{email}} with your order number.',
      'done.orderLabel': 'Order number',
      'done.licenseTitle': 'License issued',
      'done.licenseHint': 'A license bound to this device has been issued. Paste the token below into the AI-Tools desktop app.',
      'done.licenseStep': 'In AI-Tools, open License, paste the token above and save.',
      'done.redeemTitle': 'Payment received',
      'done.redeemHint': 'Copy the redemption code below and redeem it inside the AI-Tools desktop app.',
      'done.redeemStep1': 'Open License in the AI-Tools app and choose “Redeem a code”.',
      'done.redeemStep2': 'Paste the code and confirm — the app binds this device and activates automatically.',
      'done.copyLicense': 'Copy license token',
      'done.copyCode': 'Copy redemption code',
      'done.download': 'Download .txt',
      'done.downloaded': 'Download started',
      'done.goAccount': 'Go to My Licenses',
      'done.backTitle': 'Next steps',
      'done.licenseStep1': 'Open the AI-Tools app and go to License.',
      'done.licenseStep2': 'Paste the license above and save to bind this device.',
      'done.licenseStep3': 'You can view it or unbind the device anytime in My Licenses.',
      'done.redeemStep1': 'Open License in the AI-Tools app and choose “Redeem a code”.',
      'done.redeemStep2': 'Paste the code and confirm — the app binds this device and activates automatically.',
      'done.backHint': 'The credential was also emailed to you. Restart the app if it is already running.',
      'done.subscriptionNote': 'Renewals and cancellations are managed by the payment provider; your benefits are active for the current period.',
      'toast.copied': 'Copied to clipboard',
      'toast.copyFailed': 'Copy failed — please copy manually',

      'status.CREATED': 'Awaiting payment method',
      'status.PENDING': 'Awaiting payment',
      'status.PAID': 'Paid',
      'status.FAILED': 'Payment failed',
      'status.EXPIRED': 'Session expired',
      'status.CANCELED': 'Canceled',
      'status.UNKNOWN': 'Unknown status',

      'error.title': 'Something went wrong',
      'error.retry': 'Try again',
      'error.restart': 'Start over',
      'error.goHome': 'Back to home',
      'error.detailLabel': 'Server response: ',
      'error.traceLabel': 'Trace ID: ',
      'error.supportHint': 'If this keeps failing, or you were charged without receiving a credential, contact {{email}}.',

      'err.PRODUCT_NOT_FOUND': 'That plan no longer exists or is unavailable. Please pick another one.',
      'err.PRODUCT_INACTIVE': 'That plan is no longer on sale. Please pick another one.',
      'err.EMAIL_PURCHASE_LIMIT': 'Too many orders from this email address. Please try again later or use another email.',
      'err.NO_PAYMENT_METHOD': 'No payment method is available for your region right now. Please try later or contact us.',
      'err.PRICE_NOT_CONFIGURED': 'This plan has no price configured and cannot be ordered yet. Please try later.',
      'err.CHECKOUT_NOT_FOUND': 'The checkout session does not exist or has expired. Please start over.',
      'err.ORDER_NOT_FOUND': 'The related order does not exist. Please start over.',
      'err.UNSUPPORTED_PROVIDER': 'That payment method is not supported. Please choose another one.',
      'err.PROVIDER_REQUIRED': 'No payment method selected. Please choose one.',
      'err.PAYMENT_CREATE_FAILED': 'The payment provider could not start your payment. Please retry or choose another method.',
      'err.CHANNEL_DISABLED': 'That payment channel is currently disabled. Please choose another method.',
      'err.VALIDATION_ERROR': 'Your details failed validation. Please check email and plan, then retry.',
      'err.INVALID_REQUEST_BODY': 'The request was malformed. Please reload the page and retry.',
      'err.ENDPOINT_NOT_FOUND': 'The API endpoint was not found. Check the server configuration or try later.',
      'err.INTERNAL_ERROR': 'The server hit an unexpected error. Please try again later.',
      'err.ADMIN_UNAUTHORIZED': 'Server authorization failed. Please contact the administrator.',
      'err.UNAUTHORIZED': 'The request was not authorized. Please reload the page or contact us.',
      'err.ACCESS_DENIED': 'Access to this endpoint is denied. Please reload the page or contact us.',
      'err.MISSING_PARAMETER': 'The request is missing required parameters. Please reload and retry.',
      'err.METHOD_NOT_ALLOWED': 'This endpoint was called incorrectly. Please reload and retry.',
      'err.NETWORK': 'Network connection failed. Please check your connection and retry.',
      'err.TIMEOUT': 'No payment result detected, so we stopped waiting. If you already paid, reload this page or contact {{email}}.',
      'err.EXPIRED': 'This checkout session has expired. Please start over.',
      'err.UNKNOWN': 'An unexpected error occurred. Please try again later.',
      'err.noPaymentEntry': 'The payment provider returned no payment link. Please choose a payment method again.',

      'footer.rights': '© 2026 Yaning Labs. All rights reserved.'
    }
  };

  var LANG_LABEL = { zh: '中文', en: 'EN' };
  var HTML_LANG = { zh: 'zh-CN', en: 'en' };

  /* ======================================================================
     3. 运行时状态
     ====================================================================== */
  var lang = readLang();

  var state = {
    step: 'form',            // form | providers | pay | polling | done | error
    product: null,           // 当前选中的 Product 对象
    email: '',
    machineId: '',
    checkoutId: '',
    orderNumber: '',
    status: '',
    paymentMethods: [],
    provider: '',
    installmentNum: null,
    paymentMode: '',
    redirectUrl: '',
    qrcode: '',
    expiresAt: 0,
    expiresTotalMs: 0,
    license: '',
    redeemCode: ''
  };

  var products = [];

  /** 轮询运行时数据 */
  var poll = {
    active: false,
    timer: null,
    ticker: null,
    startedAt: 0,
    attempts: 0,
    consecutiveErrors: 0,
    paidWithoutCredential: 0
  };

  /** 自动跳转第三方支付页的计时器 */
  var redirectTimer = null;

  /* ======================================================================
     4. 通用工具
     ====================================================================== */
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /** HTML 转义：所有插入 DOM 的动态文本都必须先过这里 */
  function esc(value) {
    return String(value === null || value === undefined ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  /** 取文案：优先当前语言，缺失时回落到中文 */
  function t(key, vars) {
    var dict = I18N[lang];
    var text = (dict && dict[key] !== undefined) ? dict[key] : (I18N.zh[key] !== undefined ? I18N.zh[key] : key);
    if (vars) {
      Object.keys(vars).forEach(function (name) {
        text = text.split('{{' + name + '}}').join(String(vars[name]));
      });
    }
    return text;
  }

  /** 取内联双语对象（如 Product.name / benefits 文案） */
  function pickText(obj) {
    if (!obj) return '';
    if (obj[lang] !== undefined) return obj[lang];
    if (obj.zh !== undefined) return obj.zh;
    return '';
  }

  function readLang() {
    try {
      var saved = localStorage.getItem(LANG_STORAGE_KEY);
      return (saved === 'zh' || saved === 'en') ? saved : 'zh';
    } catch (e) {
      return 'zh';
    }
  }

  function saveLang(value) {
    try { localStorage.setItem(LANG_STORAGE_KEY, value); } catch (e) { /* 隐私模式下忽略 */ }
  }

  /** 读取 URL 查询参数 */
  function getQueryParams() {
    var raw = location.search ? location.search.replace(/^\?/, '') : '';
    var result = {};
    if (!raw) return result;
    raw.split('&').forEach(function (pair) {
      if (!pair) return;
      var idx = pair.indexOf('=');
      var key = idx === -1 ? pair : pair.slice(0, idx);
      var value = idx === -1 ? '' : pair.slice(idx + 1);
      try {
        result[decodeURIComponent(key)] = decodeURIComponent(String(value).replace(/\+/g, ' '));
      } catch (e) {
        result[key] = value;
      }
    });
    return result;
  }

  /** 生成本页 URL（不含 hash），可附带查询参数 */
  function buildPageUrl(params) {
    var base = (location.origin && location.origin !== 'null')
      ? location.origin + location.pathname
      : String(location.href).split('#')[0].split('?')[0];
    var pairs = [];
    Object.keys(params || {}).forEach(function (key) {
      var value = params[key];
      if (value === null || value === undefined || value === '') return;
      pairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
    });
    return base + (pairs.length ? '?' + pairs.join('&') : '');
  }

  /** 会话暂存（供刷新与第三方回到场景续做） */
  function saveSession() {
    var snapshot = {
      checkoutId: state.checkoutId,
      productId: state.product ? state.product.sku : '',
      email: state.email,
      machineId: state.machineId,
      orderNumber: state.orderNumber,
      provider: state.provider,
      status: state.status,
      paymentMode: state.paymentMode,
      redirectUrl: state.redirectUrl,
      qrcode: state.qrcode,
      expiresAt: state.expiresAt,
      savedAt: Date.now()
    };
    try { localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(snapshot)); } catch (e) { /* 忽略 */ }
  }

  function readSession() {
    try {
      var raw = localStorage.getItem(SESSION_STORAGE_KEY);
      if (!raw) return null;
      var parsed = JSON.parse(raw);
      return (parsed && typeof parsed === 'object') ? parsed : null;
    } catch (e) {
      return null;
    }
  }

  function clearSession() {
    try { localStorage.removeItem(SESSION_STORAGE_KEY); } catch (e) { /* 忽略 */ }
  }

  /** 状态播报（屏幕阅读器） */
  function announce(text) {
    var region = $('#liveRegion');
    if (region) region.textContent = text;
  }

  function isValidEmail(value) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(String(value || '').trim());
  }

  /** 毫秒 → 秒（向上取整），用于倒计时/等待时长展示 */
  function secOf(ms) { return Math.max(0, Math.ceil(ms / 1000)); }

  /** P9：记录会话初始有效期，倒计时进度条按剩余占比绘制 */
  function syncExpiry(expiresAt) {
    var value = Number(expiresAt) || 0;
    if (!value) return;
    if (state.expiresAt !== value || !state.expiresTotalMs) {
      state.expiresTotalMs = Math.max(1000, value - Date.now());
    }
    state.expiresAt = value;
  }

  /* ======================================================================
     5. 服务端接口层
     ====================================================================== */

  /**
   * 统一请求入口：无论成功失败都解析 JSON；网络异常统一归一为 NETWORK 错误。
   *
   * 服务端所有端点经 ApiResponseAdvice 统一包壳 `{success, code, data, traceId, timestamp}`，
   * 业务字段在 `$.data`——在此**单点剥壳**后向上层返回业务对象（保持对无壳扁平结构的兼容）；
   * 壳内 `data.success === false`（业务失败以 200 返回的形态）同样归一为 API 错误抛给上层。
   * 不剥壳时 `fetchProducts` 拿到的是壳对象而非数组、轮询读 `$.status` 得 undefined，
   * 页面会整体失效（2026-09-19 实测复现「产品信息加载失败」）。
   * @returns {Promise<Object>}
   */
  function requestJson(url, options) {
    return fetch(url, options).then(function (res) {
      return res.text().then(function (raw) {
        var payload = null;
        if (raw) {
          try { payload = JSON.parse(raw); } catch (e) { payload = null; }
        }
        if (!res.ok) throw buildApiError(res.status, payload, raw);
        var data = payload && payload.data;
        if (data && typeof data === 'object') {
          if (data.success === false) throw buildApiError(res.status, data, raw);
          return data;
        }
        return payload || {};
      });
    }, function () {
      return Promise.reject({ kind: 'network', status: 0, code: 'NETWORK', message: '', traceId: '' });
    });
  }

  /**
   * 把非 2xx 响应归一成 {status, code, message, traceId}（N4）
   * - code 兼容两种壳体：统一壳 `$.code` 与旧自拼体 `$.errorCode`（成功壳的 SUCCESS 不算错误码）
   * - message 兜底到内层 `$.data.message`，防止「服务端结构一变就只剩通用文案」重演
   */
  function buildApiError(status, payload, raw) {
    var body = payload || {};
    var rawCode = body.errorCode || (body.code && body.code !== 'SUCCESS' ? body.code : '');
    var message = body.message
      || (body.data && body.data.message)
      || String(raw || '').slice(0, 200);
    return {
      kind: 'api',
      status: status,
      code: rawCode ? String(rawCode) : defaultCodeFor(status),
      message: String(message),
      traceId: body.traceId ? String(body.traceId) : ''
    };
  }

  function defaultCodeFor(status) {
    if (status === 400) return 'VALIDATION_ERROR';
    if (status === 401) return 'UNAUTHORIZED';
    if (status === 403) return 'ACCESS_DENIED';
    if (status === 404 || status === 405) return 'ENDPOINT_NOT_FOUND';
    if (status >= 500) return 'INTERNAL_ERROR';
    return 'UNKNOWN';
  }

  function postJson(path, body) {
    return requestJson(API_BASE + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify(body)
    });
  }

  function getJson(path) {
    return requestJson(API_BASE + path, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    });
  }

  /** POST /api/checkout/create —— 创建收银台会话（不传 provider，走两步流程） */
  function apiCreateCheckout(payload) {
    return postJson('/api/checkout/create', payload);
  }

  /** POST /api/checkout/{id}/select-provider —— 选定渠道并创建支付 */
  function apiSelectProvider(checkoutId, provider, installmentNum) {
    var payload = { provider: provider };
    // 手机浏览器优先走支付宝手机网站支付（WAP）；桌面端保持二维码/网页支付。
    try {
      if (provider === 'ALIPAY' && window.matchMedia && window.matchMedia('(max-width: 820px)').matches) {
        payload.paymentProduct = 'WAP';
      }
    } catch (e) { /* 老浏览器忽略 */ }
    if (provider === 'ALIPAY' && installmentNum) {
      payload.installmentNum = installmentNum;
      // 扫码（precreate）不支持花呗分期；未指定支付形态时强制走网页跳转
      if (!payload.paymentProduct) { payload.paymentProduct = 'REDIRECT'; }
    }
    return postJson('/api/checkout/' + encodeURIComponent(checkoutId) + '/select-provider', payload);
  }

  /** GET /api/checkout/{id}/status —— 轮询订单/支付状态 */
  function apiFetchStatus(checkoutId) {
    return getJson('/api/checkout/' + encodeURIComponent(checkoutId) + '/status');
  }

  /* ======================================================================
     6. 双语言应用
     ====================================================================== */
  function applyLang(nextLang) {
    lang = nextLang;
    // 语言切换会改变结算区域：已选套餐在新区域可能没有可售渠道，先清掉选择，避免提交时才报错
    if (state.product && !providersForRegion(state.product).length) {
      state.product = null;
    }
    var dict = I18N[lang] || I18N.zh;

    $$('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (dict[key] !== undefined) el.textContent = dict[key];
    });

    if (dict['meta.title']) document.title = dict['meta.title'];
    var desc = $('meta[name="description"]');
    if (desc && dict['meta.desc']) desc.setAttribute('content', dict['meta.desc']);
    document.documentElement.setAttribute('lang', HTML_LANG[lang] || 'zh-CN');

    var other = lang === 'zh' ? 'en' : 'zh';
    $('#langCurrent').textContent = LANG_LABEL[lang];
    $('#langOther').textContent = LANG_LABEL[other];
    $('#langSwitch').setAttribute('aria-label', dict['a11y.langSwitch']);

    saveLang(lang);

    // 重渲染：字典切换后，动态内容需要重新生成
    renderPlanCards();
    renderMachineBox();
    renderSummary();
    rerenderStage();
  }

  /** 字典切换后按当前步骤重绘面板（不重放网络请求） */
  function rerenderStage() {
    if (state.step === 'providers') {
      renderProviderChoice(state.paymentMethods);
    } else if (state.step === 'pay') {
      if (state.redirectUrl) renderRedirect();
      else if (state.qrcode) renderQrcode();
    } else if (state.step === 'polling') {
      renderPolling();
    } else if (state.step === 'done') {
      renderResult();
    } else if (state.step === 'error') {
      if (lastError) renderError(lastError, lastErrorOptions);
    }
  }

  /* ======================================================================
     7. 渲染：档位卡片 / 机器码 / 摘要 / 步骤条
     ====================================================================== */
  function renderPlanCards() {
    var list = $('#planList');
    if (!list) return;

    if (!products.length) {
      list.innerHTML = '<li class="panel__desc">' + esc(t('form.error.loadProducts')) + '</li>';
      return;
    }

    var groups = [
      { title: t('plan.groupOnce'), items: products.filter(function (p) { return !p.recurring; }) },
      { title: t('plan.groupSubscription'), items: products.filter(function (p) { return p.recurring; }) }
    ];

    list.innerHTML = groups.filter(function (group) {
      return group.items.length > 0;
    }).map(function (group) {
      return '<li class="plan-group">' +
        '<p class="plan-group-title">' + esc(group.title) + '</p>' +
        '<ul class="plan-list">' + group.items.map(planCardHtml).join('') + '</ul>' +
      '</li>';
    }).join('');

    $$('.plan-card__input', list).forEach(function (input) {
      input.addEventListener('change', function () {
        selectPlan(input.value);
      });
    });
  }

  /** 单个档位卡片（P2：不可售卡片明示原因并给出切换语言的出路） */
  function planCardHtml(product) {
    var selected = state.product && state.product.sku === product.sku;
    var providers = providersForRegion(product);
    var unavailable = providers.length === 0;
    var providerNames = providers.map(function (pv) {
      var meta = PAYMENT_METHODS[pv.method];
      return meta ? pickText(meta.label) : pv.method;
    }).join(' · ');
    var tierLabel = pickText(TIER_LABELS[product.tier] || { zh: product.tier, en: product.tier });
    var p = pickPrice(product);
    var price = p.symbol + formatMoney(p.value) + priceSuffix(product.cycle);

    return '' +
      '<li>' +
        '<label class="plan-card' + (selected ? ' is-selected' : '') +
          (unavailable ? ' is-unavailable' : '') + '" data-sku="' + esc(product.sku) + '">' +
          '<input class="plan-card__input" type="radio" name="plan" value="' + esc(product.sku) + '"' +
            (selected ? ' checked' : '') + (unavailable ? ' disabled' : '') + '>' +
          '<span class="plan-card__body">' +
            '<span class="plan-card__head">' +
              '<span class="plan-card__name">' + esc(pickText(product.name)) + '</span>' +
              '<span class="plan-card__tier">' + esc(tierLabel) + '</span>' +
            '</span>' +
            (product.recommended ? '<span class="plan-card__badge">' + esc(t('plan.recommended')) + '</span>' : '') +
            '<span class="plan-card__amount">' + esc(price) + '</span>' +
            '<span class="plan-card__unit">' + esc(pickText(product.unit)) + '</span>' +
            '<span class="plan-card__desc">' + esc(pickText(product.desc)) + '</span>' +
            '<span class="plan-card__providers">' +
              esc(unavailable ? t('plan.unavailable') : t('plan.providers') + ' · ' + providerNames) +
            '</span>' +
            (unavailable
              ? '<span class="plan-card__unavailable-hint">' + esc(t('plan.unavailableHint')) + '</span>'
              : '') +
          '</span>' +
        '</label>' +
      '</li>';
  }

  /** 目录加载期间的骨架屏（P18/G3）：避免空白等接口 */
  function renderPlanSkeleton() {
    var list = $('#planList');
    if (!list) return;
    var rows = '';
    for (var i = 0; i < 2; i++) {
      rows += '<li class="sk-row" aria-hidden="true"><span class="sk" style="max-width:120px"></span>' +
        '<span class="sk" style="max-width:64px"></span><span class="sk" style="max-width:80px"></span>' +
        '<span class="sk" style="max-width:56px"></span></li>';
    }
    list.innerHTML = rows;
  }

  function selectPlan(sku) {
    var product = products.filter(function (item) { return item.sku === sku; })[0] || null;
    // 当前区域没有任何可用渠道的套餐不允许被选中（对应后端 NO_PAYMENT_METHOD）
    if (product && !providersForRegion(product).length) return;
    state.product = product;
    $$('.plan-card', $('#planList')).forEach(function (card) {
      card.classList.toggle('is-selected', card.getAttribute('data-sku') === sku);
    });
    hideError('#planError');
    renderSummary();
  }

  function renderMachineBox() {
    var valueEl = $('#machineValue');
    var hintEl = $('#machineHint');
    var copyBtn = $('#machineCopyBtn');
    if (!valueEl || !hintEl) return;
    valueEl.textContent = state.machineId || '—';
    hintEl.textContent = state.machineId ? t('form.machineHint') : t('form.machineNone');
    if (copyBtn) copyBtn.hidden = !state.machineId;
  }

  function renderSummary() {
    var map = {
      sumPlan: state.product ? pickText(state.product.name) : t('summary.notSelected'),
      sumAmount: state.product
        ? (function () { var p = pickPrice(state.product); return p.symbol + formatMoney(p.value) + priceSuffix(state.product.cycle); })()
        : '—',
      sumEmail: state.email || '—',
      sumMachine: state.machineId || t('summary.notBound'),
      sumOrder: state.orderNumber || '—',
      sumStatus: state.status ? statusLabel(state.status) : '—'
    };
    Object.keys(map).forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.textContent = map[id];
    });
  }

  function statusLabel(status) {
    var key = 'status.' + String(status || '').toUpperCase();
    var dict = I18N[lang] || I18N.zh;
    if (dict[key] !== undefined) return dict[key];
    return dict['status.UNKNOWN'];
  }

  function setStep(step) {
    state.step = step;
    var progress = {
      form: { choose: 'active', confirm: '', pay: '', done: '' },
      providers: { choose: 'done', confirm: 'done', pay: 'active', done: '' },
      pay: { choose: 'done', confirm: 'done', pay: 'active', done: '' },
      polling: { choose: 'done', confirm: 'done', pay: 'active', done: '' },
      done: { choose: 'done', confirm: 'done', pay: 'done', done: 'done' },
      error: null
    }[step];

    if (progress) {
      $$('[data-step-item]').forEach(function (item) {
        var mark = progress[item.getAttribute('data-step-item')] || '';
        item.classList.toggle('is-active', mark === 'active');
        item.classList.toggle('is-done', mark === 'done');
      });
    }

    // error 步骤保留原步骤条，仅把焦点交给错误面板
    $('#stage').hidden = (step === 'form');
    $('#orderWrap').hidden = (step !== 'form');
  }

  function setStage(html) {
    var stage = $('#stage');
    stage.hidden = false;
    stage.innerHTML = html;
    // G4：步骤切换后把焦点交给新面板，键盘/读屏用户不会停留在旧位置
    if (lastFocusedStep !== state.step) {
      lastFocusedStep = state.step;
      try { stage.focus({ preventScroll: true }); } catch (e) { stage.focus(); }
    }
  }

  var lastFocusedStep = '';

  /* ======================================================================
     8. 主流程：创建订单
     ====================================================================== */
  function handleSubmit(event) {
    if (event) event.preventDefault();

    var emailValue = String($('#email').value || '').trim();
    var ok = true;

    if (!state.product) {
      showError('#planError', t('form.error.planRequired'));
      var firstCard = $('.plan-card__input', $('#planList'));
      if (firstCard) firstCard.focus();
      ok = false;
    } else {
      hideError('#planError');
    }

    if (!validateEmailField()) {
      ok = false;
    }

    if (!ok) return;

    state.email = emailValue;

    var op = orderParams();
    var payload = {
      productId: state.product.sku,
      currency: op.currency,
      locale: op.locale,
      customerEmail: emailValue,
      returnUrl: buildPageUrl({ machineId: state.machineId }),
      cancelUrl: buildPageUrl({ machineId: state.machineId, canceled: '1' })
    };
    // 有机器码才下发：服务端据此在支付成功后直接签发绑定设备的 License
    if (state.machineId) payload.machineId = state.machineId;

    setBusy(true, t('form.submitting'));
    announce(t('form.submitting'));

    apiCreateCheckout(payload).then(function (data) {
      setBusy(false);
      state.checkoutId = data.checkoutId || '';
      state.orderNumber = data.orderNumber || '';
      state.status = String(data.status || 'CREATED').toUpperCase();
      state.paymentMethods = Array.isArray(data.paymentMethods) ? data.paymentMethods.slice() : [];
      syncExpiry(data.expiresAt);
      state.license = data.license || '';
      state.redeemCode = data.redeemCode || '';
      saveSession();

      // 地址栏带上 checkoutId：刷新即从该会话恢复
      try {
        history.replaceState(null, '', buildPageUrl({
          checkoutId: state.checkoutId,
          machineId: state.machineId
        }));
      } catch (e) { /* 部分环境不支持 replaceState，忽略 */ }

      renderSummary();

      if (state.license || state.redeemCode) {
        finish({ status: state.status, license: state.license, redeemCode: state.redeemCode });
        return;
      }
      if (!state.paymentMethods.length) {
        renderError({ kind: 'api', code: 'NO_PAYMENT_METHOD', message: '', traceId: '' }, { back: 'form' });
        return;
      }
      renderProviderChoice(state.paymentMethods);
      announce(t('pay.title'));
    }).catch(function (err) {
      setBusy(false);
      renderError(err, { back: 'form' });
    });
  }

  /** P4：失焦即验，错误内联到字段下方；修正输入后即时消除 */
  function validateEmailField(options) {
    var opts = options || {};
    var value = String($('#email').value || '').trim();
    if (!value) {
      if (!opts.silentEmpty) {
        showError('#emailError', t('form.error.emailRequired'));
        $('#email').setAttribute('aria-invalid', 'true');
        return false;
      }
      hideError('#emailError');
      $('#email').removeAttribute('aria-invalid');
      return true;
    }
    if (!isValidEmail(value)) {
      showError('#emailError', t('form.error.emailInvalid'));
      $('#email').setAttribute('aria-invalid', 'true');
      return false;
    }
    hideError('#emailError');
    $('#email').removeAttribute('aria-invalid');
    return true;
  }

  /* ======================================================================
     9. 选择支付方式
     ====================================================================== */
  function renderProviderChoice(methods) {
    var list = Array.isArray(methods) ? methods : [];
    setStep('providers');

    var price = state.product
      ? (function () { var p = pickPrice(state.product); return p.symbol + formatMoney(p.value) + priceSuffix(state.product.cycle); })()
      : '—';

    var items = list.map(function (raw, index) {
      var key = String(raw || '').toUpperCase();
      var meta = PAYMENT_METHODS[key] || {
        label: { zh: key, en: key },
        note: { zh: '', en: '' },
        icon: key.slice(0, 1),
        color: '#4C5B61'
      };
      var installmentRow = key === 'ALIPAY'
        ? '<div class="installment-row"><label>' + esc(t('pay.installment'))
            + '<select data-installment>'
            + '<option value="">' + esc(t('pay.installmentNone')) + '</option>'
            + [3, 6, 12, 24].map(function (n) {
                return '<option value="' + n + '">' + esc(t('pay.installmentOption', { n: n })) + '</option>';
              }).join('')
            + '</select></label></div>'
        : '';
      return '' +
        '<li>' +
          '<button class="provider-card" type="button" data-provider="' + esc(key) + '">' +
            '<span class="provider-card__icon" style="background:' + esc(meta.color) + '" aria-hidden="true">' +
              esc(meta.icon) +
            '</span>' +
            '<span>' +
              '<span class="provider-card__name">' + esc(pickText(meta.label)) + '</span>' +
              '<span class="provider-card__note">' + esc(pickText(meta.note)) + '</span>' +
            '</span>' +
            '<span class="provider-card__amount">' +
              (index === 0 ? '<span class="badge badge--ok">' + esc(t('pay.recommended')) + '</span>' : '') +
              '<span class="provider-card__price">' + esc(price) + '</span>' +
              '<span class="provider-card__arrow" aria-hidden="true">→</span>' +
            '</span>' +
          '</button>' +
          installmentRow +
        '</li>';
    }).join('');

    setStage('' +
      '<h2 class="panel__title">' + esc(t('pay.title')) + '</h2>' +
      '<p class="panel__desc">' + esc(t('pay.desc')) + '</p>' +
      '<div class="region-row">' +
        '<span class="region-chip">' + esc(t(currentRegion() === 'CHINA' ? 'pay.regionChina' : 'pay.regionGlobal')) + '</span>' +
        '<p class="region-row__note">' + esc(t(lang === 'zh' ? 'pay.regionSwitchZh' : 'pay.regionSwitchEn')) + '</p>' +
      '</div>' +
      '<ul class="provider-list">' + items + '</ul>' +
      recapHtml() +
      '<div class="panel__actions">' +
        '<button class="btn btn--ghost" type="button" id="backToFormBtn">' +
          esc(t('pay.backToForm')) +
        '</button>' +
      '</div>');

    $$('[data-provider]', $('#stage')).forEach(function (btn) {
      btn.addEventListener('click', function () {
        chooseProvider(btn.getAttribute('data-provider'));
      });
    });
    $$('[data-installment]', $('#stage')).forEach(function (sel) {
      sel.addEventListener('change', function () {
        state.installmentNum = sel.value ? Number(sel.value) : null;
      });
    });

    var backBtn = $('#backToFormBtn');
    if (backBtn) {
      backBtn.addEventListener('click', function () {
        // P8：会话与已填信息保留，用户改完邮箱/档位可重新确认下单
        state.step = 'form';
        lastFocusedStep = '';
        setStep('form');
        renderPlanCards();
        renderSummary();
        if ($('#email') && state.email) $('#email').value = state.email;
        announce(t('steps.choose'));
        if ($('#email')) $('#email').focus();
      });
    }
  }

  /** P8/P3：支付前复核区（档位 / 金额 / 邮箱 / 机器码） */
  function recapHtml() {
    var p = state.product ? pickPrice(state.product) : null;
    var amount = p ? p.symbol + formatMoney(p.value) + priceSuffix(state.product.cycle) : '—';
    return '<div class="recap" aria-label="' + esc(t('pay.recapTitle')) + '">' +
      '<div class="recap__row"><span>' + esc(t('summary.plan')) + '</span><b>' +
        esc(state.product ? pickText(state.product.name) : '—') + '</b></div>' +
      '<div class="recap__row"><span>' + esc(t('summary.amount')) + '</span><b>' + esc(amount) + '</b></div>' +
      '<div class="recap__row"><span>' + esc(t('summary.email')) + '</span><b>' + esc(state.email || '—') + '</b></div>' +
      '<div class="recap__row"><span>' + esc(t('summary.machine')) + '</span><b class="mono">' +
        esc(state.machineId || t('summary.notBound')) + '</b></div>' +
    '</div>';
  }

  function chooseProvider(provider) {
    if (!state.checkoutId) {
      renderError({ kind: 'api', code: 'CHECKOUT_NOT_FOUND', message: '', traceId: '' }, { back: 'form' });
      return;
    }

    setBusy(true, t('form.submitting'));
    $$('[data-provider]', $('#stage')).forEach(function (btn) { btn.disabled = true; });

    apiSelectProvider(state.checkoutId, provider, provider === 'ALIPAY' ? state.installmentNum : null).then(function (data) {
      setBusy(false);
      handlePaymentCreated(data, provider);
    }).catch(function (err) {
      setBusy(false);
      renderError(err, { back: 'providers' });
    });
  }

  /** 处理 select-provider 的返回：拿到支付入口才继续，否则报错 */
  function handlePaymentCreated(data, fallbackProvider) {
    state.provider = data.provider || fallbackProvider || '';
    state.paymentMode = data.paymentMode || '';
    state.redirectUrl = data.redirectUrl || data.payUrl || '';
    state.qrcode = data.qrcode || '';
    state.status = String(data.status || 'PENDING').toUpperCase();
    syncExpiry(data.expiresAt || state.expiresAt);
    state.license = data.license || state.license;
    state.redeemCode = data.redeemCode || state.redeemCode;
    saveSession();
    renderSummary();

    if (state.license || state.redeemCode) {
      finish(data);
      return;
    }
    if (state.redirectUrl) {
      renderRedirect();
      startRedirectCountdown();
      return;
    }
    if (state.qrcode) {
      renderQrcode();
      // keepStage：保留二维码，只在其内侧更新等待提示
      startPolling({ keepStage: true });
      return;
    }
    renderError({ kind: 'api', code: 'noPaymentEntry', message: '', traceId: '' }, { back: 'providers' });
  }

  /* ======================================================================
     10. redirect 模式：跳转第三方支付页
     ====================================================================== */
  function renderRedirect() {
    setStep('pay');
    var meta = getProviderMeta(state.provider);
    setStage('' +
      '<h2 class="panel__title">' + esc(t('pay.redirectTitle')) + '</h2>' +
      '<p class="panel__desc">' + esc(t('pay.redirectHint')) + '</p>' +
      '<div class="notice notice--busy">' +
        '<span class="spinner" aria-hidden="true"></span>' +
        '<span id="redirectCountdown">' + esc(t('pay.redirectCountdown', { s: secOf(REDIRECT_DELAY_MS) })) + '</span>' +
      '</div>' +
      '<div class="panel__actions">' +
        '<a class="btn btn--primary" href="' + esc(state.redirectUrl) + '" id="goPayBtn">' +
          esc(t('pay.goPay')) + ' · ' + esc(pickText(meta.label)) +
        '</a>' +
      '</div>' +
      '<p class="panel__desc">' + esc(t('pay.reselectHint')) + '</p>' +
      '<div class="panel__actions">' +
        '<button class="btn btn--ghost" type="button" id="reselectBtn">' + esc(t('pay.reselectProvider')) + '</button>' +
      '</div>');

    bindSecondaryActions();
  }

  function startRedirectCountdown() {
    stopRedirectCountdown();
    var remaining = REDIRECT_DELAY_MS;
    redirectTimer = setInterval(function () {
      remaining -= 1000;
      var el = $('#redirectCountdown');
      if (!el) { stopRedirectCountdown(); return; }
      if (remaining <= 0) {
        stopRedirectCountdown();
        window.location.assign(state.redirectUrl);
        return;
      }
      el.textContent = t('pay.redirectCountdown', { s: secOf(remaining) });
    }, 1000);
  }

  function stopRedirectCountdown() {
    if (redirectTimer) {
      clearInterval(redirectTimer);
      redirectTimer = null;
    }
  }

  /* ======================================================================
     11. qrcode 模式：本地渲染二维码
     ====================================================================== */
  function renderQrcode() {
    setStep('pay');
    setStage('' +
      '<h2 class="panel__title">' + esc(t('pay.qrTitle')) + '</h2>' +
      '<p class="panel__desc">' + esc(t('pay.qrHint')) + '</p>' +
      '<div class="cd-row" id="cdRow">' +
        '<span class="cd-row__label">' + esc(t('pay.countdownLabel')) + '</span>' +
        '<span class="cd-text" id="cdText">--:--</span>' +
        '<span class="cd-bar" aria-hidden="true"><span class="cd-bar__fill" id="cdBar"></span></span>' +
      '</div>' +
      '<div class="qr-box">' +
        '<div class="qr-box__canvas" id="qrCanvas"></div>' +
        '<div class="qr-box__aside">' +
          '<div id="qrFallback" hidden>' +
            '<p class="notice notice--warn">' + esc(t('pay.qrFallbackTitle')) + '：' + esc(t('pay.qrFallbackHint')) + '</p>' +
            '<a class="qr-link" id="qrLinkText" href="' + esc(state.qrcode) + '" rel="noopener noreferrer">' +
              esc(state.qrcode) +
            '</a>' +
            '<div class="qr-actions">' +
              '<button class="btn btn--ghost btn--sm" type="button" data-copy-of="qrLinkText">' + esc(t('pay.copyLink')) + '</button>' +
              '<a class="btn btn--ghost btn--sm" href="' + esc(state.qrcode) + '" target="_blank" rel="noopener noreferrer">' + esc(t('pay.openLink')) + '</a>' +
            '</div>' +
          '</div>' +
        '</div>' +
      '</div>' +
      '<div id="pollSlot"></div>' +
      '<div class="qr-actions">' +
        '<button class="btn btn--ghost btn--sm" type="button" id="refreshPollBtn">' + esc(t('pay.refreshStatus')) + '</button>' +
        '<button class="btn btn--ghost btn--sm" type="button" id="reselectBtn">' + esc(t('pay.reselectProvider')) + '</button>' +
        '<button class="btn btn--ghost btn--sm" type="button" id="copyPayLinkBtn">' + esc(t('pay.copyPayLink')) + '</button>' +
      '</div>');

    var rendered = renderQrSvg('#qrCanvas', state.qrcode);
    if (!rendered) $('#qrFallback').hidden = false;

    bindCopyButtons();
    bindSecondaryActions();
    bindQrManualActions();
    renderPollSlot();
  }

  /** P10：等待期三个手动出口，异常时不必刷新整页丢会话 */
  function bindQrManualActions() {
    var refresh = $('#refreshPollBtn');
    if (refresh) {
      refresh.addEventListener('click', function () {
        refresh.disabled = true;
        refresh.textContent = t('pay.refreshBusy');
        pollOnce();
        setTimeout(function () {
          refresh.disabled = false;
          refresh.textContent = t('pay.refreshStatus');
        }, 800);
      });
    }
    var copyBtn = $('#copyPayLinkBtn');
    if (copyBtn) {
      copyBtn.addEventListener('click', function () {
        copyText(state.qrcode).then(function () {
          toast(t('toast.copied'));
        }, function () {
          toast(t('toast.copyFailed'));
        });
      });
    }
  }

  /**
   * 用本地 vendor 库渲染二维码 SVG。
   * @returns {boolean} 渲染成功为 true；库缺失或异常为 false（调用方需降级）
   */
  function renderQrSvg(selector, text) {
    var box = $(selector);
    if (!box) return false;
    if (typeof window.qrcode !== 'function') return false;

    try {
      var qr = window.qrcode(0, 'M');
      qr.addData(String(text || ''), 'Byte');
      qr.make();

      var count = qr.getModuleCount();
      var quiet = 2;
      var size = count + quiet * 2;
      var cells = '';
      for (var row = 0; row < count; row++) {
        for (var col = 0; col < count; col++) {
          if (qr.isDark(row, col)) {
            cells += '<rect x="' + (col + quiet) + '" y="' + (row + quiet) + '" width="1" height="1"/>';
          }
        }
      }
      box.innerHTML =
        '<svg class="qr-box__svg" viewBox="0 0 ' + size + ' ' + size + '" role="img" ' +
          'aria-label="' + esc(t('pay.qrAlt')) + '">' +
          '<rect x="0" y="0" width="' + size + '" height="' + size + '" fill="#ffffff"/>' +
          cells +
        '</svg>';
      return true;
    } catch (e) {
      return false;
    }
  }

  function getProviderMeta(provider) {
    var key = String(provider || '').toUpperCase();
    return PAYMENT_METHODS[key] || { label: { zh: key, en: key }, note: { zh: '', en: '' } };
  }

  /** 支付方式相关面板的公共按钮绑定 */
  function bindSecondaryActions() {
    var reselect = $('#reselectBtn');
    if (reselect) {
      reselect.addEventListener('click', function () {
        stopRedirectCountdown();
        stopPolling();
        renderProviderChoice(state.paymentMethods);
      });
    }
  }

  /* ======================================================================
     12. 轮询支付结果
     ====================================================================== */
  /**
   * 启动轮询。
   * @param {Object=} options 传 { keepStage: true } 表示不要重绘主面板
   *                  （二维码面板需保留二维码本身，只更新其中的等待提示）
   */
  function startPolling(options) {
    var opts = options || {};
    stopPolling();
    poll.active = true;
    poll.startedAt = Date.now();
    poll.attempts = 0;
    poll.consecutiveErrors = 0;
    poll.paidWithoutCredential = 0;

    if (opts.keepStage) {
      renderPollSlot();
      startElapsedTicker();
    } else {
      setStep('polling');
      renderPolling();
    }
    pollOnce();
  }

  function stopPolling() {
    poll.active = false;
    if (poll.timer) { clearTimeout(poll.timer); poll.timer = null; }
    if (poll.ticker) { clearInterval(poll.ticker); poll.ticker = null; }
  }

  function nextDelay() {
    if (poll.attempts < POLL.stableCount) return POLL.intervalMs;
    var steps = Math.min(poll.attempts - POLL.stableCount + 1, POLL.maxBackoffSteps);
    return Math.min(POLL.maxIntervalMs, Math.round(POLL.intervalMs * Math.pow(POLL.backoffFactor, steps)));
  }

  function pollOnce() {
    if (!poll.active) return;

    // 三重终止保护：总时长、总次数、会话过期时间
    if (Date.now() - poll.startedAt >= POLL.maxDurationMs) {
      pollingFailed({ kind: 'client', code: 'TIMEOUT', message: '', traceId: '' });
      return;
    }
    if (poll.attempts >= POLL.maxAttempts) {
      pollingFailed({ kind: 'client', code: 'TIMEOUT', message: '', traceId: '' });
      return;
    }
    if (state.expiresAt && Date.now() >= state.expiresAt) {
      stopPolling();
      renderError({ kind: 'client', code: 'EXPIRED', message: '', traceId: '' }, { back: 'form' });
      return;
    }

    poll.attempts += 1;

    apiFetchStatus(state.checkoutId).then(function (data) {
      if (!poll.active) return;
      poll.consecutiveErrors = 0;

      state.status = String(data.status || '').toUpperCase();
      syncExpiry(data.expiresAt || state.expiresAt);
      state.license = data.license || state.license;
      state.redeemCode = data.redeemCode || state.redeemCode;
      saveSession();
      renderSummary();

      if (ACTIVE_STATUS[state.status]) {
        if (state.status === 'PAID') {
          // 服务端的发放是异步幂等的：PAID 但凭证尚未返回时再等几轮
          poll.paidWithoutCredential += 1;
          if (poll.paidWithoutCredential >= 5) {
            stopPolling();
            finish(data);
            return;
          }
        }
        scheduleNext();
        return;
      }

      // 终态
      stopPolling();
      if (state.status === 'PAID') {
        finish(data);
        return;
      }
      renderTerminalState(data);
    }).catch(function (err) {
      if (!poll.active) return;
      poll.consecutiveErrors += 1;
      if (poll.consecutiveErrors >= POLL.maxConsecutiveErrors) {
        pollingFailed(err);
        return;
      }
      scheduleNext();
    });
  }

  function scheduleNext() {
    var delay = nextDelay();
    if (poll.timer) clearTimeout(poll.timer);
    poll.timer = setTimeout(pollOnce, delay);
    renderPollSlot();
  }

  /** redirect/qrcode 面板下方的等待提示 + 会话倒计时（P9） */
  function renderPollSlot() {
    updateCountdown();
    var slot = $('#pollSlot');
    if (!slot) return;
    var elapsed = secOf(Date.now() - poll.startedAt);
    slot.innerHTML =
      '<p class="notice notice--busy">' +
        '<span class="spinner" aria-hidden="true"></span>' +
        '<span>' + esc(t('poll.hint')) + ' ' + esc(t('poll.elapsed', { s: elapsed })) + '</span>' +
      '</p>';
  }

  function updateCountdown() {
    var textEl = $('#cdText');
    if (!textEl || !state.expiresAt) return;
    var remaining = Math.max(0, state.expiresAt - Date.now());
    var total = state.expiresTotalMs || Math.max(remaining, 1000);
    var ratio = Math.max(0, Math.min(1, remaining / total));
    var seconds = Math.floor(remaining / 1000);
    var hh = Math.floor(seconds / 3600);
    var mm = Math.floor((seconds % 3600) / 60);
    var ss = seconds % 60;
    var mmText = (mm < 10 ? '0' : '') + mm;
    var ssText = (ss < 10 ? '0' : '') + ss;
    // 会话默认 2 小时：超过 1 小时用 h:mm:ss，避免出现 119:59 这种读数
    textEl.textContent = hh > 0 ? hh + ':' + mmText + ':' + ssText : mmText + ':' + ssText;
    var bar = $('#cdBar');
    if (bar) bar.style.width = Math.round(ratio * 100) + '%';
    var row = $('#cdRow');
    if (row) row.classList.toggle('is-urgent', remaining <= 60 * 1000);
  }

  function renderPolling() {
    setStep('polling');
    var canReopen = Boolean(state.provider);
    setStage('' +
      '<h2 class="panel__title">' + esc(t('poll.title')) + '</h2>' +
      '<p class="panel__desc">' + esc(t('poll.hint')) + '</p>' +
      '<div id="pollSlot"></div>' +
      (canReopen
        ? '<p class="panel__desc">' + esc(t('poll.openPaymentHint')) + '</p>' +
          '<div class="panel__actions">' +
            '<button class="btn btn--primary" type="button" id="reopenPayBtn">' + esc(t('poll.openPayment')) + '</button>' +
          '</div>'
        : '') +
      '<div class="panel__actions">' +
        '<button class="btn btn--ghost" type="button" id="reselectBtn">' + esc(t('pay.reselectProvider')) + '</button>' +
      '</div>');

    renderPollSlot();
    startElapsedTicker();

    var reopenBtn = $('#reopenPayBtn');
    if (reopenBtn) {
      reopenBtn.addEventListener('click', function () {
        // 显式重选渠道：相当于向服务端再要一次支付入口（需用户主动触发，避免自动重复创建）
        chooseProvider(state.provider);
      });
    }
    bindSecondaryActions();
  }

  function startElapsedTicker() {
    if (poll.ticker) clearInterval(poll.ticker);
    poll.ticker = setInterval(function () {
      if (!poll.active) { stopPolling(); return; }
      renderPollSlot();
    }, 1000);
  }

  /** 轮询失败统一出口：给出可读提示 + 重试入口 */
  function pollingFailed(err) {
    stopPolling();
    renderError(err, { back: 'form', retryPoll: Boolean(state.checkoutId) });
  }

  /* ======================================================================
     13. 终态渲染：License / 兑换码 / 失败
     ====================================================================== */
  function finish(data) {
    stopRedirectCountdown();
    stopPolling();
    state.step = 'done';
    state.status = 'PAID';
    state.license = (data && data.license) || state.license || '';
    state.redeemCode = (data && data.redeemCode) || state.redeemCode || '';
    saveSession();
    renderSummary();
    setStep('done');
    renderResult();
    announce(state.license ? t('done.licenseTitle') : t('done.redeemTitle'));
  }

  function renderResult() {
    var orderRow = state.orderNumber
      ? '<p class="panel__desc">' + esc(t('done.orderLabel')) + '：<code>' + esc(state.orderNumber) + '</code></p>'
      : '';
    var subscriptionNote = (state.product && state.product.recurring)
      ? '<p class="form-note">' + esc(t('done.subscriptionNote')) + '</p>'
      : '';
    var actions = '' +
      '<div class="panel__actions">' +
        '<a class="btn btn--primary" href="../account/">' + esc(t('done.goAccount')) + '</a>' +
        '<a class="btn btn--ghost" href="https://www.ywhome.top">' + esc(t('error.goHome')) + '</a>' +
      '</div>';

    if (state.license) {
      setStage('' +
        '<h2 class="result__title">' +
          '<span class="result__badge" aria-hidden="true">✓</span>' + esc(t('done.licenseTitle')) +
        '</h2>' +
        '<p class="result__desc">' + esc(t('done.licenseHint')) + '</p>' +
        orderRow +
        '<div class="code-block"><code id="credentialValue">' + esc(state.license) + '</code></div>' +
        '<div class="qr-actions">' +
          '<button class="btn btn--primary btn--sm" type="button" data-copy-of="credentialValue">' + esc(t('done.copyLicense')) + '</button>' +
          '<button class="btn btn--ghost btn--sm" type="button" data-download="license">' + esc(t('done.download')) + '</button>' +
        '</div>' +
        subscriptionNote +
        '<h3 class="panel__title result__next">' + esc(t('done.backTitle')) + '</h3>' +
        '<ol class="result__steps">' +
          '<li>' + esc(t('done.licenseStep1')) + '</li>' +
          '<li>' + esc(t('done.licenseStep2')) + '</li>' +
          '<li>' + esc(t('done.licenseStep3')) + '</li>' +
        '</ol>' +
        '<p class="form-note">' + esc(t('done.backHint')) + '</p>' +
        actions);
      bindCopyButtons();
      bindDownloads();
      return;
    }

    if (state.redeemCode) {
      setStage('' +
        '<h2 class="result__title">' +
          '<span class="result__badge" aria-hidden="true">✓</span>' + esc(t('done.redeemTitle')) +
        '</h2>' +
        '<p class="result__desc">' + esc(t('done.redeemHint')) + '</p>' +
        orderRow +
        '<div class="code-block"><code id="credentialValue">' + esc(state.redeemCode) + '</code></div>' +
        '<div class="qr-actions">' +
          '<button class="btn btn--primary btn--sm" type="button" data-copy-of="credentialValue">' + esc(t('done.copyCode')) + '</button>' +
          '<button class="btn btn--ghost btn--sm" type="button" data-download="redeem">' + esc(t('done.download')) + '</button>' +
        '</div>' +
        subscriptionNote +
        '<h3 class="panel__title result__next">' + esc(t('done.backTitle')) + '</h3>' +
        '<ol class="result__steps">' +
          '<li>' + esc(t('done.redeemStep1')) + '</li>' +
          '<li>' + esc(t('done.redeemStep2')) + '</li>' +
          '<li>' + esc(t('done.licenseStep3')) + '</li>' +
        '</ol>' +
        '<p class="form-note">' + esc(t('done.backHint')) + '</p>' +
        actions);
      bindCopyButtons();
      bindDownloads();
      return;
    }

    // PAID 但凭证尚未生成：不展示空白，明确告知用户等待路径
    setStage('' +
      '<h2 class="result__title">' + esc(t('done.pendingTitle')) + '</h2>' +
      '<p class="result__desc">' + esc(t('done.pendingHint', { email: SUPPORT_EMAIL })) + '</p>' +
      orderRow + actions);
  }

  /** FAILED / EXPIRED / CANCELED 等非成功终态 */
  function renderTerminalState(data) {
    var code = state.status === 'EXPIRED' ? 'EXPIRED'
      : state.status === 'CANCELED' ? 'CHECKOUT_NOT_FOUND'
      : 'PAYMENT_CREATE_FAILED';
    renderError({ kind: 'client', code: code, message: data && data.message ? data.message : '', traceId: '' },
      { back: 'form' });
  }

  /* ======================================================================
     14. 错误渲染
     ====================================================================== */
  var lastError = null;
  var lastErrorOptions = null;

  function renderError(err, options) {
    stopPolling();
    stopRedirectCountdown();
    lastError = err || { kind: 'unknown', code: 'UNKNOWN', message: '', traceId: '' };
    lastErrorOptions = options || {};
    state.step = 'error';
    setStep('error');

    var code = lastError.code || 'UNKNOWN';
    var dict = I18N[lang] || I18N.zh;
    var headline = (dict['err.' + code] !== undefined)
      ? dict['err.' + code]
      : t('err.UNKNOWN');

    var detail = '';
    var parts = [];
    if (lastError.message) parts.push(t('error.detailLabel') + lastError.message);
    if (lastError.traceId) parts.push(t('error.traceLabel') + lastError.traceId);
    if (parts.length) {
      detail = '<p class="error-box__detail">' + esc(parts.join('　·　')) + '</p>';
    }

    var canRetryPoll = Boolean(lastErrorOptions.retryPoll && state.checkoutId);
    var backLabel = t('error.restart');

    setStage('' +
      '<h2 class="panel__title">' + esc(t('error.title')) + '</h2>' +
      '<div class="notice notice--error">' + esc(headline) + '</div>' +
      detail +
      '<p class="panel__desc">' + esc(t('error.supportHint', { email: SUPPORT_EMAIL })) + '</p>' +
      '<div class="panel__actions">' +
        (canRetryPoll
          ? '<button class="btn btn--primary" type="button" id="retryPollBtn">' + esc(t('error.retry')) + '</button>'
          : '') +
        '<button class="btn btn--ghost" type="button" id="restartBtn">' + esc(backLabel) + '</button>' +
        '<a class="btn btn--ghost" href="https://www.ywhome.top">' + esc(t('error.goHome')) + '</a>' +
      '</div>');

    announce(headline);

    var retryBtn = $('#retryPollBtn');
    if (retryBtn) {
      retryBtn.addEventListener('click', function () {
        startPolling();
      });
    }
    var restartBtn = $('#restartBtn');
    if (restartBtn) {
      restartBtn.addEventListener('click', function () {
        clearSession();
        resetToForm();
      });
    }
  }

  function resetToForm() {
    state.checkoutId = '';
    state.orderNumber = '';
    state.status = '';
    state.provider = '';
    state.paymentMode = '';
    state.redirectUrl = '';
    state.qrcode = '';
    state.expiresAt = 0;
    state.expiresTotalMs = 0;
    state.license = '';
    state.redeemCode = '';
    setStep('form');
    renderSummary();
    announce(t('steps.choose'));
  }

  /* ======================================================================
     15. 表单提示 / 忙碌态 / 复制
     ====================================================================== */
  function showError(selector, message) {
    var el = $(selector);
    if (!el) return;
    el.textContent = message;
    el.hidden = false;
  }

  function hideError(selector) {
    var el = $(selector);
    if (!el) return;
    el.textContent = '';
    el.hidden = true;
  }

  var busyLabel = '';
  function setBusy(busy, label) {
    var btn = $('#submitBtn');
    if (btn) {
      btn.disabled = Boolean(busy);
      if (busy) {
        busyLabel = btn.textContent;
        btn.textContent = label || busyLabel;
      } else if (busyLabel) {
        btn.textContent = t('form.submit');
      }
    }
  }

  function bindCopyButtons() {
    $$('[data-copy-of]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var target = document.getElementById(btn.getAttribute('data-copy-of'));
        if (!target) return;
        copyText(target.textContent).then(function () {
          toast(t('toast.copied'));
        }, function () {
          toast(t('toast.copyFailed'));
        });
      });
    });
  }

  /** P12：许可证/兑换码另存为 txt，方便随邮件或工单转发 */
  function bindDownloads() {
    $$('[data-download]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var type = btn.getAttribute('data-download');
        var value = type === 'redeem' ? state.redeemCode : state.license;
        if (!value) return;
        var header = type === 'redeem'
          ? t('done.copyCode') + '\r\n'
          : t('done.copyLicense') + '\r\n';
        downloadText(type === 'redeem' ? 'redeem-code.txt' : 'license.txt', header + value + '\r\n');
        toast(t('done.downloaded'));
      });
    });
  }

  function downloadText(filename, content) {
    var blob = new Blob(['\ufeff' + content], { type: 'text/plain;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  /** G1：统一 toast 反馈（2 秒自动消失，可堆积） */
  function toast(message) {
    var wrap = $('#toastWrap');
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

  /**
   * 复制文本到剪贴板：优先异步剪贴板 API，不可用时回落到临时 textarea。
   * @returns {Promise<void>}
   */
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      try {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', 'readonly');
        area.style.position = 'fixed';
        area.style.top = '-1000px';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = document.execCommand('copy');
        document.body.removeChild(area);
        ok ? resolve() : reject(new Error('copy-command-failed'));
      } catch (e) {
        reject(e);
      }
    });
  }

  /* ======================================================================
     16. 刷新 / 回跳恢复
     ====================================================================== */
  function restoreCheckout() {
    var params = getQueryParams();

    if (params.canceled === '1') {
      var savedAfterCancel = readSession();
      if (!savedAfterCancel || !savedAfterCancel.checkoutId) return false;
    }

    var target = params.checkoutId || '';
    var saved = readSession();

    if (!target && saved && saved.checkoutId) {
      if (saved.expiresAt && Date.now() >= saved.expiresAt) {
        clearSession();
        return false;
      }
      target = saved.checkoutId;
      hydrateFromSession(saved);
    }

    if (!target) return false;

    state.checkoutId = target;
    if (params.machineId) state.machineId = params.machineId;
    renderMachineBox();
    renderSummary();

    // 跳过的(session)支付方式列表先渲染占位，拿到状态后再决定显示什么
    apiFetchStatus(target).then(function (data) {
      state.status = String(data.status || '').toUpperCase();
      state.orderNumber = data.orderNumber || state.orderNumber;
      syncExpiry(data.expiresAt || state.expiresAt);
      // P16：从账号页「继续支付」进入时用会话上的邮箱/机器码补全摘要
      if (data.email) state.email = data.email;
      if (data.machineId) state.machineId = data.machineId;
      if ($('#email') && state.email) $('#email').value = state.email;
      renderMachineBox();
      state.paymentMethods = Array.isArray(data.paymentMethods) ? data.paymentMethods.slice() : state.paymentMethods;
      state.provider = data.provider || state.provider;
      state.license = data.license || '';
      state.redeemCode = data.redeemCode || '';
      saveSession();
      renderSummary();

      if (state.status === 'EXPIRED') {
        renderError({ kind: 'client', code: 'EXPIRED', message: '', traceId: '' }, { back: 'form' });
        return;
      }
      if (state.license || state.redeemCode) {
        finish(data);
        return;
      }
      if (state.status === 'CREATED') {
        if (!state.paymentMethods.length) {
          // 服务端未随状态返回可选支付方式（未重启的旧实例）或该区域渠道为空：
          // 不死路报错——清掉本地会话回退下单表单重下（邮箱/机器码已恢复在表单里，可直接重新提交）
          clearSession();
          resetToForm();
          return;
        }
        renderProviderChoice(state.paymentMethods);
        announce(t('pay.title'));
        return;
      }
      // PENDING：直接续轮询（用户可能在别的标签页已付款）
      startPolling();
    }).catch(function (err) {
      if (err && err.code === 'CHECKOUT_NOT_FOUND') {
        clearSession();
      }
      renderError(err, { back: 'form' });
    });

    return true;
  }

  /** 用本地暂存的会话快照补全状态 */
  function hydrateFromSession(snapshot) {
    // URL 参数（C9）优先级高于本地快照：用户是点了某个档位按钮才进来的，不该被上次的会话覆盖
    if (snapshot.productId && !state.product) {
      var restored = products.filter(function (item) { return item.sku === snapshot.productId; })[0] || null;
      state.product = (restored && providersForRegion(restored).length) ? restored : null;
    }
    if (snapshot.email) state.email = snapshot.email;
    if (snapshot.machineId) state.machineId = snapshot.machineId;
    if (snapshot.orderNumber) state.orderNumber = snapshot.orderNumber;
    if (snapshot.provider) state.provider = snapshot.provider;
    if (snapshot.redirectUrl) state.redirectUrl = snapshot.redirectUrl;
    if (snapshot.qrcode) state.qrcode = snapshot.qrcode;
    if (snapshot.expiresAt) state.expiresAt = snapshot.expiresAt;
    if ($('#email') && state.email) $('#email').value = state.email;
    renderPlanCards();
  }

  /* ======================================================================
     17. 初始化
     ====================================================================== */
  function init() {
    // 语言切换（与 index.html 共用同一 localStorage 键）
    $('#langSwitch').addEventListener('click', function () {
      applyLang(lang === 'zh' ? 'en' : 'zh');
    });

    // 客户端跳转时会带 machineId：命中则支付完成后直接签发绑定该设备的 License
    var params = getQueryParams();
    if (params.machineId) state.machineId = String(params.machineId).trim();

    $('#orderForm').addEventListener('submit', handleSubmit);
    $('#email').addEventListener('input', function () {
      state.email = String($('#email').value || '').trim();
      hideError('#emailError');
      $('#email').removeAttribute('aria-invalid');
      renderSummary();
    });
    // P4：失焦即验；空值不在失焦时打断用户，只在提交时提示必填
    $('#email').addEventListener('blur', function () {
      validateEmailField({ silentEmpty: true });
    });
    var machineCopyBtn = $('#machineCopyBtn');
    if (machineCopyBtn) {
      machineCopyBtn.addEventListener('click', function () {
        copyText(state.machineId).then(function () {
          toast(t('toast.copied'));
        }, function () {
          toast(t('toast.copyFailed'));
        });
      });
    }

    renderPlanSkeleton();

    // 先加载产品目录，再做初始化渲染与会话恢复
    // C9（2026-09-20）：官网产品区按钮携带 ?product=ai-tools&productId=<sku>。
    // 两个参数职责分离（V10 产品维度）：product 只作目录过滤，productId 只作 SKU 预选。
    fetchProducts(String(params.product || '').trim()).then(function (list) {
      products = Array.isArray(list) ? list : [];
      var presetSku = String(params.productId || '').trim();
      if (presetSku) {
        // 预选匹配不到（如旧客户端把产品码传进 productId）即静默不预选，保持原行为；
        // 当前区域无可用渠道的套餐也不预选（否则提交时才报 NO_PAYMENT_METHOD）
        var preset = products.filter(function (item) { return item.sku === presetSku; })[0] || null;
        state.product = (preset && providersForRegion(preset).length) ? preset : null;
      }
      applyLang(lang);
      renderMachineBox();
      renderSummary();
      setStep('form');
      restoreCheckout();
    }, function () {
      products = [];
      applyLang(lang);
      showError('#planError', t('form.error.loadProducts'));
    });
  }

  init();
})();
