package com.billing.license.service.notification;

import com.billing.license.config.AccountProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境账号邮件链路启动自检（plan-7.0 账户基础功能 / P2 · T05，Q5 收口）。
 *
 * <p><b>为什么是启动时打日志而不是健康检查端点</b>：验证码「只写日志不发邮件」在联调期是
 * 合理配置，但对生产是<b>上线阻断项</b>——用户永远收不到验证码，而服务本身完全正常，
 * 存活探针不会红。把它做成启动自检，是为了在运维最可能看日志的时机给出最直白的指引。
 *
 * <p>仅 {@code prod} profile 生效：测试 / 联调环境开 {@code code-log-only} 是有意为之，
 * 不应被警告刷屏。两项检查互不排斥，都命中就都报。
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class AccountNotificationStartupCheck implements ApplicationRunner {

    private final AccountProperties properties;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Override
    public void run(ApplicationArguments args) {
        if (properties.isCodeLogOnly()) {
            log.error("【上线阻断】account.code-log-only=true：验证码只写日志、不发送邮件，"
                + "注册 / 找回密码 / 二次因子邮箱兜底全部不可用。"
                + "生产环境必须配置真实 SMTP 并将该开关置为 false 后重启。");
        }
        if (isBlank(mailHost) || isBlank(mailUsername)) {
            log.warn("【告警】SMTP 未完整配置（spring.mail.host / spring.mail.username 缺失）："
                + "邮箱验证码与密码变更安全提醒将静默跳过——找回密码与代重置通知均不可达。"
                + "请确认这是预期行为；配置方法见 docs/上线准备工作.md。");
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
