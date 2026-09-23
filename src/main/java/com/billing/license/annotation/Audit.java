package com.billing.license.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式审计注解（方案 B）。
 * 由 {@code AuditAspect} 统一拦截，组装操作者/时间/IP 等上下文并异步落库，
 * 调用方不再手写 audit()。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /** 操作类型；默认取方法名（大写）。如 LIST_ORDERS / REFUND_ORDER */
    String action() default "";

    /** 作用对象（SpEL），如 "#orderNumber"；默认 "-" */
    String target() default "-";

    /** 补充信息（SpEL），如 "#reason"；默认 "" */
    String detail() default "";

    /**
     * 常量 actor 覆盖（特殊场景，如客户端自吊销填 "client"）；
     * 默认空 → 取 {@code SecurityContext} 的登录主体（JWT 的 userId），未登录记为 "anonymous"。
     * 注：A12（2026-09-23）移除 X-API-Key 通道后，不再有「从请求头密钥哈希推导」的路径。
     */
    String actor() default "";
}
