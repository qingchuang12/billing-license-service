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

  /** 用户列表每页条数（与后端默认口径一致：20 条/页、上限 200） */
  var USER_PAGE_SIZE = 20;

  /** 与 checkout.js / account.js 共用同一语言键：整站语言偏好一致 */
  var LANG_KEY = 'yaning-lang';
  var LANG_LABEL = { zh: '中文', en: 'EN' };
  var HTML_LANG = { zh: 'zh-CN', en: 'en' };

  var state = {
    token: '',
    from: '',
    to: '',
    tab: 'overview',
    txPage: 0,
    txTotalPages: 0,
    userPage: 0,
    userTotalPages: 0,
    /* #10：订单处置分区的翻页状态（后端返回 Page，按 content + totalElements 渲染） */
    orderPage: 0,
    orderTotalPages: 0,
    /** 当前管理员自己的 userId：仅用于前端禁用「对自己的操作」（服务端 requireTarget 才是安全边界） */
    meId: '',
    /** 当前管理员是否已开启二次验证：仅用于 MFA 引导横幅显隐 */
    meMfa: false,
    /** 本次会话是否已忽略 MFA 引导横幅（SEC-4）：忽略后不再弹出，重新登录复位 */
    mfaBannerDismissed: false
  };

  /** 半认证态：密码已通过、尚待第二因子时持有的票据（不落存储，刷新即失效） */
  var pending = { mfaTicket: '', remember: false };
  /** 第二因子是否正在验证：动态码输满 6 位会自动提交，须防止重复请求 */
  var mfaVerifying = false;

  /* ======================= 0. 小工具 ======================= */

  function $(id) { return document.getElementById(id); }
  function queryAll(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /* ======================= 0.2 i18n 字典（zh / en） ======================= */

  var I18N = {
    zh: {
      'meta.title': '管理统计 · 晏宁科技 Yaning Labs',
      'meta.desc': '平台账务统计查看（内部）：收入总览、分渠道/分产品统计、交易流水、趋势与对账差异。',
      'a11y.skip': '跳到主要内容',
      'a11y.langSwitch': '切换语言：当前为中文，点击切换为英文',
      'brand.name': '晏宁科技',
      'nav.home': '返回首页',
      'admin.eyebrow': '管理后台',
      'admin.title': '平台账务统计',
      'admin.sub': '收入总览、分渠道 / 分产品统计、交易流水、时间趋势与对账差异（仅查询）。数据接口需管理员登录后凭 JWT 访问。',
      'auth.title': '管理员登录',
      'auth.desc': '使用管理员账号（邮箱 + 密码）登录；令牌仅保存在本机浏览器，不会发送到其他服务。',
      'auth.emailLabel': '管理员邮箱',
      'auth.emailPlaceholder': '管理员邮箱',
      'auth.passwordLabel': '密码',
      'auth.passwordPlaceholder': '密码',
      'auth.remember': '记住本机（关闭浏览器后仍保留）',
      'auth.login': '登录',
      'auth.forgotPrefix': '忘记密码？去',
      'auth.forgotLink': '账号页用邮箱验证码找回',
      'auth.forgotSuffix': '（管理台账号与账号页同源）。',
      'mfaLogin.codeLabel': '动态码',
      'mfaLogin.verify': '验证并登录',
      'mfaLogin.verifying': '验证中…',
      'mfaLogin.autoHint': '输满 6 位将自动验证',
      'mfaLogin.useEmail': '改用邮箱验证码',
      'mfaLogin.back': '返回重新输入密码',
      'mfaLogin.descWithFallback': '密码已通过。请输入认证器 App 显示的 6 位动态码；认证器不可用时可改用邮箱验证码。',
      'mfaLogin.descTotpOnly': '密码已通过。请输入认证器 App 显示的 6 位动态码。',
      'mfaLogin.sentTo': '验证码已发送至 {email}，请在下方输入（有效期 10 分钟）。',
      'userbar.note': '已登录 · 令牌保存在本机',
      'userbar.scopeLs': '（localStorage）',
      'userbar.scopeSession': '（sessionStorage，关闭标签页失效）',
      'userbar.logout': '退出登录',
      'mfaBanner.text': '当前管理员账号尚未开启二次验证（MFA）。管理台可处置 License 与用户账号，建议尽快绑定认证器 App 以降低凭据泄漏后的接管风险。',
      'mfaBanner.enable': '去开启',
      'mfaBanner.dismiss': '本次忽略',
      'range.from': '起始时间',
      'range.to': '结束时间',
      'range.apply': '查询',
      'range.presetsAria': '快捷时间范围',
      'range.today': '今天',
      'range.d7': '近 7 天',
      'range.d30': '近 30 天',
      'range.month': '本月',
      'tabs.aria': '统计分区',
      'tabs.groupFinance': '账务',
      'tabs.groupOps': '运营',
      'tabs.groupSystem': '系统',
      'tabs.overview': '收入总览',
      'tabs.channel': '分渠道',
      'tabs.product': '分产品',
      'tabs.transactions': '交易流水',
      'tabs.trend': '趋势',
      'tabs.discrepancies': '对账差异',
      'tabs.license': 'License 处置',
      'tabs.users': '用户管理',
      'tabs.products': '产品配置',
      'tabs.orders': '订单处置',
      'tabs.refunds': '退款审核',
      'tabs.events': '事件日志',
      'panel.refunds': '退款审核',
      'panel.events': '支付事件日志',
      'desc.events': '各渠道 Webhook 事件、处理状态与 payload 预览（仅管理员可见）。',
      'events.provider': '渠道',
      'events.processed': '处理状态',
      'events.all': '全部',
      'events.processedYes': '已处理',
      'events.processedNo': '未处理',
      'events.reload': '刷新',
      'events.empty': '暂无支付事件。',
      'events.replay': '重放',
      'events.replayConfirm': '确认重放该事件？系统会重新执行幂等校验。',
      'th.eventType': '事件类型',
      'th.eventId': '事件 ID',
      'th.processed': '已处理',
      'desc.refunds': 'Creem 全额审核后自动退款；部分审核后进入转账待确认，登记流水后结算。',
      'refunds.reload': '刷新',
      'refunds.empty': '暂无待处理退款。',
      'refunds.approve': '通过',
      'refunds.reject': '拒绝',
      'refunds.confirmTransfer': '确认转账',
      'refunds.transferChannel': '转账渠道',
      'refunds.transferRef': '转账流水号',
      'refunds.transferRequired': '请填写转账渠道与转账流水号',
      'refunds.notePrompt': '审核备注（可留空）',
      'refund.status.PENDING_REVIEW': '待审核',
      'refund.status.APPROVING': '执行中',
      'refund.status.MANUAL_REQUIRED': '待转账确认',
      'refund.status.REJECTED': '已拒绝',
      'refund.status.SETTLED': '已结算',
      'refund.status.CHANNEL_SUCCEEDED': '渠道已退/待结算',
      'refund.status.UNKNOWN': '结果未知',
      'refund.status.FAILED': '失败',
      'tabs.codes': '兑换码',
      'tabs.channels': '渠道自查',
      'tabs.security': '安全设置',
      'panel.overview': '收入总览',
      'panel.channel': '分渠道统计',
      'panel.product': '分产品统计',
      'panel.transactions': '交易流水',
      'panel.trend': '收入趋势',
      'panel.discrepancies': '对账差异',
      'panel.license': 'License 处置',
      'panel.users': '用户管理',
      'panel.products': '产品配置',
      'panel.security': '安全设置 · 二次验证（MFA）',
      'panel.orders': '订单处置',
      'panel.codes': '兑换码',
      'panel.channels': '渠道自查',
      'desc.orders1': '按订单号或状态查询订单，可对已支付订单补签许可证，或对订单发起退款。',
      'desc.ordersBold': '退款会立即作废该订单下的全部许可证',
      'desc.orders2': '；渠道退款失败时订单标记 REFUND_FAILED（钱没退成，不会谎报成功）。',
      'desc.codes1': '按套餐 SKU 与状态检索兑换码，用于批量导出与对账；也可批量生成新码。',
      'desc.codes2': '生成结果只在这一次以明文返回，离开页面后不再保留。',
      'desc.channels': '各支付渠道的启用状态与配置齐全情况，缺失项只回显配置项名，不显示任何密钥值。填好密钥后先来这里确认配得对不对，再去做真实下单验证。',
      'orders.number': '订单号',
      'orders.status': '订单状态',
      'orders.search': '查询',
      'orders.issue': '补签许可证',
      'orders.refund': '退款',
      'empty.orders': '没有符合条件的订单。',
      'empty.codes': '没有符合条件的兑换码。',
      'empty.channels': '没有可自查的渠道。',
      'th.orderNumber': '订单号',
      'th.amount': '金额',
      'th.orderStatus': '订单状态',
      'th.paymentStatus': '支付状态',
      'th.customer': '客户邮箱',
      'refund.title': '退款：',
      'refund.danger': '确认后将退款 {amount}。该订单全部许可证会立即作废，且不可撤销。',
      'refund.reason': '退款原因（写入审计留痕）',
      'refund.reasonRequired': '请填写退款原因。',
      'refund.submit': '确认退款',
      'confirm.issue': '为订单 {no} 补签许可证？\n（用于渠道回调丢失时的补偿，重复调用返回已签发记录，不会重复发证）',
      'confirm.refund': '确认对订单 {no} 发起退款？此操作不可撤销。',
      'result.issued': '已签发 {count} 张许可证（重复调用返回既有记录）。',
      'result.refunded': '退款成功，订单当前支付状态：{status}',
      'result.refundFailed': '渠道未受理退款，订单已标记 REFUND_FAILED（保留已支付），需到渠道控制台人工退款。',
      'codes.queryTitle': '检索',
      'codes.sku': '套餐 SKU',
      'codes.status': '状态',
      'codes.search': '查询',
      'codes.export': '导出 CSV',
      'codes.unused': '未使用',
      'codes.used': '已使用',
      'codes.expired': '已过期',
      'codes.revoked': '已撤销',
      'codes.generateTitle': '批量生成',
      'codes.genSku': '套餐 SKU',
      'codes.count': '生成数量',
      'codes.expiresAt': '过期时间（留空=不过期）',
      'codes.generate': '生成',
      'codes.outputLabel': '生成结果（明文）',
      'codes.revoke': '撤销',
      'codes.resultRevoked': '兑换码 {code} 已撤销。',
      'codes.resultGenerated': '已生成 {count} 个兑换码，明文见下方（只显示这一次）。',
      'confirm.revokeCode': '确认撤销兑换码 {code}？撤销后无法再用于激活。',
      'th.code': '兑换码',
      'th.codeStatus': '状态',
      'th.codeUsedAt': '使用时间',
      'th.codeExpires': '过期时间',
      'channels.check': '立即自查',
      'settings.title': '运行期开关',
      'settings.channelHint': '停用渠道只影响新建支付；已支付订单的 Webhook、退款仍会继续处理。',
      'settings.featureHint': '功能开关保存后立即生效；标记「待接入」的功能暂不可开启。',
      'settings.enabled': '启用',
      'settings.planned': '待接入',
      'settings.saveFailed': '开关保存失败',
      'settings.saved': '已保存',
      'th.enabled': '已启用',
      'th.configured': '配置齐全',
      'th.missingKeys': '缺失配置项',
      'th.note': '说明',
      'channels.ok': '配置齐全',
      'channels.missing': '缺 {count} 项',
      'channels.disabled': '未启用',
      'badge.configured': '齐全',
      'productForm.stripePriceId': 'Stripe 价目 ID',
      'productForm.paddlePriceId': 'Paddle 价目 ID',
      'productForm.creemProductId': 'Creem 产品 ID',
      'productForm.chargeMode': '收款方式',
      'productForm.chargeOnce': '一次性扣款（权益可限期）',
      'productForm.chargeRecurring': '自动续扣（订阅）',
      'err.codesSkuRequired': '请填写套餐 SKU。',
      'err.codesCount': '生成数量须为 1 以上的整数。',
      'err.refundFailed': '退款失败，请稍后重试或到渠道控制台人工处理。',
      'title.issuePaidOnly': '仅已支付订单可补签许可证',
      'title.alreadyRefunded': '该订单已退款，不可重复退款',
      'desc.products': '套餐即售卖单元：产品码、名称、描述都维护在套餐上；权益配置已下线，客户端按 SKU 解锁。保存与上下架前会弹出确认框。',
      'plans.keyword': '关键字',
      'plans.keywordPlaceholder': 'SKU / 套餐名称 / 产品码',
      'plans.productFilter': '产品',
      'plans.allProducts': '全部产品',
      'plans.statusFilter': '状态',
      'plans.allStatus': '全部状态',
      'plans.search': '查询',
      'plans.create': '新增套餐',
      'plans.kpiProducts': '产品',
      'plans.kpiProductsSub': '按产品码归组',
      'plans.kpiPlans': '套餐',
      'plans.kpiPlansSub': '含已下架',
      'plans.kpiActive': '在售',
      'plans.kpiActiveSub': '{n} 个已下架',
      'plans.kpiWarnings': '配置告警',
      'plans.kpiWarningsSub': '缺必填价目 / 无可售渠道',
      'plans.groupAdd': '+ 新增该产品套餐',
      'plans.groupCount': '{n} 个套餐',
      'plans.notGrouped': '未归类',
      'plans.noChannel': '无可售渠道',
      'plans.warnPaddle': 'Paddle 缺价目',
      'plans.warnCreem': 'Creem 缺产品 ID',
      'plans.warnNoChannel': '无可售渠道',
      'plans.autoRenew': '自动续扣',
      'plans.drawerNote': '产品码、名称、描述都在套餐上维护；权益配置已下线，无需再选。',
      'plans.sectionBasic': '基础信息',
      'plans.sectionPrice': '价格与周期',
      'plans.priceNote': '双币价均为必填；缺任一区域价格时该区域下单会被拒绝。',
      'plans.sectionLicense': '授权参数与展示',
      'plans.sectionChannel': '渠道价目',
      'plans.channelNote': '订阅周期需填价目 ID：Paddle 缺失会被直接拒单；一次性 / 永久套餐两家都可不填。',
      'plans.code': '产品码',
      'plans.codePlaceholder': '如 ai-tools',
      'plans.codeHint': '同一产品码下的套餐在列表中自动归组。',
      'plans.skuHint': '创建后不可修改，请按产品-档位-周期命名。',
      'plans.skuLocked': 'SKU 创建后不可修改。',
      'plans.createTitle': '新增套餐',
      'plans.editTitle': '编辑套餐 · {sku}',
      'plans.footCreate': '新建套餐默认上架',
      'plans.footEdit': '保存后立即生效',
      'plans.save': '保存套餐',
      'plans.paddleRequired': 'Paddle 价目 ID · 订阅必填',
      'plans.hintInvalidCharge': '永久（一次性）周期不能自动续扣：请把收款方式改回「一次性扣款」。',
      'plans.hintRecurring': '自动续扣（{cycle}）→ 可售渠道：{channels}。订阅需 Paddle 价目 ID，缺失会被直接拒单。',
      'plans.hintOnce': '一次性扣款（{cycle}）→ 可售渠道：{channels}。渠道价目 / 产品 ID 可留空。',
      'plans.hintMissing': '当前缺：{fields}。',
      'plans.errCodeRequired': '请填写产品码。',
      'plans.errCodeLength': '产品码不得超过 64 字符。',
      'plans.errChargeCycle': '自动续扣必须使用订阅类计费周期。',
      'plans.errPaddleRequired': '订阅套餐缺 Paddle 价目 ID，Paddle 渠道会被直接拒单。',
      'empty.products': '没有符合条件的套餐。',
      'desc.channel': '按支付渠道 × 币种汇总；历史订单无渠道时归入「未知渠道」。',
      'desc.product': '按产品 × 币种汇总，金额取自订单明细，按订单级支付状态归类。',
      'desc.transactions': '每条支付记录一行（一次资金变动）；管理端退款成功也会写入 REFUNDED 流水。',
      'desc.discrepancies': '订单与支付记录状态 / 金额不一致的疑点，按发现时间倒序；无输出即账实相符。',
      'desc.security': '开启后，登录除密码外还需输入认证器 App（Google Authenticator / Microsoft Authenticator / 1Password 等均可）的动态码；认证器不可用时可用邮箱验证码兜底。',
      'empty.overview': '该区间暂无订单数据。',
      'empty.channel': '该区间暂无渠道数据。',
      'empty.product': '该区间暂无产品数据。',
      'empty.transactions': '该条件下暂无流水记录。',
      'empty.trend': '该区间暂无趋势数据。',
      'empty.discrepancies': '未发现对账差异。',
      'empty.license': '尚未查询，或未查询到许可证。',
      'empty.users': '没有符合条件的用户。',
      'empty.licenses': '该用户名下暂无许可证。',
      'filter.channel': '渠道',
      'filter.status': '状态',
      'filter.currency': '币种',
      'filter.apply': '筛选',
      'opt.all': '全部',
      'opt.alipay': '支付宝',
      'opt.wechat': '微信支付',
      'opt.success': '成功',
      'opt.refunded': '已退款',
      'opt.failed': '失败',
      'opt.pending': '待处理',
      'opt.cancelled': '已取消',
      'opt.unknown': '未知',
      'trend.granularity': '分桶粒度',
      'opt.byMonth': '按月',
      'opt.byDay': '按日',
      'trend.refresh': '刷新',
      'pager.prev': '上一页',
      'pager.next': '下一页',
      'pager.info': '第 {page} / {total} 页 · 共 {count} 条',
      'overview.rangeNote': '区间：{from} ~ {to} · 订单总数 {count}',
      'kpi.received': '实收（{currency}）',
      'kpi.paidOrders': '{n} 笔已支付',
      'kpi.orders': '订单数',
      'kpi.ordersSub': '其中待支付 {n} 笔',
      'kpi.refunds': '退款',
      'kpi.refundsSub': '按币种展示，不跨币种求和',
      'toast.copied': '已复制到剪贴板',
      'toast.exported': '已导出 {file}',
      'th.currency': '币种',
      'th.orderCount': '订单数',
      'th.paidOrders': '已支付订单',
      'th.gmv': 'GMV',
      'th.received': '实收',
      'th.refunded': '已退款',
      'th.net': '净收入',
      'th.avgOrder': '客单价',
      'th.ledgerGross': '账本实收',
      'th.ledgerFee': '手续费',
      'th.ledgerNet': '账本净额',
      'th.channel': '渠道',
      'th.product': '产品',
      'th.plan': '档位',
      'th.billingCycle': '计费周期',
      'th.providers': '可售渠道',
      'products.noChannel': '无可售渠道',
      'th.time': '时间',
      'th.status': '状态',
      'th.amount': '金额',
      'th.orderId': '订单 ID',
      'th.txnId': '交易号',
      'th.paidAt': '支付时间',
      'th.bucket': '区间',
      'th.foundAt': '发现时间',
      'th.type': '类型',
      'th.description': '说明',
      'th.order': '订单',
      'th.orderAmount': '订单金额',
      'th.paidAmount': '支付金额',
      'th.orderStatus': '订单状态',
      'th.select': '选择',
      'th.licenseKey': 'License 密钥',
      'th.customerEmail': '客户邮箱',
      'th.machineCode': '机器码',
      'th.expiresAt': '到期时间',
      'th.action': '操作',
      'th.email': '邮箱',
      'th.role': '角色',
      'th.mfa': '二次验证',
      'th.createdAt': '注册时间',
      'th.lastLogin': '最近登录',
      'th.license': '许可证',
      'th.machineBound': '绑定机器码',
      'th.validUntil': '有效期至',
      'disc.paidNoPayment': '已支付无成功支付',
      'disc.amountMismatch': '金额不一致',
      'disc.refundedNoPayment': '已退款无支付记录',
      'disc.paidNotMarked': '成功支付订单未标记',
      'mfaStatus.enabled': '当前状态：<span class="badge badge--ok">已开启</span>',
      'mfaStatus.disabled': '当前状态：<span class="badge badge--muted">未开启</span>',
      'mfaStatus.enrolledAt': ' · 开启于 {time}',
      'mfaStatus.fallbackOpen': ' · 邮箱兜底：<span class="badge badge--ok">已开放</span>',
      'mfaStatus.fallbackClosed': ' · 邮箱兜底：<span class="badge badge--warn">未开放</span>',
      'mfa.blockTitle': '二次验证（MFA）',
      'mfa.enroll': '生成密钥',
      'mfa.secretNote': '用认证器 App（Google / Microsoft Authenticator、1Password 等）扫描下方二维码；或在 App 中选择「手动输入密钥」，粘贴下面这串密钥（本页离开后将不再显示）：',
      'mfa.qrAlt': '二次验证绑定二维码',
      'mfa.activateLabel': '认证器动态码',
      'mfa.activatePlaceholder': '录入密钥后 App 显示的 6 位数字',
      'mfa.activate': '激活',
      'mfa.unbindNote': '解绑会关闭二次验证这道防线，故须同时提供当前密码与一个有效验证码。若认证器与邮箱均不可用，请联系运维执行',
      'mfa.unbindNoteTail': '。',
      'mfa.unbind': '解绑二次验证',
      'lic.desc1': '按密钥精确查询，或按客户邮箱列出名下全部许可证（含失效件）。选中一行后可执行',
      'lic.descUnbind': '解绑',
      'lic.desc2': '（只清设备绑定、保留授权）、',
      'lic.descReissue': '失效重发',
      'lic.desc3': '（旧证置 REISSUED 并签发新密钥）、',
      'lic.descRevoke': '作废',
      'lic.desc4': '（终态，不可逆）——三者语义不同，请按实际情况择一。',
      'lic.searchLabel': 'License 密钥 / 客户邮箱',
      'lic.byKey': '按密钥查询',
      'lic.byEmail': '按邮箱查询',
      'lic.actionTitle': '处置',
      'lic.actionType': '处置动作',
      'lic.optUnbind': '解绑设备（保留授权，用户可在新机重新激活）',
      'lic.optReissue': '失效重发（旧证置 REISSUED，签发新密钥）',
      'lic.optRevoke': '作废（不可逆，用户权益终止）',
      'lic.newMachine': '新机器码（留空 = 不绑设备）',
      'lic.newMachinePlaceholder': '留空则由用户随后自行在新机激活',
      'lic.forceOverride': '越权重发次数上限（仅在已达上限时勾选；处置留痕会标注 forced）',
      'lic.reason': '处置原因',
      'lic.reasonPlaceholder': '如：用户换机 / 违规使用 / 重复购买',
      'lic.submit': '确认执行',
      'lic.revokeConfirmTitle': '确认作废许可证',
      'lic.revokeConfirmBody': '作废为终态、不可逆：用户权益立即终止，已绑设备下次校验将失败。请确认处置对象与原因无误。',
      'lic.revokeConfirm': '确认作废',
      'lic.revokeTarget': '对象：{key} · 原因：{reason}',
      'lic.errEmailRequired': '请输入客户邮箱。',
      'lic.errKeyRequired': '请输入 License 密钥。',
      'licStatus.active': '有效',
      'licStatus.expired': '已过期',
      'licStatus.revoked': '已作废',
      'licStatus.reissued': '已重发',
      'licStatus.unknown': '未知',
      'lic.action': '处置',
      'lic.errRevokedTerminal': '该许可证已作废（终态），无法再处置。',
      'lic.errRevokeNeedsReason': '作废不可逆，请填写处置原因。',
      'lic.noteUnbind': '已解绑设备绑定——授权仍有效，用户可在新机重新激活。',
      'lic.noteRevoke': '已作废该许可证（不可逆）。',
      'lic.noteReissue': '已失效原证并签发新密钥，新密钥同时会出现在该用户的账号页：<code>{key}</code>',
      'lic.newKeyMissing': '(响应未返回，请重新查询)',
      'lic.errActionFailed': '处置失败，请重试。',
      'users.desc1': '按邮箱 / 角色 / 状态查询账号，可就地改角色、停用 / 启用、代重置密码。',
      'users.descBold': '不能对自己执行这些动作',
      'users.desc2': '（列表里自身的行会禁用）；服务端也会二次校验，前端禁用只是体验层。',
      'users.desc3': '代重置后用户必须下次登录自助改密，旧会话立即失效。',
      'users.selfNote': '当前登录账号',
      'users.selfActionNote': '不能对自己执行这些动作',
      'users.emailLabel': '邮箱（包含匹配）',
      'users.role': '角色',
      'users.status': '状态',
      'users.search': '查询',
      'users.export': '导出 CSV',
      'users.newPwd': '新密码',
      'users.confirmPwd': '确认新密码',
      'users.disable': '停用',
      'users.enable': '启用',
      'users.resetPwd': '重置密码',
      'users.detail': '详情',
      'products.keyword': '关键字',
      'products.keywordPlaceholder': 'SKU / 套餐名 / 产品码',
      'products.statusFilter': '状态',
      'products.onSale': '已上架',
      'products.offSale': '已下架',
      'products.search': '查询',
      'products.createPlan': '新增套餐',
      'products.edit': '编辑',
      'products.putOn': '上架',
      'products.takeOff': '下架',
      'products.recommended': '推荐',
      'products.priceCell': '¥{cny} / ${usd}',
      'products.daysCell': '授权 {license} 天 · 更新 {update} · 大版本 {major}',
      'products.updateUnlimited': '不限',
      'products.resultCreated': '已创建套餐 {sku}。',
      'products.resultUpdated': '已保存套餐 {sku}。',
      'products.resultOn': '已上架 {sku}。',
      'products.resultOff': '已下架 {sku}（仅影响新订单）。',
      'productForm.sku': 'SKU',
      'productForm.skuPlaceholder': '如 pro-half-year',
      'productForm.name': '套餐名称（中文）',
      'productForm.nameEn': '套餐名称（英文）',
      'productForm.priceCny': '人民币价格',
      'productForm.priceUsd': '美元价格',
      'productForm.licenseDays': '授权有效天数',
      'productForm.updateDays': '更新权益天数（留空=不限制）',
      'productForm.maxMajor': '大版本上限（留空=不限制）',
      'productForm.description': '套餐描述（中文）',
      'productForm.descriptionEn': '套餐描述（英文）',
      'productForm.recommended': '在收银台展示「推荐」标记',
      'err.productSkuRequired': '请输入 SKU。',
      'err.productSkuPattern': 'SKU 须为 2–64 位字母/数字/短横线。',
      'err.productNameRequired': '请输入套餐名称。',
      'err.productPrice': '双币价均为必填且须大于 0，最多 2 位小数。',
      'err.productDays': '授权有效天数须为正整数；更新天数与大版本上限留空或填正整数。',
      'err.productSaveFailed': '保存失败，请重试。',
      'confirm.takeOffProduct': '确认下架 {sku}？下架后收银台不再展示，但不影响历史订单与已签发许可证。',
      'confirm.putOnProduct': '确认上架 {sku}？上架后收银台立即可售。',
      'confirm.saveProduct': '确认保存套餐 {sku}（¥{cny} / ${usd}）？',
      'th.sku': 'SKU',
      'th.name': '名称',
      'th.price': '双币价',
      'th.licenseUpdate': '授权 / 更新 / 版本',
      'cycle.ONE_TIME': '一次性',
      'cycle.MONTHLY': '按月',
      'cycle.HALF_YEAR': '半年',
      'cycle.QUARTERLY': '按季',
      'cycle.YEARLY': '按年',
      'cycle.LIFETIME': '永久',
      'tier.PRO': '基础版（PRO）',
      'tier.PRO_PLUS': '高级版（PRO PLUS）',
      'role.user': '普通用户',
      'role.admin': '管理员',
      'status.active': '正常',
      'status.disabled': '已停用',
      'badge.on': '已开启',
      'badge.off': '未开启',
      'badge.self': '本人',
      'title.cantChangeRole': '不能变更自己的角色',
      'title.cantToggleSelf': '不能停用或启用自己',
      'title.cantResetSelf': '不能重置自己的密码',
      'resetPwd.title': '重置密码：',
      'resetPwd.note': '为该账号设置新密码并告知用户本人；重置后旧会话立即失效，且该用户下次登录必须先修改密码。新密码须 8–72 位且同时包含字母与数字。',
      'resetPwd.submit': '确认重置',
      'pwd.currentLabel': '当前密码',
      'pwd.currentPlaceholder': '当前密码',
      'pwd.hint': '8–72 位，含字母与数字',
      'pwd.confirmPlaceholder': '再次输入新密码',
      'pwd.sixDigits': '6 位数字',
      'confirm.changeRole': '确认将该用户的角色改为「{role}」？变更后其已登录会话立即失效。',
      'confirm.disable': '确认停用该用户？停用后其已登录会话立即失效，且无法再登录。',
      'confirm.enable': '确认启用该用户？',
      'result.roleChanged': '角色已改为「{role}」，该用户已登录的会话已失效。',
      'result.disabled': '已停用该用户，其已登录会话已失效。',
      'result.enabled': '已启用该用户。',
      'result.resetDone': '已重置 {email} 的密码。其旧会话已失效，下次登录须先修改密码；请通过可靠渠道把新密码告知本人（本页不再显示）。',
      'result.selfPwdChanged': '密码已修改，请用新密码重新登录。',
      'detail.title': '用户详情',
      'detail.titleWithEmail': '用户详情：{email}',
      'detail.email': '邮箱',
      'detail.role': '角色',
      'detail.status': '状态',
      'detail.mfa': '二次验证',
      'detail.createdAt': '注册时间',
      'detail.lastLogin': '最近登录',
      'detail.licenses': '名下许可证',
      'detail.orders': '名下订单',
      'unit.licenses': '张',
      'unit.orders': '笔',
      'detail.viewLicenses': '查看许可证',
      'detail.licensesNote': '以下为该用户邮箱名下的许可证（完整密钥，仅供售后处置；不含签名令牌）：',
      'selfPwd.title': '修改本账号密码',
      'selfPwd.note': '改密成功后当前登录会话立即失效，需用新密码重新登录；系统会向你的邮箱发送不含密码的安全提醒。新密码须 8–72 位且同时包含字母与数字，不得与旧密码相同。',
      'selfPwd.submit': '确认修改',
      'footer.note': '内部页面，请勿外传链接。已开启二次验证的账号登录时需输入动态码。',
      'common.cancel': '取消',
      'common.confirm': '确认',
      'common.close': '关闭',
      'common.none': '—',
      'err.loginFailed': '登录失败，请检查邮箱和密码。',
      'err.mfaLoginTicketLost': '登录会话已失效，请重新输入密码。',
      'err.verifyFailed': '验证失败，请重试。',
      'err.sendCodeFailed': '验证码发送失败，请稍后重试。',
      'err.noToken': '登录失败：服务端未返回令牌。',
      'err.notAdmin': '该账号不是管理员，无法进入管理台。',
      'err.pwdResetByOther': '你的密码已被其他管理员重置：请先到账号页（/account/）登录后修改密码，再回到管理台操作。',
      'err.sessionExpired': '登录已失效或无管理员权限，请重新登录。',
      'err.badCredentials': '邮箱或密码错误，或该账号不是管理员。',
      'err.rangeRequired': '请填写开始与结束时间。',
      'err.rangeOrder': '开始时间不能晚于结束时间。',
      'err.network': '网络错误：无法连接服务，请稍后重试。',
      'err.queryFailed': '查询失败：{reason}',
      'err.mfaPasswordRequired': '请输入当前密码。',
      'err.mfaEnrollFailed': '生成失败，请重试。',
      'err.mfaSixDigits': '请输入 6 位动态码。',
      'err.mfaActivateFailed': '激活失败，请重试。',
      'err.mfaUnbindCodeRequired': '请输入认证器动态码或邮箱验证码。',
      'err.mfaUnbindFailed': '解绑失败，请重试。',
      'err.exportFailed': '导出失败，请稍后重试。',
      'err.pwdLength': '新密码长度须为 8–72 位。',
      'err.pwdAlnum': '新密码须同时包含字母与数字。',
      'err.pwdMismatch': '两次输入的新密码不一致。',
      'err.selfPwdRequired': '请填写当前密码与新密码。',
      'err.selfPwdFailed': '修改失败，请稍后重试。',
      'err.enterEmailPassword': '请输入管理员邮箱与密码。',
      'err.detailBad': '详情数据异常。',
      'err.resetFailed': '重置失败，请稍后重试。'
    },
    en: {
      'meta.title': 'Admin Console · Yaning Labs',
      'meta.desc': 'Internal platform accounting: revenue overview, per-channel / per-product stats, transactions, trend and reconciliation.',
      'a11y.skip': 'Skip to main content',
      'a11y.langSwitch': 'Switch language: currently English, click to switch to Chinese',
      'brand.name': 'Yaning Labs',
      'nav.home': 'Home',
      'admin.eyebrow': 'Admin',
      'admin.title': 'Platform Accounting',
      'admin.sub': 'Revenue overview, per-channel / per-product stats, transactions, trend and reconciliation (read-only). Data APIs require an admin JWT.',
      'auth.title': 'Admin Sign-in',
      'auth.desc': 'Sign in with an admin account (email + password); the token is kept in this browser only and never sent elsewhere.',
      'auth.emailLabel': 'Admin email',
      'auth.emailPlaceholder': 'admin@example.com',
      'auth.passwordLabel': 'Password',
      'auth.passwordPlaceholder': 'Password',
      'auth.remember': 'Remember this device (persist after closing the browser)',
      'auth.login': 'Sign in',
      'auth.forgotPrefix': 'Forgot password? Use the',
      'auth.forgotLink': 'account page (email code reset)',
      'auth.forgotSuffix': '— admin accounts are shared with the account page.',
      'mfaLogin.codeLabel': 'Code',
      'mfaLogin.verify': 'Verify & sign in',
      'mfaLogin.verifying': 'Verifying…',
      'mfaLogin.autoHint': 'Verification starts automatically at 6 digits',
      'mfaLogin.useEmail': 'Use an emailed code',
      'mfaLogin.back': 'Back to password',
      'mfaLogin.descWithFallback': 'Password accepted. Enter the 6-digit code from your authenticator app; if unavailable, switch to an emailed code.',
      'mfaLogin.descTotpOnly': 'Password accepted. Enter the 6-digit code from your authenticator app.',
      'mfaLogin.sentTo': 'Code sent to {email}. Enter it below (valid for 10 minutes).',
      'userbar.note': 'Signed in · token kept on this device',
      'userbar.scopeLs': ' (localStorage)',
      'userbar.scopeSession': ' (sessionStorage, cleared when the tab closes)',
      'userbar.logout': 'Sign out',
      'mfaBanner.text': 'This admin account has not enabled two-factor authentication (MFA). The console can manage licenses and user accounts — bind an authenticator app soon to reduce takeover risk if credentials leak.',
      'mfaBanner.enable': 'Enable now',
      'mfaBanner.dismiss': 'Dismiss',
      'range.from': 'From',
      'range.to': 'To',
      'range.apply': 'Apply',
      'range.presetsAria': 'Quick time ranges',
      'range.today': 'Today',
      'range.d7': 'Last 7 days',
      'range.d30': 'Last 30 days',
      'range.month': 'This month',
      'tabs.aria': 'Sections',
      'tabs.groupFinance': 'Finance',
      'tabs.groupOps': 'Operations',
      'tabs.groupSystem': 'System',
      'tabs.overview': 'Overview',
      'tabs.channel': 'Channels',
      'tabs.product': 'Products',
      'tabs.transactions': 'Transactions',
      'tabs.trend': 'Trend',
      'tabs.discrepancies': 'Reconciliation',
      'tabs.license': 'License Actions',
      'tabs.users': 'Users',
      'tabs.products': 'Products',
      'tabs.orders': 'Orders',
      'tabs.refunds': 'Refund review',
      'tabs.events': 'Event log',
      'panel.refunds': 'Refund review',
      'panel.events': 'Payment event log',
      'desc.events': 'Webhook events, processing state and payload preview (admin only).',
      'events.provider': 'Provider',
      'events.processed': 'Processed',
      'events.all': 'All',
      'events.processedYes': 'Processed',
      'events.processedNo': 'Unprocessed',
      'events.reload': 'Reload',
      'events.empty': 'No payment events.',
      'events.replay': 'Replay',
      'events.replayConfirm': 'Replay this event? Idempotency checks will run again.',
      'th.eventType': 'Event type',
      'th.eventId': 'Event ID',
      'th.processed': 'Processed',
      'desc.refunds': 'Creem full refunds auto-execute after approval; partial refunds wait for transfer confirmation.',
      'refunds.reload': 'Reload',
      'refunds.empty': 'No pending refunds.',
      'refunds.approve': 'Approve',
      'refunds.reject': 'Reject',
      'refunds.confirmTransfer': 'Confirm transfer',
      'refunds.transferChannel': 'Transfer channel',
      'refunds.transferRef': 'Transfer reference',
      'refunds.transferRequired': 'Transfer channel and reference are required',
      'refunds.notePrompt': 'Review note (optional)',
      'refund.status.PENDING_REVIEW': 'Pending review',
      'refund.status.APPROVING': 'Approving',
      'refund.status.MANUAL_REQUIRED': 'Awaiting transfer',
      'refund.status.REJECTED': 'Rejected',
      'refund.status.SETTLED': 'Settled',
      'refund.status.CHANNEL_SUCCEEDED': 'Channel refunded / settling',
      'refund.status.UNKNOWN': 'Unknown',
      'refund.status.FAILED': 'Failed',
      'tabs.codes': 'Redeem codes',
      'tabs.channels': 'Channel check',
      'tabs.security': 'Security',
      'panel.overview': 'Revenue Overview',
      'panel.channel': 'Per-channel Stats',
      'panel.product': 'Per-product Stats',
      'panel.transactions': 'Transactions',
      'panel.trend': 'Revenue Trend',
      'panel.discrepancies': 'Reconciliation',
      'panel.license': 'License Actions',
      'panel.users': 'User Management',
      'panel.products': 'Product Configuration',
      'panel.security': 'Security · Two-Factor (MFA)',
      'panel.orders': 'Order operations',
      'panel.codes': 'Redeem codes',
      'panel.channels': 'Payment channel check',
      'desc.orders1': 'Look up orders by number or status; re-issue licenses for a paid order, or refund it.',
      'desc.ordersBold': 'A refund immediately revokes every license under that order',
      'desc.orders2': '; if the channel rejects the refund, the order is flagged REFUND_FAILED (money not returned — never reported as success).',
      'desc.codes1': 'Search codes by plan SKU and status for export and reconciliation; new codes can be generated in bulk.',
      'desc.codes2': 'Plain codes are returned only this once and are not kept after leaving the page.',
      'desc.channels': 'Enabled state and config completeness per channel. Missing entries show config key names only, never secret values. Check this right after filling in keys, before running a real order.',
      'orders.number': 'Order number',
      'orders.status': 'Order status',
      'orders.search': 'Search',
      'orders.issue': 'Issue licenses',
      'orders.refund': 'Refund',
      'empty.orders': 'No orders match these filters.',
      'empty.codes': 'No redeem codes match these filters.',
      'empty.channels': 'No channels to check.',
      'th.orderNumber': 'Order',
      'th.amount': 'Amount',
      'th.orderStatus': 'Order status',
      'th.paymentStatus': 'Payment status',
      'th.customer': 'Customer',
      'refund.title': 'Refund: ',
      'refund.danger': 'This refunds {amount}. All licenses under this order are revoked immediately and the action cannot be undone.',
      'refund.reason': 'Reason (recorded in the audit trail)',
      'refund.reasonRequired': 'Please enter a refund reason.',
      'refund.submit': 'Confirm refund',
      'confirm.issue': 'Issue licenses for order {no}?\n(Compensation for a lost channel callback; repeat calls return existing records and never issue duplicates.)',
      'confirm.refund': 'Refund order {no}? This cannot be undone.',
      'result.issued': 'Issued {count} license(s); repeat calls return existing records.',
      'result.refunded': 'Refunded. Current payment status: {status}',
      'result.refundFailed': 'Channel rejected the refund; order flagged REFUND_FAILED (stays paid). Refund manually in the channel console.',
      'codes.queryTitle': 'Search',
      'codes.sku': 'Plan SKU',
      'codes.status': 'Status',
      'codes.search': 'Search',
      'codes.export': 'Export CSV',
      'codes.unused': 'Unused',
      'codes.used': 'Used',
      'codes.expired': 'Expired',
      'codes.revoked': 'Revoked',
      'codes.generateTitle': 'Generate in bulk',
      'codes.genSku': 'Plan SKU',
      'codes.count': 'Quantity',
      'codes.expiresAt': 'Expires at (blank = never)',
      'codes.generate': 'Generate',
      'codes.outputLabel': 'Result (plain text)',
      'codes.revoke': 'Revoke',
      'codes.resultRevoked': 'Code {code} revoked.',
      'codes.resultGenerated': 'Generated {count} code(s); plain text below (shown only once).',
      'confirm.revokeCode': 'Revoke code {code}? It can no longer be used to activate.',
      'th.code': 'Code',
      'th.codeStatus': 'Status',
      'th.codeUsedAt': 'Used at',
      'th.codeExpires': 'Expires at',
      'channels.check': 'Run check',
      'settings.title': 'Runtime toggles',
      'settings.channelHint': 'Disabling a channel only blocks new payments; existing webhooks/refunds continue.',
      'settings.featureHint': 'Feature toggles apply immediately; features marked Planned cannot be enabled yet.',
      'settings.enabled': 'Enabled',
      'settings.planned': 'Planned',
      'settings.saveFailed': 'Failed to save toggle',
      'settings.saved': 'Saved',
      'th.enabled': 'Enabled',
      'th.configured': 'Configured',
      'th.missingKeys': 'Missing keys',
      'th.note': 'Note',
      'channels.ok': 'Complete',
      'channels.missing': '{count} missing',
      'channels.disabled': 'Disabled',
      'badge.configured': 'OK',
      'productForm.stripePriceId': 'Stripe price ID',
      'productForm.paddlePriceId': 'Paddle price ID',
      'productForm.creemProductId': 'Creem product ID',
      'productForm.chargeMode': 'Charge mode',
      'productForm.chargeOnce': 'One-time charge (term may be limited)',
      'productForm.chargeRecurring': 'Auto-recurring subscription',
      'err.codesSkuRequired': 'Plan SKU is required.',
      'err.codesCount': 'Quantity must be a positive integer.',
      'err.refundFailed': 'Refund failed. Retry later or refund manually in the channel console.',
      'title.issuePaidOnly': 'Only paid orders can have licenses issued',
      'title.alreadyRefunded': 'This order is already refunded',
      'desc.products': 'Plans are the selling unit: product code, name and description all live on the plan; feature configuration is retired and clients unlock by SKU. Saves and availability changes ask for confirmation.',
      'plans.keyword': 'Keyword',
      'plans.keywordPlaceholder': 'SKU / plan name / product code',
      'plans.productFilter': 'Product',
      'plans.allProducts': 'All products',
      'plans.statusFilter': 'Status',
      'plans.allStatus': 'All statuses',
      'plans.search': 'Search',
      'plans.create': 'New plan',
      'plans.kpiProducts': 'Products',
      'plans.kpiProductsSub': 'Grouped by product code',
      'plans.kpiPlans': 'Plans',
      'plans.kpiPlansSub': 'Includes archived',
      'plans.kpiActive': 'On sale',
      'plans.kpiActiveSub': '{n} off sale',
      'plans.kpiWarnings': 'Config warnings',
      'plans.kpiWarningsSub': 'Missing price ID / no sellable channel',
      'plans.groupAdd': '+ Add plan for this product',
      'plans.groupCount': '{n} plans',
      'plans.notGrouped': 'Ungrouped',
      'plans.noChannel': 'No sellable channel',
      'plans.warnPaddle': 'Paddle price ID missing',
      'plans.warnCreem': 'Creem product ID missing',
      'plans.warnNoChannel': 'No sellable channel',
      'plans.autoRenew': 'auto-renews',
      'plans.drawerNote': 'Product code, name and description all live on the plan; feature configuration is retired.',
      'plans.sectionBasic': 'Basic info',
      'plans.sectionPrice': 'Price & cycle',
      'plans.priceNote': 'Both prices are required; a missing regional price blocks orders from that region.',
      'plans.sectionLicense': 'License & display',
      'plans.sectionChannel': 'Channel prices',
      'plans.channelNote': 'Subscription cycles require price IDs: Paddle rejects checkout when missing; one-time / lifetime plans need neither.',
      'plans.code': 'Product code',
      'plans.codePlaceholder': 'e.g. ai-tools',
      'plans.codeHint': 'Plans with the same product code are grouped in the list.',
      'plans.skuHint': 'Immutable after creation; name it product-tier-cycle.',
      'plans.skuLocked': 'SKU is immutable after creation.',
      'plans.createTitle': 'New plan',
      'plans.editTitle': 'Edit plan · {sku}',
      'plans.footCreate': 'New plans go live by default',
      'plans.footEdit': 'Takes effect immediately after save',
      'plans.save': 'Save plan',
      'plans.paddleRequired': 'Paddle price ID · required for subscriptions',
      'plans.hintInvalidCharge': 'A one-time/lifetime cycle cannot auto-renew; switch charge mode back to one-time.',
      'plans.hintRecurring': 'Auto-renew ({cycle}) → sellable channels: {channels}. Subscriptions require a Paddle price ID or checkout is rejected.',
      'plans.hintOnce': 'One-time charge ({cycle}) → sellable channels: {channels}. Price / product IDs are optional.',
      'plans.hintMissing': 'Missing: {fields}.',
      'plans.errCodeRequired': 'Please enter a product code.',
      'plans.errCodeLength': 'Product code must be at most 64 characters.',
      'plans.errChargeCycle': 'Auto-renew requires a recurring billing cycle.',
      'plans.errPaddleRequired': 'Subscription plan is missing the Paddle price ID; Paddle checkout will be rejected.',
      'empty.products': 'No plans match these filters.',
      'desc.channel': 'Aggregated by payment channel × currency; orders without a channel fall into "Unknown".',
      'desc.product': 'Aggregated by product × currency from order items, grouped by order-level payment status.',
      'desc.transactions': 'One row per payment record (one fund movement); admin refunds also write REFUNDED entries.',
      'desc.discrepancies': 'Suspected mismatches between orders and payments (status / amount), newest first; no output means books match.',
      'desc.security': 'When enabled, sign-in requires a 6-digit code from an authenticator app (Google Authenticator / Microsoft Authenticator / 1Password etc.); an emailed code is the fallback.',
      'empty.overview': 'No orders in this range.',
      'empty.channel': 'No channel data in this range.',
      'empty.product': 'No product data in this range.',
      'empty.transactions': 'No transactions match these filters.',
      'empty.trend': 'No trend data in this range.',
      'empty.discrepancies': 'No reconciliation discrepancies found.',
      'empty.license': 'Nothing queried yet, or no licenses found.',
      'empty.users': 'No users match these filters.',
      'empty.licenses': 'This user has no licenses.',
      'filter.channel': 'Channel',
      'filter.status': 'Status',
      'filter.currency': 'Currency',
      'filter.apply': 'Filter',
      'opt.all': 'All',
      'opt.alipay': 'Alipay',
      'opt.wechat': 'WeChat Pay',
      'opt.success': 'Success',
      'opt.refunded': 'Refunded',
      'opt.failed': 'Failed',
      'opt.pending': 'Pending',
      'opt.cancelled': 'Cancelled',
      'opt.unknown': 'Unknown',
      'trend.granularity': 'Bucket size',
      'opt.byMonth': 'Monthly',
      'opt.byDay': 'Daily',
      'trend.refresh': 'Refresh',
      'pager.prev': 'Prev',
      'pager.next': 'Next',
      'pager.info': 'Page {page} / {total} · {count} items',
      'overview.rangeNote': 'Range: {from} ~ {to} · {count} orders',
      'kpi.received': 'Received ({currency})',
      'kpi.paidOrders': '{n} paid',
      'kpi.orders': 'Orders',
      'kpi.ordersSub': '{n} awaiting payment',
      'kpi.refunds': 'Refunds',
      'kpi.refundsSub': 'Shown per currency; never summed across currencies',
      'toast.copied': 'Copied to clipboard',
      'toast.exported': 'Exported {file}',
      'th.currency': 'Currency',
      'th.orderCount': 'Orders',
      'th.paidOrders': 'Paid Orders',
      'th.gmv': 'GMV',
      'th.received': 'Received',
      'th.refunded': 'Refunded',
      'th.net': 'Net',
      'th.avgOrder': 'Avg Order',
      'th.channel': 'Channel',
      'th.product': 'Product',
      'th.plan': 'Plan',
      'th.billingCycle': 'Billing Cycle',
      'th.providers': 'Channels',
      'products.noChannel': 'None',
      'th.time': 'Time',
      'th.status': 'Status',
      'th.amount': 'Amount',
      'th.orderId': 'Order ID',
      'th.txnId': 'Txn ID',
      'th.paidAt': 'Paid At',
      'th.bucket': 'Bucket',
      'th.foundAt': 'Found At',
      'th.type': 'Type',
      'th.description': 'Description',
      'th.order': 'Order',
      'th.orderAmount': 'Order Amount',
      'th.paidAmount': 'Paid Amount',
      'th.orderStatus': 'Order Status',
      'th.select': 'Select',
      'th.licenseKey': 'License Key',
      'th.customerEmail': 'Customer Email',
      'th.machineCode': 'Machine Code',
      'th.expiresAt': 'Expires',
      'th.action': 'Actions',
      'th.email': 'Email',
      'th.role': 'Role',
      'th.mfa': '2FA',
      'th.createdAt': 'Created',
      'th.lastLogin': 'Last Login',
      'th.license': 'License',
      'th.machineBound': 'Bound Machine',
      'th.validUntil': 'Valid Until',
      'disc.paidNoPayment': 'Paid without successful payment',
      'disc.amountMismatch': 'Amount mismatch',
      'disc.refundedNoPayment': 'Refunded without payment',
      'disc.paidNotMarked': 'Paid order not marked',
      'mfaStatus.enabled': 'Status: <span class="badge badge--ok">Enabled</span>',
      'mfaStatus.disabled': 'Status: <span class="badge badge--muted">Disabled</span>',
      'mfaStatus.enrolledAt': ' · enabled at {time}',
      'mfaStatus.fallbackOpen': ' · email fallback: <span class="badge badge--ok">Open</span>',
      'mfaStatus.fallbackClosed': ' · email fallback: <span class="badge badge--warn">Closed</span>',
      'mfa.blockTitle': 'Two-Factor (MFA)',
      'mfa.enroll': 'Generate Secret',
      'mfa.secretNote': 'Scan the QR code below with an authenticator app (Google / Microsoft Authenticator, 1Password, etc.), or choose "Enter setup key" and paste the secret (it will not be shown again after you leave this page):',
      'mfa.qrAlt': 'Two-factor enrollment QR code',
      'mfa.activateLabel': 'Authenticator code',
      'mfa.activatePlaceholder': '6-digit code shown in the app after entering the key',
      'mfa.activate': 'Activate',
      'mfa.unbindNote': 'Unbinding turns off this line of defense, so it requires the current password and a valid code. If both the authenticator and email are unavailable, ask ops to run',
      'mfa.unbindNoteTail': '.',
      'mfa.unbind': 'Unbind Two-Factor',
      'lic.desc1': 'Look up by key, or list all licenses of a customer email (including inactive ones). Select a row to',
      'lic.descUnbind': 'Unbind',
      'lic.desc2': ' (clear device binding only, license kept), ',
      'lic.descReissue': 'Reissue',
      'lic.desc3': ' (old key marked REISSUED, a new key is issued), or ',
      'lic.descRevoke': 'Revoke',
      'lic.desc4': ' (terminal, irreversible) — they mean different things; choose per the situation.',
      'lic.searchLabel': 'License key / customer email',
      'lic.byKey': 'Search by key',
      'lic.byEmail': 'Search by email',
      'lic.actionTitle': 'Action for',
      'lic.actionType': 'Action',
      'lic.optUnbind': 'Unbind device (license kept; user can re-activate on a new machine)',
      'lic.optReissue': 'Reissue (old key marked REISSUED, new key issued)',
      'lic.optRevoke': 'Revoke (irreversible; access ends)',
      'lic.newMachine': 'New machine code (empty = bind nothing)',
      'lic.newMachinePlaceholder': 'Leave empty; the user can activate on a new machine later',
      'lic.forceOverride': 'Override reissue limit (check only when the cap is reached; audit will note forced)',
      'lic.reason': 'Reason',
      'lic.reasonPlaceholder': 'e.g. machine change / abuse / duplicate purchase',
      'lic.submit': 'Confirm',
      'lic.revokeConfirmTitle': 'Revoke license',
      'lic.revokeConfirmBody': 'Revocation is final and irreversible: the user loses access immediately and bound devices fail the next check. Verify the target and reason.',
      'lic.revokeConfirm': 'Revoke',
      'lic.revokeTarget': 'Target: {key} · Reason: {reason}',
      'lic.errEmailRequired': 'Please enter the customer email.',
      'lic.errKeyRequired': 'Please enter the license key.',
      'licStatus.active': 'Active',
      'licStatus.expired': 'Expired',
      'licStatus.revoked': 'Revoked',
      'licStatus.reissued': 'Reissued',
      'licStatus.unknown': 'Unknown',
      'lic.action': 'Action',
      'lic.errRevokedTerminal': 'This license is revoked (terminal) and cannot be actioned.',
      'lic.errRevokeNeedsReason': 'Revocation is irreversible; please fill in the reason.',
      'lic.noteUnbind': 'Device binding released — the license is still valid and can be activated on a new machine.',
      'lic.noteRevoke': 'License revoked (irreversible).',
      'lic.noteReissue': 'Old key invalidated and a new key issued; it also appears on the user\'s account page: <code>{key}</code>',
      'lic.newKeyMissing': '(not returned, please re-query)',
      'lic.errActionFailed': 'Action failed, please retry.',
      'users.desc1': 'Query accounts by email / role / status; change role, enable / disable, reset password in place.',
      'users.descBold': 'You cannot run these actions on yourself',
      'users.desc2': ' (your own row is disabled); the server double-checks regardless — the front-end disable is UX only.',
      'users.desc3': 'After a reset, the user must set a new password at next sign-in; old sessions die immediately.',
      'users.selfNote': 'Current account',
      'users.selfActionNote': 'You cannot perform these actions on yourself',
      'users.emailLabel': 'Email (contains)',
      'users.role': 'Role',
      'users.status': 'Status',
      'users.search': 'Search',
      'users.export': 'Export CSV',
      'users.newPwd': 'New password',
      'users.confirmPwd': 'Confirm new password',
      'users.disable': 'Disable',
      'users.enable': 'Enable',
      'users.resetPwd': 'Reset password',
      'users.detail': 'Details',
      'products.keyword': 'Keyword',
      'products.keywordPlaceholder': 'SKU / plan name / product code',
      'products.statusFilter': 'Status',
      'products.onSale': 'Active',
      'products.offSale': 'Inactive',
      'products.search': 'Search',
      'products.createPlan': 'New plan',
      'products.edit': 'Edit',
      'products.putOn': 'Activate',
      'products.takeOff': 'Deactivate',
      'products.recommended': 'Recommended',
      'products.priceCell': '¥{cny} / ${usd}',
      'products.daysCell': 'License {license} days · Updates {update} · Major {major}',
      'products.updateUnlimited': 'Unlimited',
      'products.resultCreated': 'Plan {sku} created.',
      'products.resultUpdated': 'Plan {sku} saved.',
      'products.resultOn': 'Plan {sku} activated.',
      'products.resultOff': 'Plan {sku} deactivated (new orders only).',
      'productForm.sku': 'SKU',
      'productForm.skuPlaceholder': 'e.g. pro-half-year',
      'productForm.name': 'Plan name (Chinese)',
      'productForm.nameEn': 'Plan name (English)',
      'productForm.priceCny': 'Price (CNY)',
      'productForm.priceUsd': 'Price (USD)',
      'productForm.licenseDays': 'License duration (days)',
      'productForm.updateDays': 'Update entitlement days (blank = unlimited)',
      'productForm.maxMajor': 'Max major version (blank = unlimited)',
      'productForm.description': 'Plan description (Chinese)',
      'productForm.descriptionEn': 'Plan description (English)',
      'productForm.recommended': 'Show "Recommended" badge on checkout',
      'err.productSkuRequired': 'Please enter a SKU.',
      'err.productSkuPattern': 'SKU must be 2–64 characters of letters / digits / hyphens.',
      'err.productNameRequired': 'Please enter the plan name.',
      'err.productPrice': 'Both prices are required and must be greater than 0 with at most 2 decimals.',
      'err.productDays': 'License duration must be a positive integer; update days and max major must be blank or positive integers.',
      'err.productSaveFailed': 'Save failed, please retry.',
      'confirm.takeOffProduct': 'Deactivate {sku}? It will disappear from checkout; historical orders and issued licenses are unaffected.',
      'confirm.putOnProduct': 'Activate {sku}? It will be purchasable on checkout immediately.',
      'confirm.saveProduct': 'Save plan {sku} (¥{cny} / ${usd})?',
      'th.sku': 'SKU',
      'th.name': 'Name',
      'th.price': 'Prices',
      'th.licenseUpdate': 'License / Updates / Major',
      'cycle.ONE_TIME': 'One-time',
      'cycle.MONTHLY': 'Monthly',
      'cycle.HALF_YEAR': 'Half-year',
      'cycle.QUARTERLY': 'Quarterly',
      'cycle.YEARLY': 'Yearly',
      'cycle.LIFETIME': 'Lifetime',
      'tier.PRO': 'Pro',
      'tier.PRO_PLUS': 'Pro Plus',
      'role.user': 'User',
      'role.admin': 'Admin',
      'status.active': 'Active',
      'status.disabled': 'Disabled',
      'badge.on': 'On',
      'badge.off': 'Off',
      'badge.self': 'You',
      'title.cantChangeRole': 'You cannot change your own role',
      'title.cantToggleSelf': 'You cannot disable or enable yourself',
      'title.cantResetSelf': 'You cannot reset your own password',
      'resetPwd.title': 'Reset password: ',
      'resetPwd.note': 'Set a new password for this account and tell the user; old sessions die immediately and the user must set a new password at next sign-in. 8–72 characters with both letters and digits.',
      'resetPwd.submit': 'Reset',
      'pwd.currentLabel': 'Current password',
      'pwd.currentPlaceholder': 'Current password',
      'pwd.hint': '8–72 chars, letters and digits',
      'pwd.confirmPlaceholder': 'Repeat the new password',
      'pwd.sixDigits': '6 digits',
      'confirm.changeRole': 'Change this user\'s role to "{role}"? Their signed-in sessions die immediately.',
      'confirm.disable': 'Disable this user? Their signed-in sessions die immediately and they cannot sign in.',
      'confirm.enable': 'Enable this user?',
      'result.roleChanged': 'Role changed to "{role}"; the user\'s signed-in sessions have been invalidated.',
      'result.disabled': 'User disabled; their signed-in sessions have been invalidated.',
      'result.enabled': 'User enabled.',
      'result.resetDone': 'Password reset for {email}. Old sessions are dead; the user must set a new password at next sign-in. Deliver the new password via a trusted channel (it is not shown here again).',
      'result.selfPwdChanged': 'Password changed. Please sign in with the new password.',
      'detail.title': 'User Details',
      'detail.titleWithEmail': 'User details: {email}',
      'detail.email': 'Email',
      'detail.role': 'Role',
      'detail.status': 'Status',
      'detail.mfa': '2FA',
      'detail.createdAt': 'Created',
      'detail.lastLogin': 'Last Login',
      'detail.licenses': 'Licenses',
      'detail.orders': 'Orders',
      'unit.licenses': '',
      'unit.orders': '',
      'detail.viewLicenses': 'View licenses',
      'detail.licensesNote': 'Licenses under this user\'s email (full keys, for support actions only; no signed tokens):',
      'selfPwd.title': 'Change My Password',
      'selfPwd.note': 'Changing the password kills the current session immediately; you must sign in again with the new password. A security alert (no password inside) will be emailed to you. 8–72 characters with both letters and digits, and it must differ from the current one.',
      'selfPwd.submit': 'Change password',
      'footer.note': 'Internal page — do not share the link. Accounts with two-factor enabled need an extra code at sign-in.',
      'common.cancel': 'Cancel',
      'common.confirm': 'Confirm',
      'common.close': 'Close',
      'common.none': '—',
      'err.loginFailed': 'Sign-in failed. Check the email and password.',
      'err.mfaLoginTicketLost': 'The sign-in ticket expired. Please enter the password again.',
      'err.verifyFailed': 'Verification failed, please retry.',
      'err.sendCodeFailed': 'Failed to send the code, please retry later.',
      'err.noToken': 'Sign-in failed: no token returned.',
      'err.notAdmin': 'This account is not an admin and cannot enter the console.',
      'err.pwdResetByOther': 'Your password was reset by another admin: sign in on the account page (/account/) to change it first, then come back here.',
      'err.sessionExpired': 'Session expired or not an admin. Please sign in again.',
      'err.badCredentials': 'Wrong email or password, or not an admin.',
      'err.rangeRequired': 'Please fill in both start and end times.',
      'err.rangeOrder': 'Start time cannot be after end time.',
      'err.network': 'Network error: cannot reach the server, please retry later.',
      'err.queryFailed': 'Query failed: {reason}',
      'err.mfaPasswordRequired': 'Please enter the current password.',
      'err.mfaEnrollFailed': 'Generation failed, please retry.',
      'err.mfaSixDigits': 'Please enter a 6-digit code.',
      'err.mfaActivateFailed': 'Activation failed, please retry.',
      'err.mfaUnbindCodeRequired': 'Please enter the authenticator code or an emailed code.',
      'err.mfaUnbindFailed': 'Unbind failed, please retry.',
      'err.exportFailed': 'Export failed, please retry later.',
      'err.pwdLength': 'The new password must be 8–72 characters.',
      'err.pwdAlnum': 'The new password must contain both letters and digits.',
      'err.pwdMismatch': 'The two new passwords do not match.',
      'err.selfPwdRequired': 'Please fill in the current and new password.',
      'err.selfPwdFailed': 'Change failed, please retry.',
      'err.enterEmailPassword': 'Please enter the admin email and password.',
      'err.detailBad': 'Unexpected detail payload.',
      'err.resetFailed': 'Reset failed, please retry.'
    }
  };

  var lang = 'zh';

  function readLang() {
    try {
      var saved = localStorage.getItem(LANG_KEY);
      return (saved === 'zh' || saved === 'en') ? saved : 'zh';
    } catch (e) { return 'zh'; }
  }

  function saveLang(value) {
    try { localStorage.setItem(LANG_KEY, value); } catch (e) { /* 隐私模式下忽略 */ }
  }

  /** 取文案：缺 key 回落中文，再缺回落 key 本身；{var} 插值与 account.js 同约定。 */
  function t(key, vars) {
    var dict = I18N[lang] || I18N.zh;
    var text = dict[key] !== undefined ? dict[key] : (I18N.zh[key] || key);
    if (vars) {
      Object.keys(vars).forEach(function (name) {
        text = text.replace('{' + name + '}', String(vars[name]));
      });
    }
    return text;
  }

  /**
   * 应用语言：静态标记（data-i18n / -placeholder / -aria / -alt）、meta、html lang 与
   * 语言开关标签；已登录时重载当前分区，让动态表格按新语言重新生成。
   */
  function applyLang(nextLang) {
    lang = nextLang;
    saveLang(lang);
    var dict = I18N[lang] || I18N.zh;
    document.documentElement.lang = HTML_LANG[lang] || 'zh-CN';

    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (dict[key] !== undefined) { el.textContent = dict[key]; }
    });
    document.querySelectorAll('[data-i18n-placeholder]').forEach(function (el) {
      var key = el.getAttribute('data-i18n-placeholder');
      if (dict[key] !== undefined) { el.setAttribute('placeholder', dict[key]); }
    });
    document.querySelectorAll('[data-i18n-aria]').forEach(function (el) {
      var key = el.getAttribute('data-i18n-aria');
      if (dict[key] !== undefined) { el.setAttribute('aria-label', dict[key]); }
    });
    document.querySelectorAll('[data-i18n-alt]').forEach(function (el) {
      var key = el.getAttribute('data-i18n-alt');
      if (dict[key] !== undefined) { el.setAttribute('alt', dict[key]); }
    });

    if (dict['meta.title']) { document.title = dict['meta.title']; }
    var desc = document.querySelector('meta[name="description"]');
    if (desc && dict['meta.desc']) { desc.setAttribute('content', dict['meta.desc']); }

    $('langCurrent').textContent = LANG_LABEL[lang];
    $('langOther').textContent = LANG_LABEL[lang === 'zh' ? 'en' : 'zh'];
    $('langSwitch').setAttribute('aria-label', dict['a11y.langSwitch']);

    // 动态区重渲染：表格 / 提示均由脚本拼装，切语言后按新字典重新拉取当前分区
    if (state.token) { loadTab(state.tab); }
  }


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

  /** 指标卡等需要紧凑展示的位置用币种符号，避免「CNY 620.00 · USD 0.00」换行 */
  function currencySymbol(currency) {
    if (currency === 'CNY') return '¥';
    if (currency === 'USD') return '$';
    return currency ? currency + ' ' : '';
  }

  function fmtDateTime(iso) {    if (!iso) return '—';
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
      PAID_BUT_NO_SUCCESS_PAYMENT: t('disc.paidNoPayment'),
      AMOUNT_MISMATCH: t('disc.amountMismatch'),
      REFUNDED_BUT_NO_PAYMENT: t('disc.refundedNoPayment'),
      SUCCESS_PAYMENT_BUT_ORDER_NOT_PAID: t('disc.paidNotMarked')
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

  /** G1：统一 toast 反馈（2 秒自动消失，可堆积） */
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

  function todayStamp() {
    var now = new Date();
    return now.getFullYear() + pad(now.getMonth() + 1) + pad(now.getDate());
  }

  function showAuth() {
    $('authPanel').hidden = false;
    $('mainPanel').hidden = true;
  }

  function showMain() {
    $('authPanel').hidden = true;
    $('mainPanel').hidden = false;
    $('keyScopeNote').textContent =
      $('rememberKey').checked ? t('userbar.scopeLs') : t('userbar.scopeSession');
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
      showError('authError', err && err.message ? err.message : t('err.loginFailed'));
    });
  }

  /**
   * 登录第二步（第二因子）：票据换正式令牌。
   *
   * <p>返回 Promise，供「输满 6 位自动提交」与按钮/回车两条入口共用；
   * 验证失败时清空输入框并聚焦，用户可直接重输并再次触发自动提交。
   */
  function verifyMfa(code) {
    if (!pending.mfaTicket) {
      backToLoginStep();
      showError('authError', t('err.mfaLoginTicketLost'));
      return Promise.resolve();
    }
    hideError('mfaLoginError');
    setMfaVerifying(true);
    return apiPost('/api/account/mfa/verify', { ticket: pending.mfaTicket, code: code }).then(function (data) {
      finishLogin(data, pending.remember, 'mfaLoginError');
    }).catch(function (err) {
      showError('mfaLoginError', err && err.message ? err.message : t('err.verifyFailed'));
      $('mfaLoginCode').value = '';
      $('mfaLoginCode').focus();
    }).then(function () {
      setMfaVerifying(false);
    });
  }

  /** 验证中：禁用按钮与输入框，避免自动提交与手动点击各发一次请求。 */
  function setMfaVerifying(busy) {
    mfaVerifying = busy;
    var btn = $('mfaVerifyBtn');
    btn.disabled = busy;
    btn.textContent = busy ? t('mfaLogin.verifying') : t('mfaLogin.verify');
    $('mfaLoginCode').readOnly = busy;
  }

  /** 请求邮箱兜底验证码（认证器不可用时的恢复路径）。 */
  function sendMfaEmailCode() {
    if (!pending.mfaTicket) {
      backToLoginStep();
      showError('authError', t('err.mfaLoginTicketLost'));
      return;
    }
    hideError('mfaLoginError');
    var email = $('loginEmail').value.trim();
    apiPost('/api/account/mfa/challenge', { ticket: pending.mfaTicket }).then(function () {
      /* 成功提示不占用错误位（那里是红色语义），改写说明文案 */
      $('mfaStepDesc').textContent = t('mfaLogin.sentTo', { email: email });
      $('mfaLoginCode').focus();
    }).catch(function (err) {
      showError('mfaLoginError', err && err.message ? err.message : t('err.sendCodeFailed'));
    });
  }

  /** 两步共用的收尾：校验令牌与非管理员拦截，均落在同一处。 */
  function finishLogin(data, remember, errorId) {
    var token = data && data.accessToken;
    var role = data && data.user && data.user.role;
    if (!token) { showError(errorId, t('err.noToken')); return; }
    if (role !== 'ADMIN') {
      showError(errorId, t('err.notAdmin'));
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
    /* 先取自身 ID 再渲染分区：用户列表要靠它禁用「对自己的操作」 */
    loadMe().then(function () { loadTab(state.tab); renderMfaBanner(); });
  }

  /** 取当前管理员资料（识别自身用；失败不阻塞渲染，禁用态退化为「全部可点」）。 */
  function loadMe() {
    return apiRequest('GET', '/api/account/me', null, true).then(function (data) {
      state.meId = (data && data.id) ? String(data.id) : '';
      state.meMfa = !!(data && data.mfaEnabled);
      return data;
    }).catch(function () {
      state.meId = '';
      state.meMfa = false;
    });
  }

  /**
   * MFA 引导横幅（SEC-4）：管理员已登录但未开启二次验证时显示，纯提示、不拦截任何操作。
   * 本次会话内点「忽略」后不再弹出；开启 MFA 或退出登录后复位。
   */
  function renderMfaBanner() {
    var banner = $('mfaBanner');
    if (!banner) return;
    var show = !!state.token && !state.meMfa && !state.mfaBannerDismissed;
    banner.hidden = !show;
  }

  function showMfaStep(methods) {
    hideError('authError');
    hideError('mfaLoginError');
    $('loginStep').hidden = true;
    $('mfaStep').hidden = false;
    var emailFallback = !methods || methods.indexOf('EMAIL') !== -1;
    $('mfaStepDesc').textContent = emailFallback
      ? t('mfaLogin.descWithFallback')
      : t('mfaLogin.descTotpOnly');
    $('mfaEmailBtn').hidden = !emailFallback;
    setMfaVerifying(false);
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
    state.meId = '';
    state.meMfa = false;
    state.mfaBannerDismissed = false;
    var banner = $('mfaBanner');
    if (banner) banner.hidden = true;
    closeResetModal();
    closeUserDetail();
    pending.mfaTicket = null;
    pending.remember = false;
    $('loginEmail').value = '';
    $('loginPassword').value = '';
    $('mfaLoginCode').value = '';
    /* 本人改密残留输入一并清掉：密码只在表单打开期间存在于 DOM */
    $('selfPwdOld').value = '';
    $('selfPwdNew').value = '';
    $('selfPwdConfirm').value = '';
    hideError('selfPwdError');
    $('mfaStep').hidden = true;
    $('loginStep').hidden = false;
    hideError('mfaLoginError');
    showAuth();
  }

  /* 401/403：令牌失效或无权限——清掉本地令牌，退回登录卡片 */
  function handleAuthFailure(err) {
    lock();
    // Q3：密码被其他管理员代重置时，管理端动作会被过滤器挡下——必须明确告知去哪里改密，
    // 否则「无管理员权限」会把人引到错误的排查方向。
    showError('authError', (err && err.code === 'PASSWORD_CHANGE_REQUIRED')
      ? t('err.pwdResetByOther')
      : t('err.sessionExpired'));
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
   *
   * <p>所有动作（GET 查询 / POST 处置 / PATCH 行内变更）都走这里，避免每个动作各写一套 fetch
   * ——剥壳、401/403 处理与网络错误归一只有一份实现。
   *
   * @param method       HTTP 方法（GET / POST / PATCH）
   * @param body         请求体对象；null / undefined 表示无请求体（不设 Content-Type）
   * @param withAuth     需要访问令牌时传 true。登录与半认证的 /api/account/mfa/** 端点不带令牌——
   *                     此时 401/403 的语义是「凭据错误」而非「登录失效」，故不触发 handleAuthFailure，
   *                     否则会把「密码错」误报成「登录已失效」。
   * @param extraHeaders 额外请求头；可选
   */
  function apiRequest(method, url, body, withAuth, extraHeaders) {
    var headers = { 'Accept': 'application/json' };
    var hasBody = (body !== null && body !== undefined);
    if (hasBody) { headers['Content-Type'] = 'application/json'; }
    if (withAuth) { headers['Authorization'] = 'Bearer ' + state.token; }
    if (extraHeaders) {
      for (var name in extraHeaders) {
        if (Object.prototype.hasOwnProperty.call(extraHeaders, name)) { headers[name] = extraHeaders[name]; }
      }
    }
    return fetch(url, {
      method: method,
      headers: headers,
      body: hasBody ? JSON.stringify(body) : undefined
    }).then(function (res) {
      return res.text().then(function (raw) {
        var payload = null;
        if (raw) { try { payload = JSON.parse(raw); } catch (e) { payload = null; } }
        if (res.status === 401 || res.status === 403) {
          if (withAuth) {
            var err = buildApiError(res.status, payload);
            handleAuthFailure(err);
            throw err;
          }
          throw { kind: 'api', status: res.status, code: 'AUTH', message: t('err.badCredentials') };
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

  /** POST：代理到统一入口（有请求体）。 */
  function apiPost(url, body, withAuth, extraHeaders) {
    return apiRequest('POST', url, body, withAuth, extraHeaders);
  }

  /** PATCH：代理到统一入口（行内改角色 / 启停走查询参数，无请求体）。 */
  function apiPatch(url, body, withAuth, extraHeaders) {
    return apiRequest('PATCH', url, body, withAuth, extraHeaders);
  }

  /** GET：代理到统一入口（管理端查询一律带令牌）。 */
  function requestJson(url) {
    return apiRequest('GET', url, null, true);
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
      showError('rangeError', t('err.rangeRequired'));
      return;
    }
    if (from > to) {
      showError('rangeError', t('err.rangeOrder'));
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
    /* 时间范围工具栏只服务于统计类分区；处置类分区（安全设置 / License / 用户 / 产品 / 订单 / 兑换码 / 渠道）都与时间无关 */
    $('rangePanel').hidden = (tab === 'security' || tab === 'license' || tab === 'users'
        || tab === 'products' || tab === 'orders' || tab === 'refunds' || tab === 'events' || tab === 'codes' || tab === 'channels');
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
    else if (tab === 'users') loadUsers();
    else if (tab === 'products') loadProductConfig();
    else if (tab === 'orders') loadOrders();
    else if (tab === 'refunds') loadRefunds();
    else if (tab === 'events') loadPaymentEvents();
    else if (tab === 'codes') loadRedeemCodes();
    else if (tab === 'channels') loadChannelCheck();
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

  /* G3：请求期间用骨架占位，避免表格区空白 */
  function showPanelLoading(prefix) {
    hideError(prefix + 'Error');
    var wrap = $(prefix + 'TableWrap');
    if (!wrap) return;
    wrap.innerHTML = '<div class="sk-row"><span class="sk" style="max-width:160px"></span>'
      + '<span class="sk"></span><span class="sk" style="max-width:80px"></span></div>'.repeat(3);
    wrap.hidden = false;
    var empty = $(prefix + 'Empty');
    if (empty) empty.hidden = true;
  }

  function errorTextOf(err) {    if (err.kind === 'network') return t('err.network');
    if (err.status === 401 || err.status === 403) return null; /* 已由 handleAuthFailure 处理 */
    return t('err.queryFailed', { reason: (err.message || err.code || ('HTTP_' + err.status)) });
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
  /** P22：指标卡前置——分币种实收 / 订单数 / 退款，一屏概括经营状况 */
  function renderOverviewKpis(summaries, totalOrderCount) {
    var box = $('overviewKpis');
    var paid = 0;
    var refunds = [];
    var cards = '';
    for (var i = 0; i < summaries.length; i++) {
      var s = summaries[i];
      paid += Number(s.paidOrderCount) || 0;
      refunds.push(currencySymbol(s.currency) + money(s.refunded));
      cards += '<div class="kpi">'
        + '<p class="kpi__label">' + escapeHtml(t('kpi.received', { currency: s.currency })) + '</p>'
        + '<p class="kpi__value">' + escapeHtml(money(s.grossReceived)) + '</p>'
        + '<p class="kpi__sub">' + escapeHtml(t('kpi.paidOrders', { n: s.paidOrderCount })) + '</p>'
        + '</div>';
    }
    var unpaid = Math.max(0, (Number(totalOrderCount) || 0) - paid);
    cards += '<div class="kpi">'
      + '<p class="kpi__label">' + escapeHtml(t('kpi.orders')) + '</p>'
      + '<p class="kpi__value">' + escapeHtml(String(totalOrderCount || 0)) + '</p>'
      + '<p class="kpi__sub">' + escapeHtml(t('kpi.ordersSub', { n: unpaid })) + '</p>'
      + '</div>';
    cards += '<div class="kpi">'
      + '<p class="kpi__label">' + escapeHtml(t('kpi.refunds')) + '</p>'
      + '<p class="kpi__value">' + escapeHtml(refunds.join(' · ')) + '</p>'
      + '<p class="kpi__sub">' + escapeHtml(t('kpi.refundsSub')) + '</p>'
      + '</div>';
    box.innerHTML = cards;
    box.hidden = false;
  }

  function loadOverview() {
    showPanelLoading('overview');
    requestJson('/api/admin/accounting/overview' + rangeQuery()).then(function (data) {
      $('overviewRangeNote').textContent =
        t('overview.rangeNote', { from: fmtDateTime(data.from), to: fmtDateTime(data.to), count: data.totalOrderCount });
      var summaries = data.summaries || [];
      if (!summaries.length) {
        $('overviewKpis').hidden = true;
        $('overviewKpis').innerHTML = '';
        renderPanel('overview', '', '');
        return;
      }
      renderOverviewKpis(summaries, data.totalOrderCount);
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
          + '<td class="num">' + escapeHtml(money(s.ledgerGrossReceived)) + '</td>'
          + '<td class="num">' + escapeHtml(money(s.ledgerFee)) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(s.ledgerNet)) + '</td>'
          + '</tr>';
      }
      renderPanel('overview', '', table([
        { label: t('th.currency') }, { label: t('th.orderCount'), num: true }, { label: t('th.paidOrders'), num: true },
        { label: t('th.gmv'), num: true }, { label: t('th.received'), num: true }, { label: t('th.refunded'), num: true },
        { label: t('th.net'), num: true }, { label: t('th.avgOrder'), num: true },
        { label: t('th.ledgerGross'), num: true }, { label: t('th.ledgerFee'), num: true },
        { label: t('th.ledgerNet'), num: true }
      ], rows));
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('overview', text, '');
    });
  }

  /* ---- 分渠道 ---- */
  function loadChannel() {
    showPanelLoading('channel');
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
        { label: t('th.channel') }, { label: t('th.currency') }, { label: t('th.received'), num: true },
        { label: t('th.refunded'), num: true }, { label: t('th.net'), num: true }, { label: t('th.orderCount'), num: true }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('channel', text, '');
    });
  }

  /* ---- 分产品 ---- */
  function loadProduct() {
    showPanelLoading('product');
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
        { label: 'SKU' }, { label: t('th.product') }, { label: t('th.plan') }, { label: t('th.billingCycle') },
        { label: t('th.currency') }, { label: t('th.received'), num: true }, { label: t('th.refunded'), num: true },
        { label: t('th.net'), num: true }, { label: t('th.orderCount'), num: true }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('product', text, '');
    });
  }

  /* ---- 交易流水（分页） ---- */
  function loadTransactions() {
    showPanelLoading('tx');
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
        var tx = content[i];
        rows += '<tr>'
          + '<td>' + escapeHtml(fmtDateTime(tx.createdAt)) + '</td>'
          + '<td>' + none(tx.channelName || tx.channel) + '</td>'
          + '<td>' + statusBadge(tx.status, tx.statusDesc) + '</td>'
          + '<td class="num strong-amount">' + escapeHtml(money(tx.amount, tx.currency)) + '</td>'
          + '<td class="mono">' + none(tx.orderId) + '</td>'
          + '<td class="mono">' + none(tx.transactionId) + '</td>'
          + '<td>' + escapeHtml(fmtDateTime(tx.paidAt)) + '</td>'
          + '</tr>';
      }
      var rowsHtml = content.length ? table([
        { label: t('th.time') }, { label: t('th.channel') }, { label: t('th.status') }, { label: t('th.amount'), num: true },
        { label: t('th.orderId') }, { label: t('th.txnId') }, { label: t('th.paidAt') }
      ], rows) : '';
      renderPanel('tx', '', rowsHtml);
      state.txTotalPages = page.totalPages || 0;
      var pager = $('txPager');
      pager.hidden = !(page.totalPages > 1);
      if (!pager.hidden) {
        $('txPageInfo').textContent = t('pager.info',
        { page: (page.number || 0) + 1, total: page.totalPages, count: page.totalElements });
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
    showPanelLoading('trend');
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
        { label: t('th.bucket') }, { label: t('th.currency') }, { label: t('th.received'), num: true },
        { label: t('th.refunded'), num: true }, { label: t('th.net'), num: true }, { label: t('th.orderCount'), num: true }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) renderPanel('trend', text, '');
    });
  }

  /* ---- 对账差异 ---- */
  function loadDiscrepancies() {
    showPanelLoading('disc');
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
        { label: t('th.foundAt') }, { label: t('th.type') }, { label: t('th.description') }, { label: t('th.order') },
        { label: t('th.orderAmount'), num: true }, { label: t('th.paidAmount'), num: true }, { label: t('th.orderStatus') }
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
    /* 清掉上一轮二维码：密钥只回显一次，旧二维码绝不能残留到下一次绑定 */
    $('mfaQrImg').removeAttribute('src');
    $('mfaQrBox').hidden = true;
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
      ? t('mfaStatus.enabled')
      : t('mfaStatus.disabled');
    if (enabled && data.enrolledAt) html += t('mfaStatus.enrolledAt', { time: escapeHtml(fmtDateTime(data.enrolledAt)) });
    html += data && data.emailFallbackEnabled
      ? t('mfaStatus.fallbackOpen')
      : t('mfaStatus.fallbackClosed');
    $('mfaStatusText').innerHTML = html;
    $('mfaStatusText').hidden = false;

    if (enabled) $('mfaUnbindBox').hidden = false;
    else $('mfaEnrollBox').hidden = false;
  }

  /** 生成密钥（须当前密码）。成功后进入「待激活」态，密钥当场展示。 */
  function enrollMfa() {
    hideError('mfaEnrollError');
    var password = $('mfaPassword').value;
    if (!password) { showError('mfaEnrollError', t('err.mfaPasswordRequired')); return; }

    $('mfaEnrollBtn').disabled = true;
    apiPost('/api/admin/mfa/enroll', { password: password }, true).then(function (data) {
      $('mfaEnrollBtn').disabled = false;
      $('mfaPassword').value = '';
      $('mfaSecretText').textContent = (data && data.secret) || '';
      /* 二维码由服务端本地渲染；渲染失败时为 null——此时不占位，靠手动输入密钥完成绑定 */
      var qr = data && data.qrCodeDataUri;
      if (qr) {
        $('mfaQrImg').src = qr;
        $('mfaQrBox').hidden = false;
      } else {
        $('mfaQrImg').removeAttribute('src');
        $('mfaQrBox').hidden = true;
      }
      $('mfaSecretBox').hidden = false;
      $('mfaEnrollBox').hidden = true;
      $('mfaActivateCode').value = '';
      hideError('mfaActivateError');
      $('mfaActivateCode').focus();
    }).catch(function (err) {
      $('mfaEnrollBtn').disabled = false;
      showError('mfaEnrollError', err && err.message ? err.message : t('err.mfaEnrollFailed'));
    });
  }

  /** 用认证器当前动态码激活；成功后服务端置 mfa_enabled。 */
  function activateMfa() {
    hideError('mfaActivateError');
    var code = $('mfaActivateCode').value.trim();
    if (!/^\d{6}$/.test(code)) { showError('mfaActivateError', t('err.mfaSixDigits')); return; }

    $('mfaActivateBtn').disabled = true;
    apiPost('/api/admin/mfa/activate', { code: code }, true).then(function () {
      $('mfaActivateBtn').disabled = false;
      $('mfaActivateCode').value = '';
      /* 已开启 MFA：同步本地态并收起引导横幅（SEC-4） */
      state.meMfa = true;
      state.mfaBannerDismissed = false;
      renderMfaBanner();
      loadMfa();
    }).catch(function (err) {
      $('mfaActivateBtn').disabled = false;
      showError('mfaActivateError', err && err.message ? err.message : t('err.mfaActivateFailed'));
    });
  }

  /** 解绑（须密码 + 动态码或邮箱兜底码）——两道都验，防「session 被劫持后静默关掉防线」。 */
  function unbindMfa() {
    hideError('mfaUnbindError');
    var password = $('mfaUnbindPassword').value;
    var code = $('mfaUnbindCode').value.trim();
    if (!password) { showError('mfaUnbindError', t('err.mfaPasswordRequired')); return; }
    if (!code) { showError('mfaUnbindError', t('err.mfaUnbindCodeRequired')); return; }

    $('mfaUnbindBtn').disabled = true;
    apiPost('/api/admin/mfa/unbind', { password: password, code: code }, true).then(function () {
      $('mfaUnbindBtn').disabled = false;
      $('mfaUnbindPassword').value = '';
      $('mfaUnbindCode').value = '';
      /* 已解绑 MFA：恢复引导横幅（SEC-4） */
      state.meMfa = false;
      renderMfaBanner();
      loadMfa();
    }).catch(function (err) {
      $('mfaUnbindBtn').disabled = false;
      showError('mfaUnbindError', err && err.message ? err.message : t('err.mfaUnbindFailed'));
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
      showError('licError', byEmail ? t('lic.errEmailRequired') : t('lic.errKeyRequired'));
      return;
    }
    hideError('licError');
    if (!keepResult) { $('licResult').hidden = true; }
    closeLicAction();
    licLastQuery = { byEmail: byEmail, term: term };

    var url = byEmail
      ? '/api/admin/licenses?customerEmail=' + encodeURIComponent(term) + '&size=200'
      : '/api/admin/licenses/' + encodeURIComponent(term);

    requestJson(url).then(function (data) {
      // 单件查询返回对象、列表查询返回分页视图（#9：数据在 Page.content），此处统一收敛为数组
      var list = byEmail
        ? (data && Array.isArray(data.content) ? data.content : [])
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
    var label = { ACTIVE: t('licStatus.active'), EXPIRED: t('licStatus.expired'),
      REVOKED: t('licStatus.revoked'), REISSUED: t('licStatus.reissued') };
    return '<span class="badge badge--' + (cls[status] || 'muted') + '">'
      + escapeHtml(label[status] || status || t('licStatus.unknown')) + '</span>';
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
      rows += '<tr class="lic-row" data-lic-key="' + escapeHtml(l.licenseKey) + '">'
        + '<td><input type="radio" name="licRow" value="' + escapeHtml(l.licenseKey) + '"'
        + ' aria-label="' + escapeHtml(t('lic.action')) + '"></td>'
        + '<td><code class="mono">' + escapeHtml(l.licenseKey) + '</code></td>'
        + '<td>' + licenseStatusBadge(l.status) + '</td>'
        + '<td>' + none(l.customerEmail) + '</td>'
        + '<td><code class="mono">' + none(l.machineCode) + '</code></td>'
        + '<td>' + escapeHtml(fmtDateTime(l.expiresAt)) + '</td>'
        + '</tr>';
    }
    $('licTableWrap').innerHTML = table([
      { label: t('th.select') }, { label: t('th.licenseKey') }, { label: t('th.status') },
      { label: t('th.customerEmail') }, { label: t('th.machineCode') }, { label: t('th.expiresAt') }
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
    queryAll('#licTableWrap .lic-row').forEach(function (row) {
      var selected = row.getAttribute('data-lic-key') === licenseKey;
      row.classList.toggle('is-selected', selected);
      var radio = row.querySelector('input[type="radio"]');
      if (radio) radio.checked = selected;
    });
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

  /** 动作切换：只有「失效重发」需要新机器码与越限开关；作废按钮切成危险样式 */
  function syncLicActionFields() {
    var type = $('licActionType').value;
    var isReissue = type === 'reissue';
    $('licMachineField').hidden = !isReissue;
    $('licForceField').hidden = !isReissue;
    var submit = $('licActionSubmit');
    submit.classList.toggle('btn--danger', type === 'revoke');
    submit.classList.toggle('btn--primary', type !== 'revoke');
    submit.textContent = type === 'revoke' ? t('lic.revokeConfirm') : t('lic.submit');
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
      showError('licActionError', t('lic.errRevokedTerminal'));
      return;
    }
    // 作废不可逆，故前端强制填原因（端点侧 reason 仍可选，以兼容既有直调方）
    if (type === 'revoke' && !reason) {
      showError('licActionError', t('lic.errRevokeNeedsReason'));
      return;
    }
    if (type === 'revoke') {
      openRevokeModal(key, reason);
      return;
    }
    doLicAction(type, reason, key);
  }

  /** P23：作废前再确认一次（危险色 + 后果明示），确认后才真正调用端点 */
  var revokePending = null;

  function openRevokeModal(key, reason) {
    revokePending = { key: key, reason: reason };
    $('revokeTargetNote').textContent = t('lic.revokeTarget', { key: key, reason: reason });
    $('revokeModal').hidden = false;
    $('revokeConfirmBtn').focus();
  }

  function closeRevokeModal() {
    revokePending = null;
    $('revokeModal').hidden = true;
    $('licActionSubmit').focus();
  }

  function confirmRevoke() {
    if (!revokePending) { closeRevokeModal(); return; }
    var pending = revokePending;
    closeRevokeModal();
    doLicAction('revoke', pending.reason, pending.key);
  }

  function doLicAction(type, reason, key) {
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
        note = t('lic.noteUnbind');
      } else if (type === 'revoke') {
        note = t('lic.noteRevoke');
      } else {
        note = t('lic.noteReissue',
          { key: escapeHtml((data && data.licenseKey) || t('lic.newKeyMissing')) });
      }
      $('licResult').innerHTML = note;
      $('licResult').hidden = false;
      if (licLastQuery) { searchLicense(licLastQuery.byEmail, true); }
    }).catch(function (err) {
      $('licActionSubmit').disabled = false;
      showError('licActionError', err && err.message ? err.message : t('lic.errActionFailed'));
    });
  }

  /* ======================= 用户管理（plan-7.0 账户基础功能） ======================= */

  /** 代重置的目标（仅存活于弹层打开期间，关闭即清空） */
  var resetTarget = null;

  function isSelf(userId) {
    return !!state.meId && String(userId) === state.meId;
  }

  /** 组装列表过滤参数（导出与查询共用同一过滤口径）。 */
  function userFilterParams() {
    var parts = [];
    var email = $('userEmail').value.trim();
    if (email) { parts.push('email=' + encodeURIComponent(email)); }
    if ($('userRole').value) { parts.push('role=' + encodeURIComponent($('userRole').value)); }
    if ($('userStatus').value) { parts.push('status=' + encodeURIComponent($('userStatus').value)); }
    return parts;
  }

  /**
   * 导出当前过滤条件的用户 CSV（P2）：带令牌 fetch 成 blob 再触发下载——
   * 直接跳转链接带不上 Authorization 头。
   */
  function exportUsers() {
    var btn = $('userExportBtn');
    var params = userFilterParams();
    btn.disabled = true;
    fetch('/api/admin/users/export' + (params.length ? '?' + params.join('&') : ''), {
      headers: { 'Accept': 'text/csv', 'Authorization': 'Bearer ' + state.token }
    }).then(function (res) {
      if (!res.ok) {
        throw { kind: 'api', status: res.status, code: 'EXPORT_FAILED', message: t('err.exportFailed') };
      }
      return res.blob().then(function (blob) {
        btn.disabled = false;
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        var filename = 'users-' + todayStamp() + '.csv';
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        toast(t('toast.exported', { file: filename }));
      });
    }).catch(function (err) {
      btn.disabled = false;
      if (err && (err.status === 401 || err.status === 403)) { handleAuthFailure(err); return; }
      showError('userError', err && err.message ? err.message : t('err.exportFailed'));
    });
  }

  function loadUsers() {
    showPanelLoading('user');
    hideUserResult();
    var parts = userFilterParams();
    parts.push('page=' + state.userPage);
    parts.push('size=' + USER_PAGE_SIZE);

    requestJson('/api/admin/users?' + parts.join('&')).then(function (page) {
      renderUserTable(page);
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('userError', text); }
    });
  }

  function roleBadge(status) {
    if (status === 'ACTIVE') { return '<span class="badge badge--ok">' + t('status.active') + '</span>'; }
    if (status === 'DISABLED') { return '<span class="badge badge--danger">' + t('status.disabled') + '</span>'; }
    return '<span class="badge badge--muted">' + none(status) + '</span>';
  }

  /* 行内改角色：自身行禁用（服务端另有 requireTarget 兜底，这里只是体验层） */
  function roleSelectHtml(u, self) {
    return '<select class="row-select" data-act="role" data-id="' + escapeHtml(u.id) + '"'
      + (self ? ' disabled title="' + t('title.cantChangeRole') + '"' : '') + '>'
      + '<option value="USER"' + (u.role === 'USER' ? ' selected' : '') + '>' + t('role.user') + '</option>'
      + '<option value="ADMIN"' + (u.role === 'ADMIN' ? ' selected' : '') + '>' + t('role.admin') + '</option>'
      + '</select>';
  }

  function statusButtonHtml(u, self) {
    var disabling = u.status === 'ACTIVE';
    return '<button class="btn btn--ghost btn--sm" type="button" data-act="status"'
      + ' data-id="' + escapeHtml(u.id) + '" data-next="' + (disabling ? 'DISABLED' : 'ACTIVE') + '"'
      + (self ? ' disabled title="' + t('title.cantToggleSelf') + '"' : '') + '>'
      + (disabling ? t('users.disable') : t('users.enable')) + '</button>';
  }

  function resetButtonHtml(u, self) {
    return '<button class="btn btn--ghost btn--sm" type="button" data-act="reset"'
      + ' data-id="' + escapeHtml(u.id) + '" data-email="' + escapeHtml(u.email) + '"'
      + (self ? ' disabled title="' + t('title.cantResetSelf') + '"' : '') + '">'
      + t('users.resetPwd') + '</button>';
  }

  /** 详情按钮（P1）：任何人（含自己）都可看，只是只读资料与计数 */
  function detailButtonHtml(u) {
    return '<button class="btn btn--ghost btn--sm" type="button" data-act="detail"'
      + ' data-id="' + escapeHtml(u.id) + '">' + t('users.detail') + '</button>';
  }

  function renderUserTable(page) {
    var list = (page && page.content) || [];
    var rows = '';
    for (var i = 0; i < list.length; i++) {
      var u = list[i];
      var self = isSelf(u.id);
      rows += '<tr' + (self ? ' class="is-self"' : '') + '>'
        + '<td>' + escapeHtml(u.email) + (self ? '<span class="self-note">' + t('users.selfNote') + '</span>' : '') + '</td>'
        + '<td>' + roleSelectHtml(u, self) + '</td>'
        + '<td>' + roleBadge(u.status) + '</td>'
        + '<td>' + (u.mfaEnabled
            ? '<span class="badge badge--ok">' + t('badge.on') + '</span>'
            : '<span class="badge badge--muted">' + t('badge.off') + '</span>') + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(u.createdAt)) + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(u.lastLoginAt)) + '</td>'
        + '<td>' + (self
            ? '<span class="self-note">' + t('users.selfActionNote') + '</span>'
            : '<div class="row-actions">' + detailButtonHtml(u) + statusButtonHtml(u, self)
              + resetButtonHtml(u, self) + '</div>') + '</td>'
        + '</tr>';
    }
    renderPanel('user', '', list.length ? table([
      { label: t('th.email') }, { label: t('th.role') }, { label: t('th.status') }, { label: t('th.mfa') },
      { label: t('th.createdAt') }, { label: t('th.lastLogin') }, { label: t('th.action') }
    ], rows) : '');

    var pager = $('userPager');
    state.userTotalPages = (page && page.totalPages) || 0;
    pager.hidden = !(state.userTotalPages > 1);
    if (!pager.hidden) {
      $('userPageInfo').textContent = t('pager.info',
        { page: ((page && page.number) || 0) + 1, total: page.totalPages, count: page.totalElements });
      $('userPrev').disabled = page.first === true;
      $('userNext').disabled = page.last === true;
    }
  }

  /** 失败一律按服务端返回重查（不做乐观覆盖），避免出现「界面显示成功、库里没改」的漂移。 */
  function reloadUsersAfterAction() {
    loadUsers();
  }

  /* 敏感动作不再要求第二因子：MFA 仅用于登录，数据修改只在前端确认一次 */

  function changeUserRole(userId, role) {
    var label = role === 'ADMIN' ? t('role.admin') : t('role.user');
    if (!window.confirm(t('confirm.changeRole', { role: label }))) {
      reloadUsersAfterAction();
      return;
    }
    hideError('userError');
    apiPatch('/api/admin/users/' + encodeURIComponent(userId) + '/role?role=' + encodeURIComponent(role), null, true)
      .then(function () {
        hideUserResult();
        showUserResult(t('result.roleChanged', { role: label }));
        reloadUsersAfterAction();
      })
      .catch(function (err) {
        var text = errorTextOf(err);
        if (text) { showError('userError', text); }
        reloadUsersAfterAction();
      });
  }

  function changeUserStatus(userId, next) {
    var disabling = next === 'DISABLED';
    if (!window.confirm(disabling ? t('confirm.disable') : t('confirm.enable'))) {
      reloadUsersAfterAction();
      return;
    }
    hideError('userError');
    apiPatch('/api/admin/users/' + encodeURIComponent(userId) + '/status?status=' + encodeURIComponent(next), null, true)
      .then(function () {
        showUserResult(disabling ? t('result.disabled') : t('result.enabled'));
        reloadUsersAfterAction();
      })
      .catch(function (err) {
        var text = errorTextOf(err);
        if (text) { showError('userError', text); }
        reloadUsersAfterAction();
      });
  }

  function openResetModal(userId, email) {
    resetTarget = { id: userId, email: email || '' };
    $('resetPwdEmail').textContent = resetTarget.email;
    $('resetPwdNew').value = '';
    $('resetPwdConfirm').value = '';
    hideError('resetPwdError');
    $('resetPwdModal').hidden = false;
    $('resetPwdNew').focus();
  }

  /** 关闭即清空输入：新密码只在弹层打开期间存在于 DOM，绝不写入任何存储。 */
  function closeResetModal() {
    resetTarget = null;
    $('resetPwdNew').value = '';
    $('resetPwdConfirm').value = '';
    $('resetPwdEmail').textContent = '';
    hideError('resetPwdError');
    $('resetPwdModal').hidden = true;
  }

  function submitResetPassword() {
    if (!resetTarget) { closeResetModal(); return; }
    hideError('resetPwdError');
    var pwd = $('resetPwdNew').value;
    var confirmPwd = $('resetPwdConfirm').value;
    /* 与服务端 PasswordPolicy 同口径的前端预检（8–72 位、含字母与数字），省一次往返 */
    if (pwd.length < 8 || pwd.length > 72) {
      showError('resetPwdError', t('err.pwdLength'));
      return;
    }
    if (!/[A-Za-z]/.test(pwd) || !/[0-9]/.test(pwd)) {
      showError('resetPwdError', t('err.pwdAlnum'));
      return;
    }
    if (pwd !== confirmPwd) {
      showError('resetPwdError', t('err.pwdMismatch'));
      return;
    }

    var userId = resetTarget.id;
    var email = resetTarget.email;
    $('resetPwdSubmit').disabled = true;
    apiPost('/api/admin/users/' + encodeURIComponent(userId) + '/password/reset',
        { newPassword: pwd }, true)
      .then(function () {
        $('resetPwdSubmit').disabled = false;
        closeResetModal();
        /* 只提示成功：密码不回显、不复制、不留存 */
        showUserResult(t('result.resetDone', { email: email }));
        reloadUsersAfterAction();
      })
      .catch(function (err) {
        $('resetPwdSubmit').disabled = false;
        showError('resetPwdError', err && err.message ? err.message : t('err.resetFailed'));
      });
  }

  function showUserResult(text) {
    hideError('userError');
    $('userResult').textContent = text;
    $('userResult').hidden = false;
  }

  function hideUserResult() {
    $('userResult').hidden = true;
    $('userResult').textContent = '';
  }

  /* ---- 用户详情（P1）：资料 + 计数；许可证明细复用既有查询 ---- */

  var userDetailTrigger = null;

  function openUserDetail(userId) {
    userDetailTrigger = document.activeElement;
    $('userDetailTitle').textContent = t('detail.title');
    hideError('userDetailError');
    $('userDetailBody').hidden = true;
    $('userDetailLicensesBox').hidden = true;
    $('userDetailLicensesWrap').innerHTML = '';
    $('userDetailModal').hidden = false;
    $('userDetailClose').focus();

    requestJson('/api/admin/users/' + encodeURIComponent(userId)).then(function (detail) {
      renderUserDetail(detail);
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('userDetailError', text); }
    });
  }

  function renderUserDetail(detail) {
    var u = detail && detail.user;
    if (!u) { showError('userDetailError', t('err.detailBad')); return; }
    $('userDetailTitle').textContent = t('detail.titleWithEmail', { email: u.email });
    $('userDetailEmail').textContent = u.email;
    $('userDetailRole').textContent = u.role === 'ADMIN' ? t('role.admin') : (u.role === 'USER' ? t('role.user') : none(u.role));
    $('userDetailStatus').innerHTML = roleBadge(u.status);
    $('userDetailMfa').innerHTML = u.mfaEnabled
      ? '<span class="badge badge--ok">' + t('badge.on') + '</span>'
      : '<span class="badge badge--muted">' + t('badge.off') + '</span>';
    $('userDetailCreatedAt').textContent = fmtDateTime(u.createdAt);
    $('userDetailLastLoginAt').textContent = fmtDateTime(u.lastLoginAt);
    $('userDetailLicenseCount').textContent = String(detail.licenseCount);
    $('userDetailOrderCount').textContent = String(detail.orderCount);
    $('userDetailLicensesBtn').setAttribute('data-email', u.email);
    $('userDetailBody').hidden = false;
  }

  /** 复用既有 GET /api/admin/licenses?customerEmail=：完整密钥、不含 signedToken（#9 起为分页视图） */
  function loadUserDetailLicenses() {
    var email = $('userDetailLicensesBtn').getAttribute('data-email');
    if (!email) { return; }
    $('userDetailLicensesBox').hidden = false;
    $('userDetailLicensesEmpty').hidden = true;
    requestJson('/api/admin/licenses?customerEmail=' + encodeURIComponent(email) + '&size=200')
      .then(function (page) {
        var items = page && Array.isArray(page.content) ? page.content : [];
        var rows = '';
        for (var i = 0; i < items.length; i++) {
          var l = items[i];
          rows += '<tr>'
            + '<td><code class="mono">' + escapeHtml(l.licenseKey) + '</code></td>'
            + '<td>' + none(l.productSku) + '</td>'
            + '<td>' + licenseStatusBadge(l.status) + '</td>'
            + '<td><code class="mono">' + none(l.machineCode) + '</code></td>'
            + '<td>' + escapeHtml(fmtDateTime(l.expiresAt)) + '</td>'
            + '</tr>';
        }
        $('userDetailLicensesWrap').innerHTML = items.length
          ? table([{ label: t('th.license') }, { label: t('th.product') }, { label: t('th.status') }, { label: t('th.machineBound') }, { label: t('th.validUntil') }], rows)
          : '';
        $('userDetailLicensesEmpty').hidden = items.length > 0;
      })
      .catch(function (err) {
        $('userDetailLicensesWrap').innerHTML = '';
        var text = errorTextOf(err);
        if (text) { showError('userDetailError', text); }
      });
  }

  function closeUserDetail() {
    $('userDetailModal').hidden = true;
    $('userDetailLicensesWrap').innerHTML = '';
    $('userDetailLicensesBtn').removeAttribute('data-email');
    hideError('userDetailError');
    if (userDetailTrigger && document.contains(userDetailTrigger)) {
      userDetailTrigger.focus();
    }
    userDetailTrigger = null;
  }

  /* ---- 产品配置（单层套餐，S11） ---- */

  /** 表单元数据（周期 / 档位 / 渠道能力）；首次进入分区时拉取后缓存 */
  var productMeta = null;
  /** 套餐列表快照：行内编辑按 id 取回完整对象 */
  var productList = [];
  var planEditing = null;
  var planDrawerTrigger = null;

  var PRODUCT_SKU = /^[A-Za-z0-9][A-Za-z0-9-]{1,63}$/;
  var PRODUCT_AMOUNT = /^\d+(\.\d{1,2})?$/;
  var PRODUCT_POSITIVE_INT = /^\d+$/;
  var PLAN_NONE_GROUP = '__none__';
  var PLAN_RECURRING_CYCLES = { MONTHLY: true, HALF_YEAR: true, QUARTERLY: true, YEARLY: true };
  var PLAN_FIELDS = ['planSku', 'planCode', 'planName', 'planNameEn', 'planDesc', 'planDescEn',
    'planPriceCny', 'planPriceUsd', 'planDays', 'planUpdateDays', 'planMaxMajor',
    'planStripePriceId', 'planPaddlePriceId', 'planCreemProductId'];

  function isPositiveAmount(text) {
    return PRODUCT_AMOUNT.test(text) && Number(text) > 0;
  }

  function isPositiveInt(text) {
    return PRODUCT_POSITIVE_INT.test(text) && Number(text) > 0;
  }

  function findProduct(productId) {
    for (var i = 0; i < productList.length; i++) {
      if (String(productList[i].id) === String(productId)) { return productList[i]; }
    }
    return null;
  }

  /** 进入分区：元数据 + 全量套餐；筛选在内存完成（套餐为数十行量级）。 */
  function loadProductConfig() {
    showPanelLoading('products');
    hideProductsResult();
    return ensureProductMeta().then(function () {
      return requestJson('/api/admin/products');
    }).then(function (list) {
      productList = Array.isArray(list) ? list : [];
      renderProductKpis();
      fillPlanProductFilter();
      fillPlanCodeOptions();
      renderPlanTable();
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { renderPanel('products', text, ''); }
    });
  }

  /** 元数据只取一次；语言切换后再次进入时按当前语言重填下拉项 */
  function ensureProductMeta() {
    if (productMeta) {
      applyProductMetaOptions();
      return Promise.resolve(productMeta);
    }
    return requestJson('/api/admin/products/meta').then(function (data) {
      productMeta = data || { billingCycles: [], tiers: [], channels: [] };
      applyProductMetaOptions();
      return productMeta;
    });
  }

  function applyProductMetaOptions() {
    if (!productMeta) { return; }
    fillEnumSelect('planCycle', productMeta.billingCycles || [], 'cycle.');
    fillEnumSelect('planTier', productMeta.tiers || [], 'tier.');
    updatePlanHint();
  }

  function fillEnumSelect(id, values, labelPrefix) {
    var el = $(id);
    if (!el) { return; }
    var html = '';
    for (var i = 0; i < values.length; i++) {
      var value = String(values[i]);
      html += '<option value="' + escapeHtml(value) + '">' + escapeHtml(t(labelPrefix + value)) + '</option>';
    }
    el.innerHTML = html;
  }

  /** 渠道展示名（与收银台一致；未知渠道直接显示枚举名） */
  var PROVIDER_LABELS = {
    ALIPAY: { zh: '支付宝', en: 'Alipay' },
    WECHAT_PAY: { zh: '微信支付', en: 'WeChat Pay' },
    STRIPE: { zh: 'Stripe', en: 'Stripe' },
    PADDLE: { zh: 'Paddle', en: 'Paddle' },
    CREEM: { zh: 'Creem', en: 'Creem' },
    PAYPAL: { zh: 'PayPal', en: 'PayPal' }
  };

  function providerLabel(method) {
    var meta = PROVIDER_LABELS[method];
    if (!meta) { return method; }
    return lang === 'en' ? meta.en : meta.zh;
  }

  function channelEnabled(method) {
    var channels = (productMeta && productMeta.channels) || [];
    for (var i = 0; i < channels.length; i++) {
      if (channels[i].method === method) { return !!channels[i].enabled; }
    }
    return false;
  }

  /**
   * 当前配置下能卖该套餐的渠道：已启用 + 支持周期 + 渠道必填价目齐全。
   * 与收银台 ProductController#providerCapabilities 同口径（Creem 任意周期都需产品 ID；
   * Paddle 订阅周期需价目 ID）。
   */
  function sellableChannels(chargeMode, cycle, priceIds) {
    var channels = (productMeta && productMeta.channels) || [];
    var ids = priceIds || {};
    return channels.filter(function (channel) {
      if (!channel.enabled) { return false; }
      if ((channel.supportedBillingCycles || []).indexOf(cycle) < 0) { return false; }
      if (chargeMode === 'AUTO_RECURRING' && !channel.supportsRecurring) { return false; }
      if (channel.method === 'CREEM' && !ids.creem) { return false; }
      if (channel.method === 'PADDLE' && chargeMode === 'AUTO_RECURRING' && !ids.paddle) { return false; }
      return true;
    });
  }

  function planGroupKey(product) {
    var code = product.productCode == null ? '' : String(product.productCode).trim().toLowerCase();
    return code || PLAN_NONE_GROUP;
  }

  function planGroupLabel(key) {
    return key === PLAN_NONE_GROUP ? t('plans.notGrouped') : key;
  }

  /** 按产品码归组并按产品码 / SKU 排序，保证列表稳定。 */
  function productGroups(list) {
    var map = {};
    for (var i = 0; i < list.length; i++) {
      var key = planGroupKey(list[i]);
      if (!map[key]) { map[key] = []; }
      map[key].push(list[i]);
    }
    return Object.keys(map).sort().map(function (key) {
      map[key].sort(function (a, b) { return String(a.sku).localeCompare(String(b.sku)); });
      return { key: key, items: map[key] };
    });
  }

  function renderProductKpis() {
    var groups = productGroups(productList);
    var active = 0;
    var warnings = 0;
    for (var i = 0; i < productList.length; i++) {
      if (productList[i].active) { active++; }
      if (planWarnings(productList[i]).length) { warnings++; }
    }
    var box = $('productKpis');
    if (!box) { return; }
    box.innerHTML = ''
      + kpiCard(t('plans.kpiProducts'), String(groups.length), t('plans.kpiProductsSub'))
      + kpiCard(t('plans.kpiPlans'), String(productList.length), t('plans.kpiPlansSub'))
      + kpiCard(t('plans.kpiActive'), String(active),
        t('plans.kpiActiveSub', { n: productList.length - active }))
      + kpiCard(t('plans.kpiWarnings'), String(warnings), t('plans.kpiWarningsSub'), warnings > 0);
    box.hidden = false;
  }

  function kpiCard(label, value, sub, warn) {
    return '<div class="kpi' + (warn ? ' kpi--warn' : '') + '">'
      + '<p class="kpi__label">' + escapeHtml(label) + '</p>'
      + '<p class="kpi__value">' + escapeHtml(value) + '</p>'
      + '<p class="kpi__sub">' + escapeHtml(sub) + '</p>'
      + '</div>';
  }

  function fillPlanProductFilter() {
    var el = $('planProductFilter');
    if (!el) { return; }
    var current = el.value;
    var html = '<option value="">' + escapeHtml(t('plans.allProducts')) + '</option>';
    var groups = productGroups(productList);
    for (var i = 0; i < groups.length; i++) {
      html += '<option value="' + escapeHtml(groups[i].key) + '">'
        + escapeHtml(planGroupLabel(groups[i].key)) + '</option>';
    }
    el.innerHTML = html;
    var stillExists = false;
    for (var j = 0; j < el.options.length; j++) {
      if (el.options[j].value === current) { stillExists = true; break; }
    }
    el.value = stillExists ? current : '';
  }

  function fillPlanCodeOptions() {
    var datalist = $('planCodeOptions');
    if (!datalist) { return; }
    var seen = {};
    var html = '';
    for (var i = 0; i < productList.length; i++) {
      var key = planGroupKey(productList[i]);
      if (key === PLAN_NONE_GROUP || seen[key]) { continue; }
      seen[key] = true;
      html += '<option value="' + escapeHtml(key) + '"></option>';
    }
    datalist.innerHTML = html;
  }

  /** 套餐配置告警：缺必填价目 / 无可售渠道（与列表标黄、指标卡汇总同源）。 */
  function planWarnings(product) {
    var warnings = [];
    var recurring = product.chargeMode === 'AUTO_RECURRING';
    var ids = { stripe: product.stripePriceId, paddle: product.paddlePriceId, creem: product.creemProductId };
    var channels = sellableChannels(product.chargeMode, product.billingCycle, ids);
    if (recurring && !product.paddlePriceId) { warnings.push('plans.warnPaddle'); }
    if (channelEnabled('CREEM') && !product.creemProductId) { warnings.push('plans.warnCreem'); }
    if (!channels.length) { warnings.push('plans.warnNoChannel'); }
    return warnings;
  }

  function filteredPlans() {
    var kw = ($('planKeyword').value || '').trim().toLowerCase();
    var code = $('planProductFilter').value;
    var status = $('planStatusFilter').value;
    return productList.filter(function (p) {
      var hay = [p.sku, p.name, p.nameEn, p.productCode].join(' ').toLowerCase();
      if (kw && hay.indexOf(kw) < 0) { return false; }
      if (code && planGroupKey(p) !== code) { return false; }
      if (status && String(!!p.active) !== status) { return false; }
      return true;
    });
  }

  function renderPlanTable() {
    var list = filteredPlans();
    var groups = productGroups(list);
    var rows = '';
    for (var g = 0; g < groups.length; g++) {
      var group = groups[g];
      rows += '<tr class="group-row"><td colspan="9">'
        + '<span class="mono">' + escapeHtml(planGroupLabel(group.key)) + '</span>'
        + '<span class="group-row__sub">' + escapeHtml(t('plans.groupCount', { n: group.items.length })) + '</span>'
        + '<button class="group-row__add" type="button" data-act="create" data-code="'
        + escapeHtml(group.key === PLAN_NONE_GROUP ? '' : group.key) + '">'
        + escapeHtml(t('plans.groupAdd')) + '</button>'
        + '</td></tr>';
      for (var i = 0; i < group.items.length; i++) {
        rows += planRowHtml(group.items[i]);
      }
    }
    renderPanel('products', '', rows ? table([
      { label: t('th.sku') }, { label: t('th.name') }, { label: t('th.price'), num: true },
      { label: t('th.billingCycle') }, { label: t('th.providers') }, { label: t('th.licenseUpdate') },
      { label: t('products.recommended') }, { label: t('th.status') }, { label: t('th.action') }
    ], rows) : '');
  }

  function planRowHtml(p) {
    var onSale = !!p.active;
    var updateDays = (p.updateUntilDays == null) ? t('products.updateUnlimited') : String(p.updateUntilDays);
    var maxMajor = (p.maxMajorVersion == null) ? t('products.updateUnlimited') : String(p.maxMajorVersion);
    var priceText = t('products.priceCell', { cny: money(p.priceCny), usd: money(p.priceUsd) });
    var daysText = t('products.daysCell', {
      license: p.licenseDurationDays == null ? '—' : String(p.licenseDurationDays),
      update: updateDays,
      major: maxMajor
    });
    var cycleText = t('cycle.' + p.billingCycle)
      + (p.chargeMode === 'AUTO_RECURRING' ? ' · ' + t('plans.autoRenew') : '');
    var warnings = planWarnings(p);
    var ids = { stripe: p.stripePriceId, paddle: p.paddlePriceId, creem: p.creemProductId };
    var channels = sellableChannels(p.chargeMode, p.billingCycle, ids);
    var channelText = channels.length
      ? channels.map(function (c) { return providerLabel(c.method); }).join(' · ')
      : t('plans.noChannel');
    var warningHtml = '';
    for (var w = 0; w < warnings.length; w++) {
      warningHtml += '<span class="badge badge--warn">' + escapeHtml(t(warnings[w])) + '</span>';
    }
    return '<tr class="plan-row' + (warnings.length ? ' row--warn' : '') + '" data-id="' + escapeHtml(p.id) + '">'
      + '<td class="mono">' + escapeHtml(p.sku) + '</td>'
      + '<td>' + escapeHtml(p.name) + (p.nameEn ? '<br>' + escapeHtml(p.nameEn) : '') + '</td>'
      + '<td class="num strong-amount">' + escapeHtml(priceText) + '</td>'
      + '<td>' + escapeHtml(cycleText) + '</td>'
      + '<td><div class="plan-channel-cell">' + escapeHtml(channelText) + warningHtml + '</div></td>'
      + '<td>' + escapeHtml(daysText) + '</td>'
      + '<td>' + (p.recommended
          ? '<span class="badge badge--ok">' + t('products.recommended') + '</span>'
          : '—') + '</td>'
      + '<td>' + (onSale
          ? '<span class="badge badge--ok">' + t('products.onSale') + '</span>'
          : '<span class="badge badge--muted">' + t('products.offSale') + '</span>') + '</td>'
      + '<td><div class="row-actions">'
      + '<button class="btn btn--ghost btn--sm" type="button" data-act="edit"'
        + ' data-id="' + escapeHtml(p.id) + '">' + t('products.edit') + '</button>'
      + '<button class="btn btn--ghost btn--sm" type="button" data-act="toggle"'
        + ' data-id="' + escapeHtml(p.id) + '" data-next="' + (onSale ? 'false' : 'true') + '"'
        + ' data-sku="' + escapeHtml(p.sku) + '">' + (onSale ? t('products.takeOff') : t('products.putOn'))
        + '</button>'
      + '</div></td>'
      + '</tr>';
  }

  function resetPlanForm() {
    for (var i = 0; i < PLAN_FIELDS.length; i++) {
      var el = $(PLAN_FIELDS[i]);
      if (el) { el.value = ''; el.removeAttribute('aria-invalid'); }
    }
    $('planCycle').value = 'ONE_TIME';
    $('planCharge').value = 'ONE_TIME';
    $('planDays').value = '365';
    $('planTier').value = (productMeta && productMeta.tiers && productMeta.tiers.length)
      ? productMeta.tiers[0] : 'PRO';
    $('planRecommended').checked = false;
    $('planSku').disabled = false;
    $('planSkuHint').textContent = t('plans.skuHint');
    hideError('planFormError');
  }

  function openPlanCreate(code) {
    ensureProductMeta().then(function () {
      planDrawerTrigger = document.activeElement;
      planEditing = null;
      resetPlanForm();
      $('planDrawerTitle').textContent = t('plans.createTitle');
      $('planDrawerMode').textContent = t('plans.footCreate');
      $('planCode').value = code || '';
      $('planDrawer').hidden = false;
      updatePlanHint();
      $('planSku').focus();
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('productsError', text); }
    });
  }

  function openPlanEdit(productId) {
    var product = findProduct(productId);
    if (!product) { return; }
    ensureProductMeta().then(function () {
      planDrawerTrigger = document.activeElement;
      planEditing = product;
      resetPlanForm();
      fillPlanForm(product);
      $('planSku').disabled = true;
      $('planSkuHint').textContent = t('plans.skuLocked');
      $('planDrawerTitle').textContent = t('plans.editTitle', { sku: product.sku });
      $('planDrawerMode').textContent = t('plans.footEdit');
      $('planDrawer').hidden = false;
      updatePlanHint();
      $('planName').focus();
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('productsError', text); }
    });
  }

  function fillPlanForm(p) {
    $('planSku').value = p.sku || '';
    $('planCode').value = p.productCode || '';
    $('planName').value = p.name || '';
    $('planNameEn').value = p.nameEn || '';
    $('planDesc').value = p.description || '';
    $('planDescEn').value = p.descriptionEn || '';
    $('planPriceCny').value = p.priceCny == null ? '' : String(p.priceCny);
    $('planPriceUsd').value = p.priceUsd == null ? '' : String(p.priceUsd);
    $('planCycle').value = p.billingCycle || 'ONE_TIME';
    $('planCharge').value = p.chargeMode || 'ONE_TIME';
    $('planDays').value = p.licenseDurationDays == null ? '' : String(p.licenseDurationDays);
    $('planTier').value = p.tier || 'PRO';
    $('planUpdateDays').value = p.updateUntilDays == null ? '' : String(p.updateUntilDays);
    $('planMaxMajor').value = p.maxMajorVersion == null ? '' : String(p.maxMajorVersion);
    $('planRecommended').checked = !!p.recommended;
    $('planStripePriceId').value = p.stripePriceId || '';
    $('planPaddlePriceId').value = p.paddlePriceId || '';
    $('planCreemProductId').value = p.creemProductId || '';
  }

  function closePlanDrawer() {
    $('planDrawer').hidden = true;
    planEditing = null;
    hideError('planFormError');
    if (planDrawerTrigger && document.contains(planDrawerTrigger)) {
      planDrawerTrigger.focus();
    }
    planDrawerTrigger = null;
  }

  function planFormRecurring() {
    return $('planCharge').value === 'AUTO_RECURRING';
  }

  /** 周期 / 收款方式联动：实时显示可售渠道、必填价目，并标黄缺口。 */
  function updatePlanHint() {
    var box = $('planChannelHint');
    var cycleEl = $('planCycle');
    if (!box || !cycleEl) { return; }
    var cycle = cycleEl.value;
    var charge = $('planCharge').value;
    var recurring = charge === 'AUTO_RECURRING';
    var invalid = recurring && !PLAN_RECURRING_CYCLES[cycle];
    var ids = {
      stripe: $('planStripePriceId').value.trim(),
      paddle: $('planPaddlePriceId').value.trim(),
      creem: $('planCreemProductId').value.trim()
    };
    var names = sellableChannels(charge, cycle, ids).map(function (c) { return providerLabel(c.method); });
    var text;
    var warn = false;
    if (invalid) {
      text = t('plans.hintInvalidCharge');
      warn = true;
    } else if (recurring) {
      text = t('plans.hintRecurring', {
        cycle: t('cycle.' + cycle),
        channels: names.length ? names.join(' · ') : t('plans.noChannel')
      });
      if (!ids.paddle) {
        text += ' ' + t('plans.hintMissing', { fields: t('productForm.paddlePriceId') });
        warn = true;
      }
      if (!names.length) { warn = true; }
    } else {
      text = t('plans.hintOnce', {
        cycle: t('cycle.' + cycle),
        channels: names.length ? names.join(' · ') : t('plans.noChannel')
      });
      if (!names.length) { warn = true; }
    }
    box.className = 'channel-hint' + (warn ? ' channel-hint--warn' : '');
    box.textContent = text;
    var paddleLabel = $('planPaddleLabelText');
    if (paddleLabel) {
      paddleLabel.textContent = recurring ? t('plans.paddleRequired') : t('productForm.paddlePriceId');
    }
  }

  function submitPlan() {
    hideError('planFormError');
    var creating = !planEditing;
    var sku = $('planSku').value.trim();
    var code = $('planCode').value.trim();
    var name = $('planName').value.trim();
    var priceCnyText = $('planPriceCny').value.trim();
    var priceUsdText = $('planPriceUsd').value.trim();
    var licenseDaysText = $('planDays').value.trim();
    var updateDaysText = $('planUpdateDays').value.trim();
    var maxMajorText = $('planMaxMajor').value.trim();
    var cycle = $('planCycle').value;
    var charge = $('planCharge').value;

    if (creating && !sku) { showError('planFormError', t('err.productSkuRequired')); return; }
    if (creating && !PRODUCT_SKU.test(sku)) { showError('planFormError', t('err.productSkuPattern')); return; }
    if (!code) { showError('planFormError', t('plans.errCodeRequired')); return; }
    if (code.length > 64) { showError('planFormError', t('plans.errCodeLength')); return; }
    if (!name) { showError('planFormError', t('err.productNameRequired')); return; }
    if (!isPositiveAmount(priceCnyText) || !isPositiveAmount(priceUsdText)) {
      showError('planFormError', t('err.productPrice'));
      return;
    }
    if (!isPositiveInt(licenseDaysText)
        || (updateDaysText && !isPositiveInt(updateDaysText))
        || (maxMajorText && !isPositiveInt(maxMajorText))) {
      showError('planFormError', t('err.productDays'));
      return;
    }
    if (charge === 'AUTO_RECURRING' && !PLAN_RECURRING_CYCLES[cycle]) {
      showError('planFormError', t('plans.errChargeCycle'));
      return;
    }
    if (charge === 'AUTO_RECURRING' && !$('planPaddlePriceId').value.trim()) {
      showError('planFormError', t('plans.errPaddleRequired'));
      $('planPaddlePriceId').setAttribute('aria-invalid', 'true');
      $('planPaddlePriceId').focus();
      return;
    }

    var resultSku = creating ? sku : planEditing.sku;
    if (!window.confirm(t('confirm.saveProduct', {
      sku: resultSku,
      cny: money(priceCnyText),
      usd: money(priceUsdText)
    }))) {
      return;
    }

    var body = {
      productCode: code,
      name: name,
      nameEn: $('planNameEn').value.trim(),
      description: $('planDesc').value.trim(),
      descriptionEn: $('planDescEn').value.trim(),
      priceCny: priceCnyText,
      priceUsd: priceUsdText,
      billingCycle: cycle,
      chargeMode: charge,
      tier: $('planTier').value,
      recommended: $('planRecommended').checked,
      licenseDurationDays: Number(licenseDaysText),
      updateUntilDays: updateDaysText ? Number(updateDaysText) : null,
      maxMajorVersion: maxMajorText ? Number(maxMajorText) : null,
      stripePriceId: $('planStripePriceId').value.trim(),
      paddlePriceId: $('planPaddlePriceId').value.trim(),
      creemProductId: $('planCreemProductId').value.trim()
    };
    if (creating) {
      body.sku = sku;
      body.active = true;
    }

    $('planSubmit').disabled = true;
    var url = creating
      ? '/api/admin/products'
      : '/api/admin/products/' + encodeURIComponent(planEditing ? planEditing.id : '');
    var call = creating ? apiPost(url, body, true) : apiPatch(url, body, true);
    call.then(function (saved) {
      $('planSubmit').disabled = false;
      closePlanDrawer();
      return loadProductConfig().then(function () {
        showProductsResult(t(creating ? 'products.resultCreated' : 'products.resultUpdated',
          { sku: (saved && saved.sku) || resultSku }));
      });
    }).catch(function (err) {
      $('planSubmit').disabled = false;
      showError('planFormError', err && err.message ? err.message : t('err.productSaveFailed'));
    });
  }

  function togglePlanActive(productId, nextActive, sku) {
    var confirmText = nextActive
      ? t('confirm.putOnProduct', { sku: sku })
      : t('confirm.takeOffProduct', { sku: sku });
    if (!window.confirm(confirmText)) { return; }
    hideError('productsError');

    apiPatch('/api/admin/products/' + encodeURIComponent(productId)
        + '/active?active=' + (nextActive ? 'true' : 'false'), null, true)
      .then(function (saved) {
        return loadProductConfig().then(function () {
          showProductsResult(t(nextActive ? 'products.resultOn' : 'products.resultOff',
            { sku: (saved && saved.sku) || sku }));
        });
      })
      .catch(function (err) {
        var text = errorTextOf(err);
        if (text) { showError('productsError', text); }
      });
  }

  function showProductsResult(text) {
    hideError('productsError');
    $('productsResult').textContent = text;
    $('productsResult').hidden = false;
  }

  function hideProductsResult() {
    $('productsResult').hidden = true;
    $('productsResult').textContent = '';
  }

  /* ---- 本人改密（P1）：复用既有 /api/account/password/change，成功即退出重登 ---- */

  function submitSelfPasswordChange() {
    hideError('selfPwdError');
    var oldPwd = $('selfPwdOld').value;
    var newPwd = $('selfPwdNew').value;
    var confirmPwd = $('selfPwdConfirm').value;
    if (!oldPwd || !newPwd || !confirmPwd) {
      showError('selfPwdError', t('err.selfPwdRequired'));
      return;
    }
    /* 与服务端 PasswordPolicy 同口径的前端预检（8–72 位、含字母与数字） */
    if (newPwd.length < 8 || newPwd.length > 72) {
      showError('selfPwdError', t('err.pwdLength'));
      return;
    }
    if (!/[A-Za-z]/.test(newPwd) || !/[0-9]/.test(newPwd)) {
      showError('selfPwdError', t('err.pwdAlnum'));
      return;
    }
    if (newPwd !== confirmPwd) {
      showError('selfPwdError', t('err.pwdMismatch'));
      return;
    }

    var btn = $('selfPwdSubmit');
    btn.disabled = true;
    apiPost('/api/account/password/change', { oldPassword: oldPwd, newPassword: newPwd }, true)
      .then(function () {
        /* 改密使 tokenVersion+1：本会话令牌已失效；lock() 退回登录卡片并清掉表单残留 */
        lock();
        $('selfPwdSubmit').disabled = false;
        showError('authError', t('result.selfPwdChanged'));
      })
      .catch(function (err) {
        btn.disabled = false;
        /* handleAuthFailure 已把 401/403 处理为退回登录态（含被代重置引导），其余错误就地提示 */
        if (!err || (err.status !== 401 && err.status !== 403)) {
          showError('selfPwdError', err && err.message ? err.message : t('err.selfPwdFailed'));
        }
      });
  }

  /* ======================= 4.7 订单处置（#10） ======================= */

  /** 订单列表每页条数（与后端默认口径一致：20 条/页、上限 200） */
  var ORDER_PAGE_SIZE = 20;

  function showOrderResult(text) {
    hideError('orderError');
    $('orderResult').textContent = text;
    $('orderResult').hidden = false;
  }

  function hideOrderResult() {
    $('orderResult').hidden = true;
    $('orderResult').textContent = '';
  }

  function orderFilterParams() {
    var parts = [];
    var no = $('orderNumberInput').value.trim();
    if (no) { parts.push('orderNumber=' + encodeURIComponent(no)); }
    var status = $('orderStatusFilter').value;
    if (status) { parts.push('status=' + encodeURIComponent(status)); }
    return parts;
  }

  function loadOrders() {
    showPanelLoading('order');
    hideOrderResult();
    var parts = orderFilterParams();
    parts.push('page=' + state.orderPage);
    parts.push('size=' + ORDER_PAGE_SIZE);

    /* #9：后端返回 Page，业务数组在 content，总数在 totalElements */
    requestJson('/api/admin/orders?' + parts.join('&')).then(function (page) {
      renderOrderTable(page);
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('orderError', text); }
    });
  }

  /** 补签只对已支付订单开放：未支付的订单本就没有可签发的权益 */
  function issueButtonHtml(o) {
    var disabled = o.paymentStatus !== 'PAID';
    return '<button class="btn btn--ghost btn--sm" type="button" data-act="issue"'
      + ' data-no="' + escapeHtml(o.orderNumber) + '"'
      + (disabled ? ' disabled title="' + t('title.issuePaidOnly') + '"' : '') + '>'
      + t('orders.issue') + '</button>';
  }

  /** 已退款订单不再重复退款（服务端也是「一单只退一次」的口径） */
  function refundButtonHtml(o) {
    var done = o.paymentStatus === 'REFUNDED' || o.paymentStatus === 'PARTIALLY_REFUNDED';
    return '<button class="btn btn--ghost btn--sm btn--danger" type="button" data-act="refund"'
      + ' data-no="' + escapeHtml(o.orderNumber) + '"'
      + ' data-amount="' + escapeHtml(o.totalAmount == null ? '' : o.totalAmount) + '"'
      + ' data-currency="' + escapeHtml(o.currency || '') + '"'
      + (done ? ' disabled title="' + t('title.alreadyRefunded') + '"' : '') + '>'
      + t('orders.refund') + '</button>';
  }

  function renderOrderTable(page) {
    var list = (page && page.content) || [];
    var rows = '';
    for (var i = 0; i < list.length; i++) {
      var o = list[i];
      rows += '<tr>'
        + '<td class="mono">' + escapeHtml(o.orderNumber || '') + '</td>'
        + '<td class="num">' + escapeHtml(money(o.totalAmount, o.currency)) + '</td>'
        + '<td>' + statusBadge(o.status) + '</td>'
        + '<td>' + statusBadge(o.paymentStatus) + '</td>'
        + '<td>' + none(o.customerEmail) + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(o.createdAt)) + '</td>'
        + '<td><div class="row-actions">' + issueButtonHtml(o) + refundButtonHtml(o) + '</div></td>'
        + '</tr>';
    }
    renderPanel('order', '', list.length ? table([
      { label: t('th.orderNumber') }, { label: t('th.amount'), num: true },
      { label: t('th.orderStatus') }, { label: t('th.paymentStatus') },
      { label: t('th.customer') }, { label: t('th.createdAt') }, { label: t('th.action') }
    ], rows) : '');

    var pager = $('orderPager');
    state.orderTotalPages = (page && page.totalPages) || 0;
    pager.hidden = !(state.orderTotalPages > 1);
    if (!pager.hidden) {
      $('orderPageInfo').textContent = t('pager.info',
        { page: ((page && page.number) || 0) + 1, total: page.totalPages, count: page.totalElements });
      $('orderPrev').disabled = page.first === true;
      $('orderNext').disabled = page.last === true;
    }
  }

  /**
   * I4：为已支付订单补签许可证（渠道回调丢失时的运维补偿）。
   * 与自动发货复用同一服务方法，故天然幂等——重复点只是拿回已签发的记录。
   */
  function issueLicenses(orderNumber) {
    if (!window.confirm(t('confirm.issue', { no: orderNumber }))) { return; }
    hideError('orderError');
    apiPost('/api/admin/orders/' + encodeURIComponent(orderNumber) + '/issue', {}, true)
      .then(function (list) {
        showOrderResult(t('result.issued', { count: (list && list.length) || 0 }));
        loadOrders();
      })
      .catch(function (err) {
        var text = errorTextOf(err);
        if (text) { showError('orderError', text); }
      });
  }

  /** 退款不可逆且会作废许可证，故走弹层要原因（原因进审计留痕）而不是行内直接触发 */
  var refundTarget = { orderNumber: '', amount: '', currency: '' };

  function openRefundModal(orderNumber, amount, currency) {
    refundTarget = { orderNumber: orderNumber, amount: amount || '', currency: currency || '' };
    $('refundOrderNo').textContent = orderNumber;
    $('refundDanger').textContent = t('refund.danger', {
      amount: money(amount, currency || '')
    });
    $('refundReason').value = '';
    $('refundCount').textContent = '0/200';
    hideError('refundError');
    $('refundModal').hidden = false;
    $('refundReason').focus();
  }

  function closeRefundModal() {
    $('refundModal').hidden = true;
    refundTarget = { orderNumber: '', amount: '', currency: '' };
    hideError('refundError');
  }

  function submitRefund() {
    var no = refundTarget.orderNumber;
    if (!no) { return; }
    var reason = $('refundReason').value.trim();
    // G2：危险动作的「后果明示」已在弹层常驻，原因为审计必填；不再叠一层 window.confirm
    if (!reason) {
      showError('refundError', t('refund.reasonRequired'));
      $('refundReason').focus();
      return;
    }
    $('refundSubmit').disabled = true;
    apiPost('/api/admin/orders/' + encodeURIComponent(no) + '/refund'
        + '?reason=' + encodeURIComponent(reason), {}, true)
      .then(function (saved) {
        $('refundSubmit').disabled = false;
        closeRefundModal();
        var status = (saved && saved.paymentStatus) || '';
        if (status === 'REFUND_FAILED') {
          /* 渠道没退成：订单仍保留已支付，如实提示，绝不说「已退款」 */
          showError('orderError', t('result.refundFailed'));
        } else {
          showOrderResult(t('result.refunded', { status: status }));
          toast(t('result.refunded', { status: status }));
        }
        loadOrders();
      })
      .catch(function (err) {
        $('refundSubmit').disabled = false;
        showError('refundError', err && err.message ? err.message : t('err.refundFailed'));
      });
  }

  /* ======================= 4.8 兑换码（#10） ======================= */

  /** 当前检索结果，供「导出 CSV」复用（不再为导出单独打一次接口） */
  var codeList = [];

  function showCodeResult(text) {
    hideError('codeError');
    $('codeResult').textContent = text;
    $('codeResult').hidden = false;
  }

  function hideCodeResult() {
    $('codeResult').hidden = true;
    $('codeResult').textContent = '';
  }

  function loadRedeemCodes() {
    showPanelLoading('code');
    hideCodeResult();
    var parts = [];
    var sku = $('codeSku').value.trim();
    if (sku) { parts.push('productSku=' + encodeURIComponent(sku)); }
    var st = $('codeStatus').value;
    if (st) { parts.push('status=' + encodeURIComponent(st)); }

    requestJson('/api/admin/redeem-codes' + (parts.length ? '?' + parts.join('&') : ''))
      .then(function (list) {
        codeList = list || [];
        renderCodeTable(codeList);
      })
      .catch(function (err) {
        var text = errorTextOf(err);
        if (text) { showError('codeError', text); }
      });
  }

  function codeStatusBadge(status) {
    var clsMap = { UNUSED: 'ok', USED: 'muted', EXPIRED: 'warn', REVOKED: 'danger' };
    var keyMap = { UNUSED: 'codes.unused', USED: 'codes.used', EXPIRED: 'codes.expired', REVOKED: 'codes.revoked' };
    var label = keyMap[status] ? t(keyMap[status]) : status;
    return '<span class="badge badge--' + (clsMap[status] || 'muted') + '">' + escapeHtml(label) + '</span>';
  }

  function renderCodeTable(list) {
    var rows = '';
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      var revocable = c.status === 'UNUSED';
      rows += '<tr>'
        + '<td class="mono">' + escapeHtml(c.code || '') + '</td>'
        + '<td class="mono">' + none(c.productSku) + '</td>'
        + '<td>' + codeStatusBadge(c.status) + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(c.usedAt)) + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(c.expiresAt)) + '</td>'
        + '<td><div class="row-actions">'
        + '<button class="btn btn--ghost btn--sm btn--danger" type="button" data-act="revokeCode"'
        + ' data-code="' + escapeHtml(c.code || '') + '"'
        + (revocable ? '' : ' disabled title="' + t('title.alreadyRefunded') + '"') + '>'
        + t('codes.revoke') + '</button>'
        + '</div></td>'
        + '</tr>';
    }
    renderPanel('code', '', list.length ? table([
      { label: t('th.code') }, { label: t('codes.sku') }, { label: t('th.codeStatus') },
      { label: t('th.codeUsedAt') }, { label: t('th.codeExpires') }, { label: t('th.action') }
    ], rows) : '');
  }

  function revokeCode(code) {
    if (!window.confirm(t('confirm.revokeCode', { code: code }))) { return; }
    hideError('codeError');
    apiPost('/api/admin/redeem-codes/revoke/' + encodeURIComponent(code), {}, true)
      .then(function () {
        showCodeResult(t('codes.resultRevoked', { code: code }));
        loadRedeemCodes();
      })
      .catch(function (err) {
        var text = errorTextOf(err);
        if (text) { showError('codeError', text); }
      });
  }

  function generateCodes() {
    var sku = $('genSku').value.trim();
    if (!sku) { showError('codeError', t('err.codesSkuRequired')); return; }
    var countText = $('genCount').value.trim();
    if (!isPositiveInt(countText)) { showError('codeError', t('err.codesCount')); return; }

    var params = 'productSku=' + encodeURIComponent(sku) + '&count=' + encodeURIComponent(countText);
    var expiresRaw = $('genExpires').value;
    if (expiresRaw) {
      /* datetime-local 缺秒，后端 LocalDateTime 解析需要补上 */
      params += '&expiresAt=' + encodeURIComponent(expiresRaw.length === 16 ? expiresRaw + ':00' : expiresRaw);
    }

    hideError('codeError');
    $('codeGenerate').disabled = true;
    apiPost('/api/admin/redeem-codes/generate?' + params, {}, true)
      .then(function (data) {
        $('codeGenerate').disabled = false;
        var codes = (data && data.codes) || [];
        showCodeResult(t('codes.resultGenerated', { count: codes.length }));
        if (codes.length) {
          $('codeOutputText').value = codes.join('\n');
          $('codeOutput').hidden = false;
        } else {
          $('codeOutput').hidden = true;
        }
        loadRedeemCodes();
      })
      .catch(function (err) {
        $('codeGenerate').disabled = false;
        var text = errorTextOf(err);
        if (text) { showError('codeError', text); }
      });
  }

  /** 导出当前检索结果（码明文 + 状态，供对账） */
  function exportCodes() {
    if (!codeList.length) { return; }
    var rows = [t('th.code') + ',' + t('codes.sku') + ',' + t('th.codeStatus') + ','
      + t('th.codeUsedAt') + ',' + t('th.codeExpires')];
    for (var i = 0; i < codeList.length; i++) {
      var c = codeList[i];
      rows.push([c.code, c.productSku, c.status, c.usedAt || '', c.expiresAt || '']
        .map(csvCell).join(','));
    }
    var filename = 'redeem-codes-' + todayStamp() + '.csv';
    downloadCsv(filename, rows.join('\r\n'));
    toast(t('toast.exported', { file: filename }));
  }

  function csvCell(value) {
    var text = value == null ? '' : String(value);
    return /[",\r\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
  }

  /** 走 Blob + 临时 <a> 下载，避免把码明文拼进 URL */
  function downloadCsv(filename, content) {
    var blob = new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  /* ======================= 4.9 渠道自查（#10） ======================= */

  function loadChannelCheck() {
    showPanelLoading('channelCheck');
    requestJson('/api/admin/payment-channels').then(function (list) {
      renderChannelTable(list || []);
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('channelCheckError', text); }
    });
  }

  /**
   * 只展示渠道名、启用态与缺失的**配置项名**——端点不回显任何密钥值，
   * 这里也不会自己拼出密钥字段。缺失项为空即配置齐全。
   */
  function renderChannelTable(list) {
    var rows = '';
    for (var i = 0; i < list.length; i++) {
      var c = list[i];
      var missing = c.missingConfig || [];
      rows += '<tr>'
        + '<td class="mono">' + escapeHtml(c.method || '') + '</td>'
        + '<td>' + (c.enabled
            ? '<span class="badge badge--ok">' + t('badge.on') + '</span>'
            : '<span class="badge badge--muted">' + t('channels.disabled') + '</span>') + '</td>'
        + '<td>' + (missing.length
            ? '<span class="badge badge--danger">' + escapeHtml(t('channels.missing', { count: missing.length })) + '</span>'
            : '<span class="badge badge--ok">' + t('channels.ok') + '</span>') + '</td>'
        + '<td class="mono">' + (missing.length ? escapeHtml(missing.join(', ')) : '—') + '</td>'
        + '<td>' + escapeHtml(c.note || '') + '</td>'
        + '</tr>';
    }
    loadRuntimeSettings();
    renderPanel('channelCheck', '', list.length ? table([
      { label: t('th.channel') }, { label: t('th.enabled') }, { label: t('th.configured') },
      { label: t('th.missingKeys') }, { label: t('th.note') }
    ], rows) : '');
  }

  function loadRuntimeSettings() {
    var el = $('runtimeSettings');
    if (!el) { return; }
    $('runtimeSettingsError').hidden = true;
    requestJson('/api/admin/payment-settings').then(function (settings) {
      renderRuntimeSettings(settings || {});
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('runtimeSettingsError', text); }
    });
  }

  function renderRuntimeSettings(settings) {
    var channels = settings.channels || [];
    var features = settings.features || [];
    var chRows = '';
    for (var i = 0; i < channels.length; i++) {
      var c = channels[i];
      var disabled = c.configured ? '' : ' disabled';
      chRows += '<tr>'
        + '<td class="mono">' + escapeHtml(c.method || '') + '</td>'
        + '<td>' + escapeHtml(c.name || '') + '</td>'
        + '<td>' + (c.configured
            ? '<span class="badge badge--ok">' + t('channels.ok') + '</span>'
            : '<span class="badge badge--danger">' + escapeHtml(t('channels.missing', { count: (c.missingConfig || []).length })) + '</span>') + '</td>'
        + '<td><label class="switch"><input type="checkbox" data-kind="channel" data-key="'
            + escapeHtml(c.method || '') + '"' + (c.enabled ? ' checked' : '') + disabled
            + '><span>' + (c.enabled ? t('settings.enabled') : t('channels.disabled')) + '</span></label></td>'
        + '</tr>';
    }
    var ftRows = '';
    for (var j = 0; j < features.length; j++) {
      var f = features[j];
      var planned = !f.implemented;
      ftRows += '<tr>'
        + '<td class="mono">' + escapeHtml(f.key || '') + '</td>'
        + '<td>' + escapeHtml(f.name || '') + '</td>'
        + '<td>' + (planned
            ? '<span class="badge badge--muted">' + t('settings.planned') + '</span>'
            : '<span class="badge badge--ok">' + t('settings.enabled') + '</span>') + '</td>'
        + '<td><label class="switch"><input type="checkbox" data-kind="feature" data-key="'
            + escapeHtml(f.key || '') + '"' + (f.enabled ? ' checked' : '') + (planned ? ' disabled' : '')
            + '></label></td>'
        + '</tr>';
    }
    var el = $('runtimeSettings');
    el.innerHTML = '<div class="table-wrap"><table class="table"><thead><tr>'
      + '<th>' + t('th.channel') + '</th><th>' + t('settings.enabled') + '</th><th>' + t('th.configured') + '</th><th></th>'
      + '</tr></thead><tbody>' + chRows + '</tbody></table></div>'
      + '<p class="form-section__desc">' + t('settings.featureHint') + '</p>'
      + '<div class="table-wrap"><table class="table"><thead><tr>'
      + '<th>Key</th><th>' + t('settings.enabled') + '</th><th>Status</th><th></th>'
      + '</tr></thead><tbody>' + ftRows + '</tbody></table></div>';
    el.onchange = function (event) {
      var target = event.target;
      if (!target || target.type !== 'checkbox') { return; }
      var kind = target.getAttribute('data-kind');
      var key = target.getAttribute('data-key');
      var enabled = target.checked;
      var url = kind === 'channel'
        ? '/api/admin/payment-settings/channels/' + encodeURIComponent(key)
        : '/api/admin/payment-settings/features/' + encodeURIComponent(key);
      $('runtimeSettingsError').hidden = true;
      apiRequest('PUT', url, { enabled: enabled }, true).then(function () {
        loadChannelCheck();
      }).catch(function (err) {
        target.checked = !enabled;
        var text = errorTextOf(err);
        if (text) { showError('runtimeSettingsError', text); }
      });
    };
  }

  /* ======================= 4.10 退款审核 ======================= */

  function loadRefunds() {
    showPanelLoading('refund');
    requestJson('/api/admin/refunds/manual').then(function (list) {
      renderRefunds(list || []);
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('refundError', text); }
    });
  }

  function refundStatusLabel(status) {
    var key = 'refund.status.' + status;
    var text = t(key);
    return text === key ? (status || '—') : text;
  }

  function renderRefunds(list) {
    var rows = '';
    for (var i = 0; i < list.length; i++) {
      var r = list[i];
      var amount = money(r.amount, r.currency);
      if (r.requestedAmount && r.amount && r.requestedAmount !== r.amount) {
        amount = money(r.requestedAmount, r.currency) + ' → ' + amount;
      }
      var actions = '';
      if (r.status === 'PENDING_REVIEW') {
        actions = '<button class="btn btn--primary btn--sm" type="button" data-refund-action="approve" data-id="'
            + escapeHtml(r.id) + '">' + t('refunds.approve') + '</button> '
          + '<button class="btn btn--ghost btn--sm btn--danger" type="button" data-refund-action="reject" data-id="'
            + escapeHtml(r.id) + '">' + t('refunds.reject') + '</button>';
      } else if (r.status === 'MANUAL_REQUIRED') {
        actions = '<select class="field__input" data-transfer-channel style="max-width:110px;display:inline-block">'
            + '<option value="BANK">BANK</option><option value="ALIPAY">ALIPAY</option><option value="OTHER">OTHER</option></select> '
          + '<input class="field__input" data-transfer-ref placeholder="' + t('refunds.transferRef')
            + '" style="max-width:150px;display:inline-block"> '
          + '<button class="btn btn--primary btn--sm" type="button" data-refund-action="confirm-transfer" data-id="'
            + escapeHtml(r.id) + '">' + t('refunds.confirmTransfer') + '</button>';
      } else {
        actions = '<span class="muted">—</span>';
      }
      rows += '<tr>'
        + '<td class="mono">' + escapeHtml(r.orderNumber || '—') + '</td>'
        + '<td>' + escapeHtml(r.provider || '—') + '</td>'
        + '<td class="num">' + escapeHtml(amount) + '</td>'
        + '<td>' + escapeHtml(refundStatusLabel(r.status)) + '</td>'
        + '<td>' + escapeHtml(r.reviewNote || r.lastError || '—') + '</td>'
        + '<td class="mono">' + escapeHtml(fmtDateTime(r.createdAt)) + '</td>'
        + '<td>' + actions + '</td>'
        + '</tr>';
    }
    renderPanel('refund', '', list.length ? table([
      { label: t('th.orderNumber') }, { label: t('th.channel') }, { label: t('th.amount'), num: true },
      { label: t('th.status') }, { label: t('th.note') }, { label: t('th.createdAt') }, { label: t('th.action') }
    ], rows) : '');
  }

  /* ======================= 4.11 支付事件日志 ======================= */

  function loadPaymentEvents() {
    showPanelLoading('event');
    var q = '?size=50';
    var provider = $('eventProvider').value.trim();
    if (provider) { q += '&provider=' + encodeURIComponent(provider); }
    var processed = $('eventProcessed').value;
    if (processed !== '') { q += '&processed=' + encodeURIComponent(processed); }
    requestJson('/api/admin/payment-events' + q).then(function (page) {
      var content = (page && page.content) || [];
      var rows = '';
      for (var i = 0; i < content.length; i++) {
        var e = content[i];
        rows += '<tr>'
          + '<td class="mono">' + escapeHtml(e.provider || '') + '</td>'
          + '<td>' + escapeHtml(e.eventType || '—') + '</td>'
          + '<td class="mono">' + escapeHtml(e.orderId || '—') + '</td>'
          + '<td>' + (e.processed
              ? '<span class="badge badge--ok">' + t('events.processedYes') + '</span>'
              : '<span class="badge badge--danger">' + t('events.processedNo') + '</span>') + '</td>'
          + '<td class="mono">' + escapeHtml(fmtDateTime(e.createdAt)) + '</td>'
          + '<td class="mono">' + escapeHtml(e.eventId || '—') + '</td>'
          + '<td class="mono" style="max-width:360px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">'
              + escapeHtml(e.payloadPreview || '—') + '</td>'
          + '<td>' + (e.processed ? '<span class="muted">—</span>'
              : '<button class="btn btn--ghost btn--sm" type="button" data-event-replay="'
                + escapeHtml(e.id) + '">' + t('events.replay') + '</button>') + '</td>'
          + '</tr>';
      }
      renderPanel('event', '', content.length ? table([
        { label: t('th.channel') }, { label: t('th.eventType') }, { label: t('th.orderId') },
        { label: t('th.processed') }, { label: t('th.createdAt') }, { label: t('th.eventId') },
        { label: 'Payload' }, { label: t('th.action') }
      ], rows) : '');
    }).catch(function (err) {
      var text = errorTextOf(err);
      if (text) { showError('eventError', text); }
    });
  }

  /* ======================= 5. 事件绑定与初始化 ======================= */

  function bindEvents() {
    /* 语言切换：与 checkout/account 共用同一偏好键，切换后动态分区按新字典重载 */
    $('langSwitch').addEventListener('click', function () {
      applyLang(lang === 'zh' ? 'en' : 'zh');
    });

    $('unlockForm').addEventListener('submit', function (e) {
      e.preventDefault();
      var email = $('loginEmail').value.trim();
      var password = $('loginPassword').value;
      if (!email || !password) { showError('authError', t('err.enterEmailPassword')); return; }
      hideError('authError');
      login(email, password, $('rememberKey').checked);
    });

    /* 登录第二步：第二因子 */
    $('mfaForm').addEventListener('submit', function (e) {
      e.preventDefault();
      if (mfaVerifying) return;
      var code = $('mfaLoginCode').value.trim();
      if (!/^\d{6}$/.test(code)) { showError('mfaLoginError', t('err.mfaSixDigits')); return; }
      verifyMfa(code);
    });
    /* 动态码输满 6 位即自动验证（键盘输入与粘贴均触发）；不足 6 位或正在验证时不发请求 */
    $('mfaLoginCode').addEventListener('input', function () {
      if (mfaVerifying) return;
      var code = $('mfaLoginCode').value.trim();
      if (!/^\d{6}$/.test(code)) return;
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
      var row = e.target && e.target.closest ? e.target.closest('tr.lic-row') : null;
      if (row) { openLicAction(row.getAttribute('data-lic-key')); }
    });
    $('licActionType').addEventListener('change', syncLicActionFields);
    $('licActionSubmit').addEventListener('click', submitLicAction);
    $('licActionCancel').addEventListener('click', closeLicAction);
    $('revokeConfirmBtn').addEventListener('click', confirmRevoke);
    $('revokeCancelBtn').addEventListener('click', closeRevokeModal);

    $('lockBtn').addEventListener('click', lock);

    /* MFA 引导横幅（SEC-4）：去开启 → 跳安全设置分区；本次忽略 → 本会话不再弹 */
    $('mfaBannerEnable').addEventListener('click', function () {
      state.mfaBannerDismissed = true;
      renderMfaBanner();
      loadTab('security');
    });
    $('mfaBannerDismiss').addEventListener('click', function () {
      state.mfaBannerDismissed = true;
      renderMfaBanner();
    });

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

    /* 用户管理：查询、翻页、行内动作与代重置弹层 */
    $('userSearch').addEventListener('click', function () { state.userPage = 0; loadUsers(); });
    $('userExportBtn').addEventListener('click', exportUsers);
    $('userEmail').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); state.userPage = 0; loadUsers(); }
    });
    $('userPrev').addEventListener('click', function () {
      if (state.userPage > 0) { state.userPage -= 1; loadUsers(); }
    });
    $('userNext').addEventListener('click', function () {
      if (state.userPage < state.userTotalPages - 1) { state.userPage += 1; loadUsers(); }
    });

    /* 订单处置（#10）：查询、翻页、行内补签/退款与退款弹层 */
    $('orderSearch').addEventListener('click', function () { state.orderPage = 0; loadOrders(); });
    $('orderNumberInput').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); state.orderPage = 0; loadOrders(); }
    });
    $('orderStatusFilter').addEventListener('change', function () { state.orderPage = 0; loadOrders(); });
    $('orderPrev').addEventListener('click', function () {
      if (state.orderPage > 0) { state.orderPage -= 1; loadOrders(); }
    });
    $('orderNext').addEventListener('click', function () {
      if (state.orderPage < state.orderTotalPages - 1) { state.orderPage += 1; loadOrders(); }
    });
    $('orderTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-act]') : null;
      if (!btn) { return; }
      var act = btn.getAttribute('data-act');
      if (act === 'issue') { issueLicenses(btn.getAttribute('data-no')); }
      else if (act === 'refund') {
        openRefundModal(btn.getAttribute('data-no'), btn.getAttribute('data-amount'), btn.getAttribute('data-currency'));
      }
    });
    $('refundSubmit').addEventListener('click', submitRefund);
    $('refundCancel').addEventListener('click', closeRefundModal);
    $('refundReason').addEventListener('input', function () {
      $('refundCount').textContent = String($('refundReason').value.length) + '/200';
    });

    /* 兑换码（#10）：检索、导出、批量生成与行内撤销 */
    $('codeSearch').addEventListener('click', loadRedeemCodes);
    $('codeSku').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); loadRedeemCodes(); }
    });
    $('codeStatus').addEventListener('change', loadRedeemCodes);
    $('codeExportBtn').addEventListener('click', exportCodes);
    $('codeGenerate').addEventListener('click', generateCodes);
    $('codeTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-act="revokeCode"]') : null;
      if (btn) { revokeCode(btn.getAttribute('data-code')); }
    });

    /* 渠道自查（#10） */
    $('channelCheck').addEventListener('click', loadChannelCheck);

    /* 退款审核 */
    $('refundReload').addEventListener('click', loadRefunds);

    /* 支付事件日志 */
    $('eventReload').addEventListener('click', loadPaymentEvents);
    $('eventTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-event-replay]') : null;
      if (!btn) { return; }
      if (!window.confirm(t('events.replayConfirm'))) { return; }
      var id = btn.getAttribute('data-event-replay');
      apiRequest('POST', '/api/admin/payment-events/' + encodeURIComponent(id) + '/replay', null, true)
        .then(function () { loadPaymentEvents(); })
        .catch(function (err) {
          var text = errorTextOf(err);
          if (text) { showError('eventError', text); }
        });
    });
    $('refundTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-refund-action]') : null;
      if (!btn) { return; }
      var id = btn.getAttribute('data-id');
      var action = btn.getAttribute('data-refund-action');
      var note = '';
      if (action === 'approve' || action === 'reject') {
        var input = window.prompt(t('refunds.notePrompt'), '');
        if (input === null) { return; }
        note = input;
      }
      var body = { note: note };
      if (action === 'confirm-transfer') {
        var row = btn.closest('tr');
        var channel = row.querySelector('[data-transfer-channel]').value;
        var ref = row.querySelector('[data-transfer-ref]').value.trim();
        if (!ref) {
          showError('refundError', t('refunds.transferRequired'));
          return;
        }
        body.transferChannel = channel;
        body.transferReference = ref;
      }
      hideError('refundError');
      apiRequest('POST', '/api/admin/refunds/' + encodeURIComponent(id) + '/' + action, body, true)
        .then(function () { loadRefunds(); })
        .catch(function (err) {
          var text = errorTextOf(err);
          if (text) { showError('refundError', text); }
        });
    });

    $('userTableWrap').addEventListener('change', function (e) {
      var sel = e.target && e.target.closest ? e.target.closest('[data-act="role"]') : null;
      if (sel) { changeUserRole(sel.getAttribute('data-id'), sel.value); }
    });
    $('userTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-act]') : null;
      if (!btn) { return; }
      var act = btn.getAttribute('data-act');
      if (act === 'status') { changeUserStatus(btn.getAttribute('data-id'), btn.getAttribute('data-next')); }
      else if (act === 'reset') { openResetModal(btn.getAttribute('data-id'), btn.getAttribute('data-email')); }
      else if (act === 'detail') { openUserDetail(btn.getAttribute('data-id')); }
    });

    $('resetPwdSubmit').addEventListener('click', submitResetPassword);
    $('resetPwdCancel').addEventListener('click', closeResetModal);

    /* 用户详情抽屉：关闭 / 点遮罩关闭 / 查看许可证（复用既有许可证查询） */
    $('userDetailClose').addEventListener('click', closeUserDetail);
    $('userDetailBackdrop').addEventListener('click', closeUserDetail);
    $('userDetailLicensesBtn').addEventListener('click', loadUserDetailLicenses);

    /* 产品配置：单层套餐（分组列表 + 右侧分组抽屉） */
    $('planSearch').addEventListener('click', renderPlanTable);
    $('planKeyword').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); renderPlanTable(); }
    });
    $('planProductFilter').addEventListener('change', renderPlanTable);
    $('planStatusFilter').addEventListener('change', renderPlanTable);
    $('planCreateBtn').addEventListener('click', function () { openPlanCreate(''); });
    $('productsTableWrap').addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('[data-act]') : null;
      if (!btn) { return; }
      var act = btn.getAttribute('data-act');
      if (act === 'create') { openPlanCreate(btn.getAttribute('data-code') || ''); }
      else if (act === 'edit') { openPlanEdit(btn.getAttribute('data-id')); }
      else if (act === 'toggle') {
        togglePlanActive(btn.getAttribute('data-id'), btn.getAttribute('data-next') === 'true',
          btn.getAttribute('data-sku'));
      }
    });
    $('planCycle').addEventListener('change', updatePlanHint);
    $('planCharge').addEventListener('change', updatePlanHint);
    ['planStripePriceId', 'planPaddlePriceId', 'planCreemProductId'].forEach(function (id) {
      $(id).addEventListener('input', updatePlanHint);
    });
    $('planSubmit').addEventListener('click', submitPlan);
    $('planCancel').addEventListener('click', closePlanDrawer);
    $('planDrawerClose').addEventListener('click', closePlanDrawer);
    $('planDrawerBackdrop').addEventListener('click', closePlanDrawer);

    /* 本人改密（P1） */
    $('selfPwdSubmit').addEventListener('click', submitSelfPasswordChange);

    /* 找回入口（P1）：跳账号页的邮箱验证码找回流程，不新增后端 API */
    $('gotoAccountReset').addEventListener('click', function () {
      window.location.href = '/account/?mode=reset';
    });

    // G4：弹层/抽屉的 Esc 关闭与 Tab 焦点圈定
    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape' && event.key !== 'Tab') return;
      var open = queryAll('.user-modal, .drawer-wrap').filter(function (el) {
        return !el.hidden;
      })[0];
      if (!open) return;
      if (event.key === 'Escape') {
        closeAdminOverlay(open.id);
        return;
      }
      trapFocus(event, open);
    });
  }

  function closeAdminOverlay(id) {
    if (id === 'refundModal') closeRefundModal();
    else if (id === 'resetPwdModal') closeResetModal();
    else if (id === 'userDetailModal') closeUserDetail();
    else if (id === 'revokeModal') closeRevokeModal();
    else if (id === 'planDrawer') closePlanDrawer();
  }

  function trapFocus(event, root) {
    var focusables = queryAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])', root)
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

  function init() {
    bindEvents();
    applyLang(readLang());
    var stored = loadStoredToken();
    if (stored) {
      $('rememberKey').checked = !!localStorage.getItem(TOKEN_STORAGE);
      state.token = stored;
      showMain();
      resetRangeToDefault();
      loadMe().then(function () { loadTab(state.tab); renderMfaBanner(); });
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
