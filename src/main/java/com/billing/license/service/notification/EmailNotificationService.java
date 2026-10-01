package com.billing.license.service.notification;

import com.billing.license.entity.Currency;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * 邮件通知服务 - 发送支付成功/失败、License 签发等通知
 *
 * <p>F7（plan-1.0）：全部邮件按收件人 locale 出中/英双语。约定：
 * <ul>
 *   <li>locale 以 {@code zh} 开头 → 中文；其余 / {@code null} / 空 → <b>英文</b>（全球默认，回落 en）；</li>
 *   <li>locale 来源：发货/退款类走 {@code CheckoutSession.locale}（回调请求的 Accept-Language 属渠道服务器，非买家），
 *       账号/安全类走买家请求的 {@code Accept-Language}；取不到买家 locale 的（管理员代退款/代重置）按 en。</li>
 * </ul>
 * 沿用内联 StringBuilder 模板风格（不引入 {@code MessageSource}）：仅文案按 locale 切换，HTML 结构与转义不变。
 */
@Service
public class EmailNotificationService {
    
    private static final Logger logger = LoggerFactory.getLogger(EmailNotificationService.class);
    
    private final JavaMailSender mailSender;
    
    @Value("${spring.mail.host:}")
    private String mailHost;
    
    @Value("${spring.mail.username:}")
    private String mailUsername;
    
    @Value("${spring.mail.from-address:}")
    private String fromAddress;
    
    @Value("${billing.support-email:service@ywhome.top}")
    private String supportEmail;

    /** 服务对外基址：License 签发邮件里给出「账户页（管理/解绑设备）」入口（plan-1.0 / S3） */
    @Value("${app.base-url:}")
    private String baseUrl;
    
    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 该 locale 是否应出英文：仅当明确以 {@code zh} 开头才出中文，其余（含 null/空）一律英文。
     * <p>公开供调用方（如 {@code WebhookController}）按同一口径挑选本地化字面量，避免两套判断发散。
     */
    public static boolean preferEn(String locale) {
        return locale == null || locale.isBlank() || !locale.trim().toLowerCase().startsWith("zh");
    }

    /** 按 locale 选中/英文文案（模板内部用；{@code en} 已由 {@link #preferEn} 预先算好）。 */
    private static String pick(boolean en, String zh, String enText) {
        return en ? enText : zh;
    }
    
    /**
     * 发送支付成功通知
     */
    @Async
    public void sendPaymentSuccessEmail(String to, String orderNo, String productName, double amount,
                                        Currency currency, String locale) {
        logger.info("发送支付成功邮件：to={}, orderNo={}", to, orderNo);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "service@ywhome.top");
            helper.setTo(to);
            helper.setSubject(pick(en, "支付成功 - 订单 ", "Payment Successful - Order ") + esc(orderNo));
            
