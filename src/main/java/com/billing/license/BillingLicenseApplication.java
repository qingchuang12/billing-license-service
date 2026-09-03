package com.billing.license;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
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
     * 启动完成后打印 Swagger / OpenAPI 文档访问地址。
     *
     * @param env Spring 环境变量，用于获取服务端口与上下文路径
     * @return CommandLineRunner
     */
    @Bean
    public CommandLineRunner swaggerLogRunner(Environment env) {
        return args -> {
            String port = env.getProperty("server.port", "8080");
            String contextPath = env.getProperty("server.servlet.context-path", "");
            String baseUrl = "http://localhost:" + port + contextPath;

            log.info("========================================");
            log.info("  Swagger UI:      {}/swagger-ui.html", baseUrl);
            log.info("  OpenAPI JSON:    {}/v3/api-docs", baseUrl);
            log.info("========================================");
        };
    }
}
