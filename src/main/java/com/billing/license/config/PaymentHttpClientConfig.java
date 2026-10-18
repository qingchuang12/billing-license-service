package com.billing.license.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 支付渠道外部调用治理（#5）：全渠道共用**一个** JDK {@link HttpClient}，并统一超时口径。
 *
 * <p>原状：5 家策略里 13 处各自 {@code HttpClient.newHttpClient()}——
 * <ul>
 *   <li>既没设连接超时，也没在请求上设超时（JDK 默认**无限等待**）；</li>
 *   <li>每次调用新建实例且从不关闭（Java 21 起 HttpClient 可关闭），泄漏选择器线程与连接；</li>
 *   <li>对端（渠道 API）卡住时，请求线程连同其占用的 DB 连接（Hikari 默认池 10）一并挂死，
 *       webhook 与收银台轮询两条腿同时打满即全站不可用。</li>
 * </ul>
 *
 * <p>本类只统一**连接**超时；JDK 的 {@code HttpClient} 没有请求级超时，
 * 单次请求超时须由各策略建请求时带上 {@link #REQUEST_TIMEOUT}（一处常量、各处引用）。
 *
 * <p>重定向策略保持 JDK 默认（{@code Redirect.NEVER}），与改造前逐次新建的客户端一致。
 */
@Configuration
public class PaymentHttpClientConfig {

    /** 建立 TCP 连接的上限：对端不接受连接即失败，不排队等待。 */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 单次请求（发出请求到读完响应）的上限，由各策略施加在 {@code HttpRequest} 上。 */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 进程内唯一的共享客户端：各策略经构造注入取用，不再各自 new（#5）。 */
    private static final HttpClient SHARED = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .build();

    @Bean
    public HttpClient paymentHttpClient() {
        return SHARED;
    }
}
