package com.billing.license.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态页目录索引转发（U2 冒烟发现，一并修复既有同源缺陷）。
 *
 * <p><b>问题</b>：Spring Boot 3 不再为子目录 URL 自动解析 {@code index.html}
 * （欢迎页只覆盖根路径 {@code /}），导致 {@code GET /checkout/}、{@code GET /account/}
 * 落到资源处理器抛 {@code NoResourceFoundException}，被 GlobalExceptionHandler 归一为 500
 * （冒烟实测：{@code /checkout/index.html}=200 而 {@code /checkout/}=500）。
 *
 * <p><b>修复</b>：显式把目录路径（含尾斜杠）forward 到对应静态页，
 * 买家跳转地址（官网购买入口、客户端在线激活）无需感知。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/checkout").setViewName("forward:/checkout/index.html");
        registry.addViewController("/checkout/").setViewName("forward:/checkout/index.html");
        registry.addViewController("/account").setViewName("forward:/account/index.html");
        registry.addViewController("/account/").setViewName("forward:/account/index.html");
        // 管理统计页（2026-09-22）：同源缺陷——只放行 /admin/** 时目录路径仍会 500，
        // 与 /checkout、/account 一并 forward（启动日志打印的是 /admin/ 目录形式）。
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
        // 支付结果页（plan-8.1 / #12）：Stripe / Creem 的默认 success-url、cancel-url 都指向
        // /payment/success 与 /payment/cancel，而这两个路径此前既无静态资源也无控制器，
        // 未传 returnUrl 的接入方（服务端直连建单）付款后被安全链拦成 401 JSON。
        // 落地为匿名只读结果页：只回显回跳自带的订单标识，不取任何账户数据。
        registry.addViewController("/payment/success").setViewName("forward:/payment/success.html");
        registry.addViewController("/payment/cancel").setViewName("forward:/payment/cancel.html");
        // /payment/ 目录本身不设索引：该前缀只有这两条合法入口（均由渠道回跳产生），
        // 虚构一个 index.html 只会多一个不知道该显示「成功」还是「取消」的页面。
    }
}
