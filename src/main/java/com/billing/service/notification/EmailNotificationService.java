package com.billing.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * 邮件通知服务 - 发送支付成功/失败、License 签发等通知
 */
@Service
public class EmailNotificationService {
    
    private static final Logger logger = LoggerFactory.getLogger(EmailNotificationService.class);
    
    private final JavaMailSender mailSender;
    
    @Value("${spring.mail.host:}")
    private String mailHost;
    
    @Value("${spring.mail.username:}")
    private String mailUsername;
    
    @Value("${spring.mail.from-address:#{null}}")
    private String fromAddress;
    
    @Value("${billing.support-email:support@company.com}")
    private String supportEmail;
    
    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    
    /**
     * 发送支付成功通知
     */
    @Async
    public void sendPaymentSuccessEmail(String to, String orderNo, String productName, double amount, String currency) {
        logger.info("发送支付成功邮件：to={}, orderNo={}", to, orderNo);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject("支付成功 - 订单 " + orderNo);
            
            String content = buildPaymentSuccessTemplate(orderNo, productName, amount, currency);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("支付成功邮件发送成功：to={}", to);
            
        } catch (MessagingException e) {
            logger.error("发送支付成功邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送支付失败通知
     */
    @Async
    public void sendPaymentFailureEmail(String to, String orderNo, String reason) {
        logger.info("发送支付失败邮件：to={}, orderNo={}", to, orderNo);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject("支付失败 - 订单 " + orderNo);
            
            String content = buildPaymentFailureTemplate(orderNo, reason);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("支付失败邮件发送成功：to={}", to);
            
        } catch (MessagingException e) {
            logger.error("发送支付失败邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送 License 签发通知
     */
    @Async
    public void sendLicenseIssuedEmail(String to, String licenseKey, String productName, String expiryDate) {
        logger.info("发送 License 签发邮件：to={}", to);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject("License 已签发 - " + productName);
            
            String content = buildLicenseIssuedTemplate(licenseKey, productName, expiryDate);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("License 签发邮件发送成功：to={}", to);
            
        } catch (MessagingException e) {
            logger.error("发送 License 签发邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 发送兑换码通知
     */
    @Async
    public void sendRedeemCodeEmail(String to, String redeemCode, String productName, String expiryDate) {
        logger.info("发送兑换码邮件：to={}", to);
        
        if (!isEmailConfigured()) {
            logger.warn("邮件服务未配置，跳过发送邮件");
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromAddress != null ? fromAddress : "noreply@billing.com");
            helper.setTo(to);
            helper.setSubject("您的兑换码 - " + productName);
            
            String content = buildRedeemCodeTemplate(redeemCode, productName, expiryDate);
            helper.setText(content, true);
            
            mailSender.send(message);
            logger.info("兑换码邮件发送成功：to={}", to);
            
        } catch (MessagingException e) {
            logger.error("发送兑换码邮件失败：to={}", to, e);
        }
    }
    
    /**
     * 检查邮件服务是否已配置
     */
    private boolean isEmailConfigured() {
        return mailHost != null && !mailHost.isEmpty() && 
               mailUsername != null && !mailUsername.isEmpty();
    }
    
    // ==================== 邮件模板 ====================
    
    private String buildPaymentSuccessTemplate(String orderNo, String productName, double amount, String currency) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#4CAF50;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>✅ 支付成功</h1></div>");
        html.append("<div class='content'><p>尊敬的客户，您好！</p>");
        html.append("<p>您的订单已成功支付，感谢您的购买！</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>订单号：</strong>").append(orderNo).append("</p>");
        html.append("<p><strong>产品名称：</strong>").append(productName).append("</p>");
        html.append("<p><strong>支付金额：</strong>").append(String.format("%.2f %s", amount, currency)).append("</p>");
        html.append("</div>");
        html.append("<p>如果购买的是激活码，您将在另一封邮件中收到兑换码或 License。</p>");
        html.append("<p>如有任何问题，请联系我们的客服：").append(supportEmail).append("</p>");
        html.append("</div><div class='footer'><p>此邮件由系统自动发送，请勿回复。</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
    
    private String buildPaymentFailureTemplate(String orderNo, String reason) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<style>body{font-family:Arial,sans-serif;line-height:1.6;color:#333;}");
        html.append(".container{max-width:600px;margin:0 auto;padding:20px;}");
        html.append(".header{background:#f44336;color:white;padding:20px;text-align:center;}");
        html.append(".content{padding:20px;background:#f9f9f9;}");
        html.append(".order-info{background:white;padding:15px;margin:15px 0;border-radius:5px;}");
        html.append(".footer{text-align:center;padding:20px;color:#666;font-size:12px;}</style>");
        html.append("</head><body><div class='container'>");
        html.append("<div class='header'><h1>❌ 支付失败</h1></div>");
        html.append("<div class='content'><p>尊敬的客户，您好！</p>");
        html.append("<p>很抱歉，您的订单支付未能成功。</p>");
        html.append("<div class='order-info'>");
        html.append("<p><strong>订单号：</strong>").append(orderNo).append("</p>");
        html.append("<p><strong>失败原因：</strong>").append(reason).append("</p>");
        html.append("</div>");
        html.append("<p>您可以：</p><ul>");
        html.append("<li>检查您的支付方式是否有足够的余额</li>");
        html.append("<li>尝试使用其他支付方式重新支付</li>");
        html.append("<li>联系客服寻求帮助：").append(supportEmail).append("</li>");
        html.append("</ul></div><div class='footer'><p>此邮件由系统自动发送，请勿回复。</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
    
    private String buildLicenseIssuedTemplate(String licenseKey, String productName, String expiryDate) {
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
        html.append("<div class='header'><h1>🔑 License 已签发</h1></div>");
        html.append("<div class='content'><p>尊敬的客户，您好！</p>");
        html.append("<p>您的产品 License 已生成，请妥善保存以下信息：</p>");
        html.append("<div class='license-box'>");
        html.append("<p><strong>产品名称：</strong>").append(productName).append("</p>");
        html.append("<p><strong>License Key：</strong></p>");
        html.append("<div class='license-key'>").append(licenseKey).append("</div>");
        html.append("<p><strong>有效期至：</strong>").append(expiryDate).append("</p>");
        html.append("</div>");
        html.append("<p><strong>使用说明：</strong></p><ol>");
        html.append("<li>下载并安装客户端软件</li>");
        html.append("<li>在激活界面输入上述 License Key</li>");
        html.append("<li>客户端将自动验证并激活产品</li>");
        html.append("</ol>");
        html.append("<p>注意：此 License 已绑定您的设备，无法在其他设备上使用。</p>");
        html.append("</div><div class='footer'><p>此邮件由系统自动发送，请勿回复。</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
    
    private String buildRedeemCodeTemplate(String redeemCode, String productName, String expiryDate) {
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
        html.append("<div class='header'><h1>🎁 您的兑换码</h1></div>");
        html.append("<div class='content'><p>尊敬的客户，您好！</p>");
        html.append("<p>感谢您购买我们的产品，以下是您的兑换码：</p>");
        html.append("<div class='code-box'>");
        html.append("<p><strong>产品名称：</strong>").append(productName).append("</p>");
        html.append("<p><strong>兑换码：</strong></p>");
        html.append("<div class='redeem-code'>").append(redeemCode).append("</div>");
        html.append("<p><strong>有效期至：</strong>").append(expiryDate).append("</p>");
        html.append("</div>");
        html.append("<p><strong>使用步骤：</strong></p><ol>");
        html.append("<li>访问我们的官网激活页面</li>");
        html.append("<li>输入上述兑换码</li>");
        html.append("<li>点击"激活"按钮完成兑换</li>");
        html.append("</ol>");
        html.append("<p>注意：每个兑换码只能使用一次，请妥善保管。</p>");
        html.append("</div><div class='footer'><p>此邮件由系统自动发送，请勿回复。</p></div>");
        html.append("</div></body></html>");
        return html.toString();
    }
}
