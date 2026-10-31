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

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Override
    public void run(ApplicationArguments args) {
        java.util.List<String> blockers = new java.util.ArrayList<>();
        if (properties.isCodeLogOnly()) {
            blockers.add("account.code-log-only=true：验证码只写日志、不发送邮件，注册/找回密码/邮箱兜底全部不可用");
        }
        if (isBlank(mailHost) || isBlank(mailUsername) || isBlank(mailPassword)) {
            blockers.add("SMTP 未完整配置（spring.mail.host / username / password 任一为空）："
                + "验证码与密码变更提醒会静默失败");
        }
        if (!blockers.isEmpty()) {
            String message = "生产环境邮件链路未就绪，拒绝启动：" + String.join("；", blockers);
            log.error("【上线阻断】{}", message);
            throw new IllegalStateException(message);
        }
        log.info("生产邮件链路自检通过：host={}, username={}", mailHost, mailUsername);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