            String content = buildPaymentSuccessTemplate(orderNo, productName, amount,
                currency != null ? currency.code() : null, en);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("支付成功邮件发送成功：to={}", to);
            
        } catch (Exception e) {
            logger.error("发送支付成功邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送支付失败通知
     */
    @Async
    public void sendPaymentFailureEmail(String to, String orderNo, String reason, String locale) {
        logger.info("发送支付失败邮件：to={}, orderNo={}", to, orderNo);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "支付失败 - 订单 ", "Payment Failed - Order ") + esc(orderNo));
            
            String content = buildPaymentFailureTemplate(orderNo, reason, en);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("支付失败邮件发送成功：to={}", to);
            
        } catch (Exception e) {
            logger.error("发送支付失败邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送 License 签发通知
     *
     * @param expiryDate 有效期展示文本；为 {@code null} 时由模板按 locale 出「永久有效 / No expiration」
     */
    @Async
    public void sendLicenseIssuedEmail(String to, String licenseKey, String productName, String expiryDate,
                                       String locale) {
        logger.info("发送 License 签发邮件：to={}", to);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "License 已签发 - ", "Your License Has Been Issued - ") + esc(productName));
            
            String content = buildLicenseIssuedTemplate(licenseKey, productName, expiryDate, en);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("License 签发邮件发送成功：to={}", to);
            
        } catch (Exception e) {
            logger.error("发送 License 签发邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送兑换码通知
     */
    @Async
    public void sendRedeemCodeEmail(String to, String redeemCode, String productName, String expiryDate,
                                    String locale) {
        logger.info("发送兑换码邮件：to={}", to);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "您的兑换码 - ", "Your Redeem Code - ") + esc(productName));
            
            String content = buildRedeemCodeTemplate(redeemCode, productName, expiryDate, en);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("兑换码邮件发送成功：to={}", to);
            
        } catch (Exception e) {
            logger.error("发送兑换码邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送退款处理通知（M5 修正：独立的退款文案，不再复用「支付失败」模板）
     */
    @Async
    public void sendRefundProcessedEmail(String to, String orderNo, String detail, String locale) {
        logger.info("发送退款通知：to={}, orderNo={}", to, orderNo);

        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }

        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "退款已处理 - 订单 ", "Refund Processed - Order ") + esc(orderNo));

            String content = buildRefundProcessedTemplate(orderNo, detail, en);
            helper.setText(content, true);

            mailSender.send(message);
            logger.info("退款通知邮件发送成功：to={}", to);

        } catch (Exception e) {
            logger.error("发送退款通知邮件失败：to={}", to, e);
        }
    }

    /**
     * 发送邮箱验证码（plan v2.10 / A3）。
     *
     * <p>与其余通知一致：SMTP 未配置时<b>静默跳过</b>（仅 warn）。
     * 这意味着「注册 / 找回密码」会因收不到验证码而不可用，且服务端不报错 ——
     * 联调阶段请开启 {@code account.code-log-only=true} 把验证码输出到日志，
     * 生产上线前必须配置真实 SMTP 并实测可达（否则用户永远收不到码）。
     */
    @Async
    public void sendVerificationCodeEmail(String to, String code, String purpose, int ttlMinutes, String locale) {
        logger.info("发送验证码邮件：to={}, purpose={}", to, purpose);

        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送验证码邮件（可开启 account.code-log-only 走日志联调）");
            return;
        }

        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "您的验证码 - ", "Your Verification Code - ") + purposeLabel(purpose, en));

            helper.setText(buildVerificationCodeTemplate(code, purposeLabel(purpose, en), ttlMinutes, en), true);

            mailSender.send(message);
            logger.info("验证码邮件发送成功：to={}", to);
        } catch (Exception e) {
            logger.error("发送验证码邮件失败：to={}", to, e);
        }
    }

    /**
     * 发送密码变更安全提醒（plan-7.0 账户基础功能 / P1）。
     *
     * <p><b>只告知「发生过变更」这一事实，绝不包含任何密码</b>——新旧密码都不进正文与主题。
     * 场景 {@code scenario}：SELF_CHANGE=本人已登录改密、SELF_RESET=邮箱验证码自助找回、
     * ADMIN_RESET=管理员代重置；仅用于文案措辞，不影响安全语义。
     *
     * <p><b>旁路而非事务前提</b>：与其余通知一致，未配置 SMTP 时静默跳过、发送异常仅记日志，
     * 绝不因通知失败回滚已完成的密码变更。未识别的 scenario 按通用文案处理（防御式，正常不会走到）。
     */
    @Async
    public void sendPasswordChangedEmail(String to, String scenario, String locale) {
        logger.info("发送密码变更提醒：to={}, scenario={}", to, scenario);

        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送密码变更提醒");
            return;
        }

        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "您的账号密码已变更 - 安全提醒", "Your Account Password Was Changed - Security Notice"));

            helper.setText(buildPasswordChangedTemplate(scenario, en), true);

            mailSender.send(message);
            logger.info("密码变更提醒发送成功：to={}", to);
        } catch (Exception e) {
            logger.error("发送密码变更提醒失败：to={}", to, e);
        }
    }

    /**
     * 账号锁定提醒（SEC-5）：连续登录失败触发锁定时，向<b>被锁账号邮箱</b>发旁路提醒。
     *
     * <p>锁定本身即「有人正在撞库」的强信号，此前仅写服务端日志、账号主人毫不知情。
     * 与密码变更提醒同款：{@code @Async} 旁路、内部吞异常，<b>发送失败绝不回滚锁定主流程</b>
     * （锁定是安全动作，不能因为邮件发不出去就不锁）。
     *
     * @param to          被锁账号邮箱
     * @param lockMinutes 本次锁定时长（分钟），用于文案告知解锁等待时间
     */
    @Async
    public void sendAccountLockedEmail(String to, int lockMinutes, String locale) {
        logger.info("发送账号锁定提醒：to={}, lockMinutes={}", to, lockMinutes);

        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送账号锁定提醒");
            return;
        }

        try {
            boolean en = preferEn(locale);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject(pick(en, "您的账号已被临时锁定 - 安全提醒", "Your Account Has Been Temporarily Locked - Security Notice"));

            helper.setText(buildAccountLockedTemplate(lockMinutes, en), true);

            mailSender.send(message);
            logger.info("账号锁定提醒发送成功：to={}", to);
        } catch (Exception e) {
            logger.error("发送账号锁定提醒失败：to={}", to, e);
        }
    }

    /** 用途枚举名 → 说明（仅用于邮件文案，按 locale 出中/英） */
    private static String purposeLabel(String purpose, boolean en) {
        if ("RESET_PASSWORD".equalsIgnoreCase(purpose)) {
            return en ? "Password Reset" : "找回密码";
        }
        return en ? "Registration" : "注册验证";
    }

    /**
     * 检查邮件服务是否已配置
     */
    private boolean isEmailConfigured() {
        return mailHost != null && !mailHost.isEmpty() && 
               mailUsername != null && !mailUsername.isEmpty();
    }

    /**
     * i5：对邮件模板中的动态内容进行 HTML 转义，防止 orderNo / reason /
     * licenseKey 等用户可控字段注入 HTML（钓鱼/脚本执行）。null 安全。
     */
    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }
    
    // ==================== 邮件模板 ====================
    
    private String buildPaymentSuccessTemplate(String orderNo, String productName, double amount, String currency,
                                               boolean en) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#4CAF50;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "支付成功", "Payment Successful")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(pick(en, "您的订单已成功支付，感谢您的购买！",
            "Your order has been paid successfully. Thank you for your purchase!")).append("</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>").append(pick(en, "订单号：", "Order No.: ")).append("</strong>").append(esc(orderNo)).append("</p>");
        html.append("<p><strong>").append(pick(en, "产品名称：", "Product: ")).append("</strong>").append(esc(productName)).append("</p>");
        html.append("<p><strong>").append(pick(en, "支付金额：", "Amount Paid: ")).append("</strong>").append(String.format("%.2f %s", amount, esc(currency))).append("</p>");
        html.append("</div>");
        html.append("<p>").append(pick(en, "如果购买的是激活码，您将在另一封邮件中收到兑换码或 License。",
            "If you purchased an activation code, you will receive the redeem code or License in a separate email.")).append("</p>");
        html.append("<p>").append(pick(en, "如有任何问题，请联系我们的客服：", "For any questions, please contact our support: "))
            .append(esc(supportEmail)).append("</p>");
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
    
    private String buildPaymentFailureTemplate(String orderNo, String reason, boolean en) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#f44336;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "支付失败", "Payment Failed")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(pick(en, "很抱歉，您的订单支付未能成功。",
            "We're sorry, but your order payment was not successful.")).append("</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>").append(pick(en, "订单号：", "Order No.: ")).append("</strong>").append(esc(orderNo)).append("</p>");
        html.append("<p><strong>").append(pick(en, "失败原因：", "Reason: ")).append("</strong>").append(esc(reason)).append("</p>");
        html.append("</div>");
        html.append("<p>").append(pick(en, "您可以：", "You can:")).append("</p><ul>");
        html.append("<li>").append(pick(en, "检查您的支付方式是否有足够的余额",
            "Check that your payment method has sufficient funds")).append("</li>");
        html.append("<li>").append(pick(en, "尝试使用其他支付方式重新支付",
            "Try paying again with another payment method")).append("</li>");
        html.append("<li>").append(pick(en, "联系客服寻求帮助：", "Contact support for help: "))
            .append(esc(supportEmail)).append("</li>");
        html.append("</ul></div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
    
    private String buildLicenseIssuedTemplate(String licenseKey, String productName, String expiryDate, boolean en) {
        String expiry = expiryDate != null ? esc(expiryDate) : pick(en, "永久有效", "No expiration");
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#2196F3;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".license-box{background:white;padding:15px;margin:15px 0;border-radius:5px;border-left:4px solid #2196F3;}");
        html.append(".license-key{font-family:monospace;font-size:18px;background:#f0f0f0;padding:10px;word-break:break-all;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "License 已签发", "License Issued")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(pick(en, "您的产品 License 已生成，请妥善保存以下信息：",
            "Your product License has been generated. Please keep the following information safe:")).append("</p>");
        html.append("<div class='license-box'>");
        html.append("<p><strong>").append(pick(en, "产品名称：", "Product: ")).append("</strong>").append(esc(productName)).append("</p>");
        html.append("<p><strong>License Key</strong></p>");
        html.append("<div class='license-key'>").append(esc(licenseKey)).append("</div>");
        html.append("<p><strong>").append(pick(en, "有效期至：", "Valid Until: ")).append("</strong>").append(expiry).append("</p>");
        html.append("</div>");
        html.append("<p><strong>").append(pick(en, "使用说明：", "Instructions:")).append("</strong></p><ol>");
        html.append("<li>").append(pick(en, "下载并安装客户端软件", "Download and install the client software")).append("</li>");
        html.append("<li>").append(pick(en, "在本机收银台页面完成付款后回到软件，会自动激活，无需手填",
            "After paying on the checkout page of this device, return to the software — it activates automatically, no manual entry needed")).append("</li>");
        html.append("<li>").append(pick(en, "若软件未自动激活（例如在别的设备或网页上下单），在激活界面输入上述 License Key",
            "If the software doesn't activate automatically (e.g. you ordered on another device or on the web), enter the License Key above in the activation screen")).append("</li>");
        html.append("</ol>");
        html.append("<p>").append(pick(en, "注意：此 License 已绑定您的设备，无法在其他设备上使用。",
            "Note: This License is bound to your device and cannot be used on other devices.")).append("</p>");
        // S3（plan-1.0）：给出自助解绑入口——用户换机后最常见的卡点是「已绑定其他机器」，
        // 没有这一条只能靠客服人工处置（管理端解绑）。
        String accountUrl = accountPageUrl();
        if (accountUrl != null) {
            html.append("<p>").append(pick(en, "需要换机或释放当前设备绑定？登录账户页自助解绑：",
                "Need to switch devices or release the current binding? Sign in to your account page to unbind: "))
                .append("<a href='").append(esc(accountUrl)).append("'>").append(esc(accountUrl)).append("</a></p>");
        }
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
    
    /**
     * 账户页绝对地址（用于邮件里的自助解绑入口）；未配置 {@code app.base-url} 时返回 null，
     * 调用方据此省略整行——不拼出「/account/」这种点开即 404 的相对链接。
     */
    private String accountPageUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/account/";
    }

    private String buildRefundProcessedTemplate(String orderNo, String detail, boolean en) {        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#2196F3;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "退款已处理", "Refund Processed")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(pick(en, "您申请的退款已处理完成。",
            "The refund you requested has been processed.")).append("</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>").append(pick(en, "订单号：", "Order No.: ")).append("</strong>").append(esc(orderNo)).append("</p>");
        html.append("<p><strong>").append(pick(en, "说明：", "Details: ")).append("</strong>").append(esc(detail)).append("</p>");
        html.append("</div>");
        html.append("<p>").append(pick(en, "退款将原路返回，具体到账时间以支付渠道为准（通常 1-7 个工作日）。",
            "The refund will be returned via the original payment method; arrival time depends on the payment channel (typically 1-7 business days).")).append("</p>");
        html.append("<p>").append(pick(en, "如有任何问题，请联系我们的客服：", "For any questions, please contact our support: "))
            .append(esc(supportEmail)).append("</p>");
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String buildRedeemCodeTemplate(String redeemCode, String productName, String expiryDate, boolean en) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#FF9800;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".code-box{background:white;padding:15px;margin:15px 0;border-radius:5px;border-left:4px solid #FF9800;}");
        html.append(".redeem-code{font-family:monospace;font-size:24px;background:#f0f0f0;padding:15px;text-align:center;letter-spacing:2px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "您的兑换码", "Your Redeem Code")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(pick(en, "感谢您购买我们的产品，以下是您的兑换码：",
            "Thank you for purchasing our product. Here is your redeem code:")).append("</p>");
        html.append("<div class='code-box'>");
        html.append("<p><strong>").append(pick(en, "产品名称：", "Product: ")).append("</strong>").append(esc(productName)).append("</p>");
        html.append("<p><strong>").append(pick(en, "兑换码：", "Redeem Code:")).append("</strong></p>");
        html.append("<div class='redeem-code'>").append(esc(redeemCode)).append("</div>");
        html.append("<p><strong>").append(pick(en, "有效期至：", "Valid Until: ")).append("</strong>").append(esc(expiryDate)).append("</p>");
        html.append("</div>");
        html.append("<p><strong>").append(pick(en, "使用步骤：", "Steps:")).append("</strong></p><ol>");
        html.append("<li>").append(pick(en, "访问我们的官网激活页面", "Visit the activation page on our website")).append("</li>");
        html.append("<li>").append(pick(en, "输入上述兑换码", "Enter the redeem code above")).append("</li>");
        html.append("<li>").append(pick(en, "点击\"激活\"按钮完成兑换", "Click \"Activate\" to complete redemption")).append("</li>");
        html.append("</ol>");
        html.append("<p>").append(pick(en, "注意：每个兑换码只能使用一次，请妥善保管。",
            "Note: Each redeem code can only be used once. Please keep it safe.")).append("</p>");
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String buildVerificationCodeTemplate(String code, String purposeLabel, int ttlMinutes, boolean en) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#607D8B;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".code-box{background:white;padding:15px;margin:15px 0;border-radius:5px;border-left:4px solid #607D8B;}");
        html.append(".verify-code{font-family:monospace;font-size:28px;background:#f0f0f0;padding:15px;text-align:center;letter-spacing:4px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(esc(purposeLabel)).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "您好！", "Hello,")).append("</p>");
        html.append("<p>").append(pick(en, "您正在进行<strong>", "You are performing <strong>"))
            .append(esc(purposeLabel))
            .append(pick(en, "</strong>操作，验证码如下：</p>", "</strong>. Your verification code is:</p>")).append("</p>");
        html.append("<div class='code-box'><div class='verify-code'>").append(esc(code)).append("</div></div>");
        html.append("<p>").append(pick(en, "验证码 <strong>", "The code is valid for <strong>"))
            .append(ttlMinutes)
            .append(pick(en, " 分钟</strong>内有效，且仅可使用一次。</p>", " minutes</strong> and can be used only once.</p>")).append("</p>");
        html.append("<p>").append(pick(en, "如果这不是您本人的操作，请忽略本邮件，您的账号仍然是安全的。",
            "If this wasn't you, please ignore this email; your account remains secure.")).append("</p>");
        html.append("<p>").append(pick(en, "如有任何问题，请联系我们的客服：", "For any questions, please contact our support: "))
            .append(esc(supportEmail)).append("</p>");
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    /** 密码变更提醒模板：按变更来源给出对应措辞；正文只有事实，没有任何密码。 */
    private String buildAccountLockedTemplate(int lockMinutes, boolean en) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#f44336;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "账号临时锁定", "Temporary Account Lock")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(pick(en, "您的账号因<b>连续多次登录失败</b>已被临时锁定。这通常意味着有人正在尝试用错误密码登录您的账号。",
            "Your account has been temporarily locked due to <b>repeated failed sign-in attempts</b>. This usually means someone is trying to log in to your account with the wrong password.")).append("</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>").append(pick(en, "锁定时间：", "Locked At: ")).append("</strong>").append(java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>");
        html.append("<p><strong>").append(pick(en, "预计解锁：</strong>约 ", "Expected Unlock: </strong>auto-unlocks in about "))
            .append(lockMinutes).append(pick(en, " 分钟后自动解锁", " minutes")).append("</p>");
        html.append("</div>");
        html.append("<p>").append(pick(en, "<strong>如果这是您本人的操作</strong>（例如忘记了密码），请在解锁后通过「邮箱验证码找回」重设密码。",
            "<strong>If this was you</strong> (e.g. you forgot your password), please reset it via \"Email Verification Code Recovery\" after unlocking.")).append("</p>");
        html.append("<p>").append(pick(en, "<strong>如果不是您本人的操作</strong>，说明您的账号可能正被恶意尝试登录，建议解锁后立即修改密码，并联系我们的客服：",
            "<strong>If this wasn't you</strong>, your account may be under a malicious login attempt; we recommend changing your password immediately after unlocking and contacting our support: "))
            .append(esc(supportEmail)).append("</p>");
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }

    private String buildPasswordChangedTemplate(String scenario, boolean en) {
        String action;
        if ("SELF_RESET".equals(scenario)) {
            action = pick(en, "您（或持有该账号邮箱的人）刚通过邮箱验证码完成了密码重置。",
                "You (or someone with access to this email) just completed a password reset via email verification code.");
        } else if ("ADMIN_RESET".equals(scenario)) {
            action = pick(en, "平台管理员已为您的账号重置了密码；新密码由管理员通过其他渠道告知您，登录后请尽快自行修改。",
                "A platform administrator has reset your account password. The new password was provided to you through another channel; please change it promptly after signing in.");
        } else {
            action = pick(en, "您的账号密码刚已完成修改。", "Your account password was just changed.");
        }
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#f44336;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>").append(pick(en, "密码变更提醒", "Password Change Notice")).append("</h1></div>");
        html.append("<div class='content'><p>").append(pick(en, "尊敬的客户，您好！", "Dear customer,")).append("</p>");
        html.append("<p>").append(esc(action)).append("</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>").append(pick(en, "变更时间：", "Time of Change: ")).append("</strong>").append(java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("</p>");
        html.append("</div>");
        html.append("<p>").append(pick(en, "所有已登录的会话已同时失效，需使用新密码重新登录。",
            "All active sessions have been signed out; please sign in again with the new password.")).append("</p>");
        html.append("<p>").append(pick(en, "<strong>如果这不是您本人的操作</strong>，请立即通过邮箱验证码重新找回密码，并联系我们的客服：",
            "<strong>If this wasn't you</strong>, immediately recover your password via email verification code and contact our support: "))
            .append(esc(supportEmail)).append("</p>");
        html.append("</div><div class='footer'><p>").append(pick(en, "此邮件由系统自动发送，请勿回复。",
            "This email was sent automatically by the system. Please do not reply.")).append("</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
}
