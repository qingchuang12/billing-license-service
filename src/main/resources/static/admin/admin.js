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
    /** 当前管理员自己的 userId：仅用于前端禁用「对自己的操作」（服务端 requireTarget 才是安全边界） */
    meId: '',
    /** 当前管理员是否已开启二次验证：决定敏感动作前是否走 step-up 弹层（P2） */
    meMfa: false
  };

  /** 半认证态：密码已通过、尚待第二因子时持有的票据（不落存储，刷新即失效） */
  var pending = { mfaTicket: '', remember: false };

  /* ======================= 0. 小工具 ======================= */

  function $(id) { return document.getElementById(id); }

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
      'mfaLogin.useEmail': '改用邮箱验证码',
      'mfaLogin.back': '返回重新输入密码',
      'mfaLogin.descWithFallback': '密码已通过。请输入认证器 App 显示的 6 位动态码；认证器不可用时可改用邮箱验证码。',
      'mfaLogin.descTotpOnly': '密码已通过。请输入认证器 App 显示的 6 位动态码。',
      'mfaLogin.sentTo': '验证码已发送至 {email}，请在下方输入（有效期 10 分钟）。',
      'userbar.note': '已登录 · 令牌保存在本机',
      'userbar.scopeLs': '（localStorage）',
      'userbar.scopeSession': '（sessionStorage，关闭标签页失效）',
      'userbar.logout': '退出登录',
      'range.from': '起始时间',
      'range.to': '结束时间',
      'range.apply': '查询',
      'range.presetsAria': '快捷时间范围',
      'range.today': '今天',
      'range.d7': '近 7 天',
      'range.d30': '近 30 天',
      'range.month': '本月',
      'tabs.aria': '统计分区',
      'tabs.overview': '收入总览',
      'tabs.channel': '分渠道',
      'tabs.product': '分产品',
      'tabs.transactions': '交易流水',
      'tabs.trend': '趋势',
      'tabs.discrepancies': '对账差异',
      'tabs.license': 'License 处置',
      'tabs.users': '用户管理',
      'tabs.security': '安全设置',
      'panel.overview': '收入总览',
      'panel.channel': '分渠道统计',
      'panel.product': '分产品统计',
      'panel.transactions': '交易流水',
      'panel.trend': '收入趋势',
      'panel.discrepancies': '对账差异',
      'panel.license': 'License 处置',
      'panel.users': '用户管理',
      'panel.security': '安全设置 · 二次验证（MFA）',
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
      'th.currency': '币种',
      'th.orderCount': '订单数',
      'th.paidOrders': '已支付订单',
      'th.gmv': 'GMV',
      'th.received': '实收',
      'th.refunded': '已退款',
      'th.net': '净收入',
      'th.avgOrder': '客单价',
      'th.channel': '渠道',
      'th.product': '产品',
      'th.plan': '档位',
      'th.billingCycle': '计费周期',
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
      'stepUp.title': '敏感操作二次确认',
      'stepUp.note': '该操作影响账号安全，请输入认证器动态码完成二次确认（6 位数字）。认证器不可用时可用邮箱验证码兜底。确认令牌 120 秒内有效且仅可使用一次。',
      'stepUp.codeLabel': '动态码',
      'stepUp.label.resetPassword': '重置该用户的密码',
      'stepUp.label.changeRole': '变更该用户的角色',
      'stepUp.label.changeStatus': '启用 / 停用该用户',
      'stepUp.actionPrefix': '即将执行：{action}。',
      'stepUp.noteSent': '验证码已发送，请查收邮箱。',
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
      'err.codeRequired': '请输入动态码。',
      'err.stepUpFailed': '二次确认失败，请重试。',
      'err.sendFailed': '发送失败，请稍后重试。',
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
      'mfaLogin.useEmail': 'Use an emailed code',
      'mfaLogin.back': 'Back to password',
      'mfaLogin.descWithFallback': 'Password accepted. Enter the 6-digit code from your authenticator app; if unavailable, switch to an emailed code.',
      'mfaLogin.descTotpOnly': 'Password accepted. Enter the 6-digit code from your authenticator app.',
      'mfaLogin.sentTo': 'Code sent to {email}. Enter it below (valid for 10 minutes).',
      'userbar.note': 'Signed in · token kept on this device',
      'userbar.scopeLs': ' (localStorage)',
      'userbar.scopeSession': ' (sessionStorage, cleared when the tab closes)',
      'userbar.logout': 'Sign out',
      'range.from': 'From',
      'range.to': 'To',
      'range.apply': 'Apply',
      'range.presetsAria': 'Quick time ranges',
      'range.today': 'Today',
      'range.d7': 'Last 7 days',
      'range.d30': 'Last 30 days',
      'range.month': 'This month',
      'tabs.aria': 'Sections',
      'tabs.overview': 'Overview',
      'tabs.channel': 'Channels',
      'tabs.product': 'Products',
      'tabs.transactions': 'Transactions',
      'tabs.trend': 'Trend',
      'tabs.discrepancies': 'Reconciliation',
      'tabs.license': 'License Actions',
      'tabs.users': 'Users',
      'tabs.security': 'Security',
      'panel.overview': 'Revenue Overview',
      'panel.channel': 'Per-channel Stats',
      'panel.product': 'Per-product Stats',
      'panel.transactions': 'Transactions',
      'panel.trend': 'Revenue Trend',
      'panel.discrepancies': 'Reconciliation',
      'panel.license': 'License Actions',
      'panel.users': 'User Management',
      'panel.security': 'Security · Two-Factor (MFA)',
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
      'stepUp.title': 'Confirm Sensitive Action',
      'stepUp.note': 'This action affects account security. Enter the authenticator code to confirm (6 digits); an emailed code is the fallback. The confirmation token is valid for 120 seconds and single-use.',
      'stepUp.codeLabel': 'Code',
      'stepUp.label.resetPassword': 'reset this user\'s password',
      'stepUp.label.changeRole': 'change this user\'s role',
      'stepUp.label.changeStatus': 'enable / disable this user',
      'stepUp.actionPrefix': 'About to: {action}',
      'stepUp.noteSent': 'Code sent. Please check your inbox.',
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
      'err.codeRequired': 'Please enter the code.',
      'err.stepUpFailed': 'Confirmation failed, please retry.',
      'err.sendFailed': 'Failed to send, please retry later.',
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

  /** 登录第二步（第二因子）：票据换正式令牌。 */
  function verifyMfa(code) {
    if (!pending.mfaTicket) {
      backToLoginStep();
      showError('authError', t('err.mfaLoginTicketLost'));
      return;
    }
    hideError('mfaLoginError');
    apiPost('/api/account/mfa/verify', { ticket: pending.mfaTicket, code: code }).then(function (data) {
      finishLogin(data, pending.remember, 'mfaLoginError');
    }).catch(function (err) {
      showError('mfaLoginError', err && err.message ? err.message : t('err.verifyFailed'));
    });
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
    loadMe().then(function () { loadTab(state.tab); });
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
    closeResetModal();
    closeStepUpModal();
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
   * @param extraHeaders 额外请求头（P2：敏感动作携带 X-Step-Up-Token）；可选
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
    /* 时间范围工具栏只服务于统计类分区；安全设置、License 处置与用户管理都与时间无关 */
    $('rangePanel').hidden = (tab === 'security' || tab === 'license' || tab === 'users');
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
    if (err.kind === 'network') return t('err.network');
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
  function loadOverview() {
    renderPanel('overview', '', '');
    requestJson('/api/admin/accounting/overview' + rangeQuery()).then(function (data) {
      $('overviewRangeNote').textContent =
        t('overview.rangeNote', { from: fmtDateTime(data.from), to: fmtDateTime(data.to), count: data.totalOrderCount });
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
        { label: t('th.currency') }, { label: t('th.orderCount'), num: true }, { label: t('th.paidOrders'), num: true },
        { label: t('th.gmv'), num: true }, { label: t('th.received'), num: true }, { label: t('th.refunded'), num: true },
        { label: t('th.net'), num: true }, { label: t('th.avgOrder'), num: true }
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
      rows += '<tr>'
        + '<td><code class="mono">' + escapeHtml(l.licenseKey) + '</code></td>'
        + '<td>' + licenseStatusBadge(l.status) + '</td>'
        + '<td>' + none(l.customerEmail) + '</td>'
        + '<td><code class="mono">' + none(l.machineCode) + '</code></td>'
        + '<td>' + escapeHtml(fmtDateTime(l.expiresAt)) + '</td>'
        + '<td><button class="btn btn--ghost btn--sm" type="button" data-lic="'
        + escapeHtml(l.licenseKey) + '">' + t('lic.action') + '</button></td>' 
        + '</tr>';
    }
    $('licTableWrap').innerHTML = table([
      { label: t('th.licenseKey') }, { label: t('th.status') }, { label: t('th.customerEmail') },
      { label: t('th.machineCode') }, { label: t('th.expiresAt') }, { label: t('th.action') }
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
      showError('licActionError', t('lic.errRevokedTerminal'));
      return;
    }
    // 作废不可逆，故前端强制填原因（端点侧 reason 仍可选，以兼容既有直调方）
    if (type === 'revoke' && !reason) {
      showError('licActionError', t('lic.errRevokeNeedsReason'));
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
        a.download = 'users.csv';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
      });
    }).catch(function (err) {
      btn.disabled = false;
      if (err && (err.status === 401 || err.status === 403)) { handleAuthFailure(err); return; }
      showError('userError', err && err.message ? err.message : t('err.exportFailed'));
    });
  }

  function loadUsers() {
    renderPanel('user', '', '');
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
      rows += '<tr>'
        + '<td>' + escapeHtml(u.email) + (self ? ' <span class="badge badge--muted">' + t('badge.self') + '</span>' : '') + '</td>'
        + '<td>' + roleSelectHtml(u, self) + '</td>'
        + '<td>' + roleBadge(u.status) + '</td>'
        + '<td>' + (u.mfaEnabled
            ? '<span class="badge badge--ok">' + t('badge.on') + '</span>'
            : '<span class="badge badge--muted">' + t('badge.off') + '</span>') + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(u.createdAt)) + '</td>'
        + '<td>' + escapeHtml(fmtDateTime(u.lastLoginAt)) + '</td>'
        + '<td><div class="row-actions">' + detailButtonHtml(u) + statusButtonHtml(u, self)
          + resetButtonHtml(u, self) + '</div></td>'
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

  /* ---- 敏感动作二次确认（P2 step-up） ---- */

  var ACTION_RESET_PASSWORD = 'ADMIN_RESET_USER_PASSWORD';
  var ACTION_CHANGE_ROLE = 'CHANGE_USER_ROLE';
  var ACTION_CHANGE_STATUS = 'CHANGE_USER_STATUS';

  /** 待确认的敏感动作（仅弹层打开期间存在）；确认令牌只随单次请求发出，不落任何存储 */
  var stepUpPending = null;

  function stepUpActionLabel(action) {
    if (action === ACTION_RESET_PASSWORD) { return t('stepUp.label.resetPassword'); }
    if (action === ACTION_CHANGE_ROLE) { return t('stepUp.label.changeRole'); }
    if (action === ACTION_CHANGE_STATUS) { return t('stepUp.label.changeStatus'); }
    return action;
  }

  function stepUpHeaders(token) {
    return token ? { 'X-Step-Up-Token': token } : null;
  }

  function isStepUpError(err) {
    return !!err && (err.code === 'MFA_STEP_UP_REQUIRED' || err.code === 'MFA_STEP_UP_INVALID');
  }

  /**
   * 敏感动作统一入口：操作者已开启 MFA 时先弹二次确认，验证通过后才执行 onConfirmed(token)。
   * 未开启 MFA 的操作者直接执行（服务端口径一致：无第二因子可验证）。
   */
  function withStepUp(action, onConfirmed) {
    if (!state.meMfa) { onConfirmed(null); return; }
    stepUpPending = { action: action, onConfirmed: onConfirmed };
    $('stepUpAction').textContent = t('stepUp.actionPrefix', { action: stepUpActionLabel(action) });
    $('stepUpCode').value = '';
    hideError('stepUpError');
    $('stepUpNote').hidden = true;
    $('stepUpModal').hidden = false;
    $('stepUpCode').focus();
  }

  function closeStepUpModal() {
    stepUpPending = null;
    $('stepUpModal').hidden = true;
    $('stepUpCode').value = '';
    hideError('stepUpError');
    $('stepUpNote').hidden = true;
  }

  /** 校验动态码换确认令牌，随后立刻发起原动作（令牌只在这一条请求里使用）。 */
  function submitStepUp() {
    if (!stepUpPending) { closeStepUpModal(); return; }
    var code = $('stepUpCode').value.trim();
    if (!code) { showError('stepUpError', t('err.codeRequired')); return; }
    var pending = stepUpPending;
    $('stepUpSubmit').disabled = true;
    apiPost('/api/admin/mfa/step-up/verify', { action: pending.action, code: code }, true)
      .then(function (data) {
        $('stepUpSubmit').disabled = false;
        closeStepUpModal();
        pending.onConfirmed(data && data.stepUpToken);
      })
      .catch(function (err) {
        $('stepUpSubmit').disabled = false;
        showError('stepUpError', err && err.message ? err.message : t('err.stepUpFailed'));
      });
  }

  /** 认证器不可用时的邮箱兜底：让服务端发一枚验证码（复用第二因子发送通道）。 */
  function sendStepUpEmailCode() {
    if (!stepUpPending) { return; }
    var btn = $('stepUpSendBtn');
    btn.disabled = true;
    apiPost('/api/admin/mfa/step-up/challenge', { action: stepUpPending.action }, true)
      .then(function () {
        btn.disabled = false;
        $('stepUpNote').textContent = t('stepUp.noteSent');
        $('stepUpNote').hidden = false;
      })
      .catch(function (err) {
        btn.disabled = false;
        showError('stepUpError', err && err.message ? err.message : t('err.sendFailed'));
      });
  }

  function changeUserRole(userId, role) {
    var label = role === 'ADMIN' ? t('role.admin') : t('role.user');
    if (!window.confirm(t('confirm.changeRole', { role: label }))) {
      reloadUsersAfterAction();
      return;
    }
    hideError('userError');
    var send = function (token) {
      apiPatch('/api/admin/users/' + encodeURIComponent(userId) + '/role?role=' + encodeURIComponent(role),
          null, true, stepUpHeaders(token))
        .then(function () {
          hideUserResult();
          showUserResult(t('result.roleChanged', { role: label }));
          reloadUsersAfterAction();
        })
        .catch(function (err) {
          if (isStepUpError(err)) { withStepUp(ACTION_CHANGE_ROLE, send); return; }
          var text = errorTextOf(err);
          if (text) { showError('userError', text); }
          reloadUsersAfterAction();
        });
    };
    withStepUp(ACTION_CHANGE_ROLE, send);
  }

  function changeUserStatus(userId, next) {
    var disabling = next === 'DISABLED';
    if (!window.confirm(disabling ? t('confirm.disable') : t('confirm.enable'))) {
      reloadUsersAfterAction();
      return;
    }
    hideError('userError');
    var send = function (token) {
      apiPatch('/api/admin/users/' + encodeURIComponent(userId) + '/status?status=' + encodeURIComponent(next),
          null, true, stepUpHeaders(token))
        .then(function () {
          showUserResult(disabling ? t('result.disabled') : t('result.enabled'));
          reloadUsersAfterAction();
        })
        .catch(function (err) {
          if (isStepUpError(err)) { withStepUp(ACTION_CHANGE_STATUS, send); return; }
          var text = errorTextOf(err);
          if (text) { showError('userError', text); }
          reloadUsersAfterAction();
        });
    };
    withStepUp(ACTION_CHANGE_STATUS, send);
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
    var send = function (token) {
      $('resetPwdSubmit').disabled = true;
      apiPost('/api/admin/users/' + encodeURIComponent(userId) + '/password/reset',
          { newPassword: pwd }, true, stepUpHeaders(token))
        .then(function () {
          $('resetPwdSubmit').disabled = false;
          closeResetModal();
          /* 只提示成功：密码不回显、不复制、不留存 */
          showUserResult(t('result.resetDone', { email: email }));
          reloadUsersAfterAction();
        })
        .catch(function (err) {
          $('resetPwdSubmit').disabled = false;
          if (isStepUpError(err)) { withStepUp(ACTION_RESET_PASSWORD, send); return; }
          showError('resetPwdError', err && err.message ? err.message : t('err.resetFailed'));
        });
    };
    withStepUp(ACTION_RESET_PASSWORD, send);
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

  function openUserDetail(userId) {
    $('userDetailTitle').textContent = t('detail.title');
    hideError('userDetailError');
    $('userDetailBody').hidden = true;
    $('userDetailLicensesBox').hidden = true;
    $('userDetailLicensesWrap').innerHTML = '';
    $('userDetailModal').hidden = false;

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

  /** 复用既有 GET /api/admin/licenses?customerEmail=：完整密钥、不含 signedToken */
  function loadUserDetailLicenses() {
    var email = $('userDetailLicensesBtn').getAttribute('data-email');
    if (!email) { return; }
    $('userDetailLicensesBox').hidden = false;
    $('userDetailLicensesEmpty').hidden = true;
    requestJson('/api/admin/licenses?customerEmail=' + encodeURIComponent(email))
      .then(function (list) {
        var items = Array.isArray(list) ? list : [];
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
      var code = $('mfaLoginCode').value.trim();
      if (!/^\d{6}$/.test(code)) { showError('mfaLoginError', t('err.mfaSixDigits')); return; }
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

    /* 敏感动作二次确认（P2 step-up）：确认 / 取消 / 邮箱兜底发码 */
    $('stepUpSubmit').addEventListener('click', submitStepUp);
    $('stepUpCancel').addEventListener('click', closeStepUpModal);
    $('stepUpSendBtn').addEventListener('click', sendStepUpEmailCode);

    /* 用户详情弹层：关闭 / 查看许可证（复用既有许可证查询） */
    $('userDetailClose').addEventListener('click', closeUserDetail);
    $('userDetailLicensesBtn').addEventListener('click', loadUserDetailLicenses);

    /* 本人改密（P1） */
    $('selfPwdSubmit').addEventListener('click', submitSelfPasswordChange);

    /* 找回入口（P1）：跳账号页的邮箱验证码找回流程，不新增后端 API */
    $('gotoAccountReset').addEventListener('click', function () {
      window.location.href = '/account/?mode=reset';
    });
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
      loadMe().then(function () { loadTab(state.tab); });
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
