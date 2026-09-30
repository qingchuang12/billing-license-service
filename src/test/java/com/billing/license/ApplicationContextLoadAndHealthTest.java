package com.billing.license;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M4 上线前验证闸门：Spring 上下文加载 + Actuator 健康 Bean 装配冒烟测试。
 * 使用 test profile（内存 H2，无需 Docker/Postgres）使上下文可在本地启动。
 *
 * 说明：本环境无法解析 Spring Boot 4.0.6 的 actuator 测试类（镜像源阻断下载），
 * 故此处不 import actuator 类，仅通过 Bean 名称确认 HealthEndpoint 已装配、
 * 且整个应用上下文能成功启动（Bean 装配/JPA 实体映射/安全链均通过）。
 * 若本测试通过，即代表应用可完成启动期装配，可作为上线前验证闸门。
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextLoadAndHealthTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertNotNull(context, "Spring 应用上下文应成功加载");
    }

    @Test
    void actuatorHealthEndpoint_shouldBeWired() {
        // 不 import actuator 类，仅按 Bean 名称确认健康检查已装配
        assertTrue(context.containsBean("healthEndpoint"),
                "Actuator HealthEndpoint 应已装配（健康检查可用）");
    }

    /**
     * F6（plan-1.0 审计）：JavaMail 默认**无超时**，SMTP 端不应答时 @Async 发送线程会被无限期挂住，
     * 占死线程池 worker 且日志里只留「发送…邮件」一行、无成败结论。
     * 这里断言三个超时确实绑定到了 JavaMailSender——只验「存在且在合理区间」，不锁死具体数值，
     * 因为默认值允许被 MAIL_*_TIMEOUT_MS 环境变量覆盖（运维调参不应打红测试）。
     */
    @Test
    void mailSmtpTimeouts_shouldBeBound() {
        JavaMailSender sender = context.getBean(JavaMailSender.class);
        assertInstanceOf(JavaMailSenderImpl.class, sender, "应装配 JavaMailSenderImpl（可读取 JavaMail 属性）");

        Properties props = ((JavaMailSenderImpl) sender).getJavaMailProperties();
        for (String key : new String[]{
                "mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout"}) {
            String raw = props.getProperty(key);
            assertNotNull(raw, key + " 必须显式配置，否则 SMTP 挂起时发送线程无限期阻塞");
            long ms = Long.parseLong(raw.trim());
            assertTrue(ms >= 1_000 && ms <= 60_000,
                    key + " 应落在 1~60s 的合理区间，实际=" + ms);
        }
    }
}
