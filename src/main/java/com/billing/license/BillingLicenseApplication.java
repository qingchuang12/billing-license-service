package com.billing.license;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 计费与 License 管理系统启动类
 * 
 * 主要功能：
 * 1. 订单管理 - 创建、查询订单
 * 2. 支付集成 - 支持支付宝、微信、Stripe、PayPal、Paddle 等多种支付方式
 * 3. License 签发 - 基于 JWT 的软件许可证生成与验证
 * 4. 兑换码管理 - 生成和兑换激活码
 */
@Slf4j
@EnableScheduling
@EnableAsync
@SpringBootApplication
public class BillingLicenseApplication {
    
    /**
     * 应用程序入口
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(BillingLicenseApplication.class, args);
    }

    /**
     * 启动完成后打印管理入口访问地址。
     *
     * <p>K11/K12（2026-09-18）：端口回退值由 8080 改为 8000（与 `server.port` 一致——原值会让启动日志里的
     * 地址与实际监听端口不符）；生产 profile 已关闭 springdoc，文档地址不再打印以免误导运维。
     *
     * <p>2026-09-22：打印管理统计页（{@code /admin/}，Web 查看页）地址而非接口地址——页面自身
     * 无数据（静态资产 GET 放行），数据接口仍需 X-API-Key + ROLE_ADMIN。该地址不依赖 springdoc，
     * 生产 profile 下同样打印。
     *
     * @param env Spring 环境变量，用于读取文档开关与端口
     * @return CommandLineRunner
     */
    @Bean
    public CommandLineRunner adminEntryLogRunner(Environment env) {
        return args -> {
            String port = env.getProperty("server.port", "8000");
            String contextPath = env.getProperty("server.servlet.context-path", "");
            String baseUrl = "http://localhost:" + port + contextPath;

            log.info("========================================");
            if (env.getProperty("springdoc.api-docs.enabled", Boolean.class, true)) {
                log.info("  Swagger UI:      {}/swagger-ui.html", baseUrl);
                log.info("  OpenAPI JSON:    {}/v3/api-docs", baseUrl);
            }
            log.info("  Admin console:   {}/admin/", baseUrl);
            log.info("========================================");
        };
    }
}
