package com.billing.license.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * M3：TraceId 过滤器 + 请求审计。
 * - 优先复用上游传入的 {@code X-Trace-Id}（链路透传），否则生成 16 位 traceId；
 * - 写入 MDC（供日志关联）并在响应头回写 {@code X-Trace-Id}；
 * - 请求结束后输出审计日志：方法 / 路径 / 状态码 / 耗时 / traceId。
 */
@Component
@Order(-100)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        String traceId = (incoming != null && !incoming.isBlank())
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(MDC_KEY, traceId);
        long start = System.currentTimeMillis();
        try {
            response.setHeader(TRACE_ID_HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            long took = System.currentTimeMillis() - start;
            log.info("request audit: traceId={}, method={}, uri={}, status={}, took={}ms",
                    traceId, request.getMethod(), request.getRequestURI(), response.getStatus(), took);
            MDC.remove(MDC_KEY);
        }
    }
}
