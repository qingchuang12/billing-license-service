package com.billing.license.service.notification;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * F7（plan-1.0）：邮件按 locale 出中/英的路由与生效性断言。
 *
 * <p>不依赖真实 SMTP：注入一个「能造 {@link MimeMessage}、{@code send()} 只捕获不外发」的 mock 发件器，
 * 走完整 {@code sendXxx} 渲染路径，断言主题按 locale 切换。单元测试直调方法（非 Spring 代理），
 * {@code @Async} 不生效、同步执行，便于捕获。
 */
class EmailNotificationServiceI18nTest {

    private EmailNotificationService wired(JavaMailSender sender) {
        EmailNotificationService s = new EmailNotificationService(sender);
        // 让 isEmailConfigured() 通过，否则邮件被静默跳过、不产生可断言的主题
        ReflectionTestUtils.setField(s, "mailHost", "localhost");
        ReflectionTestUtils.setField(s, "mailUsername", "user@example.com");
        ReflectionTestUtils.setField(s, "fromAddress", "no-reply@example.com");
        ReflectionTestUtils.setField(s, "supportEmail", "support@example.com");
        ReflectionTestUtils.setField(s, "baseUrl", "");
        return s;
    }

    private JavaMailSender capturingSender() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        // 离线可用的真实 MimeMessage（默认 Session，不联网）
        when(sender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        return sender;
    }

    private String sentSubject(JavaMailSender sender) throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue().getSubject();
    }

    @Test
    void preferEn_仅zh开头出中文其余含空值出英文() {
        assertFalse(EmailNotificationService.preferEn("zh-CN"));
        assertFalse(EmailNotificationService.preferEn(" zh "));
        assertFalse(EmailNotificationService.preferEn("zh"));
        assertTrue(EmailNotificationService.preferEn("en"));
        assertTrue(EmailNotificationService.preferEn("en-US"));
        assertTrue(EmailNotificationService.preferEn("fr-FR"));
        assertTrue(EmailNotificationService.preferEn(null));
        assertTrue(EmailNotificationService.preferEn("   "));
    }

    @Test
    void 签发邮件主题随locale切换_缺省回落英文() throws Exception {
        JavaMailSender zh = capturingSender();
        wired(zh).sendLicenseIssuedEmail("buyer@example.com", "LIC-ZH", "Some App", null, "zh-CN");
        assertTrue(sentSubject(zh).contains("License 已签发"), "中文 locale 须出中文主题");

        JavaMailSender en = capturingSender();
        wired(en).sendLicenseIssuedEmail("buyer@example.com", "LIC-EN", "Some App", null, "en-US");
        assertTrue(sentSubject(en).contains("Your License Has Been Issued"), "英文 locale 须出英文主题");

        JavaMailSender none = capturingSender();
        wired(none).sendLicenseIssuedEmail("buyer@example.com", "LIC-NULL", "Some App", null, null);
        assertTrue(sentSubject(none).contains("Your License Has Been Issued"), "缺 locale 回落英文");
    }

    @Test
    void 验证码邮件主题随locale切换() throws Exception {
        JavaMailSender zh = capturingSender();
        wired(zh).sendVerificationCodeEmail("buyer@example.com", "123456", "RESET_PASSWORD", 10, "zh-CN");
        assertTrue(sentSubject(zh).contains("您的验证码"), "中文 locale 须出中文主题");

        JavaMailSender en = capturingSender();
        wired(en).sendVerificationCodeEmail("buyer@example.com", "123456", "RESET_PASSWORD", 10, "en");
        assertTrue(sentSubject(en).contains("Your Verification Code"), "英文 locale 须出英文主题");
    }
}
