package com.billing.license;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
}
