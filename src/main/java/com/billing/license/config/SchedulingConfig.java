package com.billing.license.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关。默认开启；集成测试/维护窗口可通过
 * {@code billing.scheduling.enabled=false} 关闭，避免后台任务与并发测试抢表锁。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "billing.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
