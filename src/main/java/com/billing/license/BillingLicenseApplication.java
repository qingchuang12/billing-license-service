package com.billing.license;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 计费与 License 管理系统启动类
 * 
 * 主要功能：
 * 1. 订单管理 - 创建、查询订单
 * 2. 支付集成 - 支持支付宝、微信、Stripe、PayPal、Paddle 等多种支付方式
 * 3. License 签发 - 基于 JWT 的软件许可证生成与验证
 * 4. 兑换码管理 - 生成和兑换激活码
 */
@SpringBootApplication
public class BillingLicenseApplication {
    
    /**
     * 应用程序入口
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(BillingLicenseApplication.class, args);
    }
}
