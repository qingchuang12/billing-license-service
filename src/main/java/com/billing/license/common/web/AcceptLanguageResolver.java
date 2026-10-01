package com.billing.license.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * F7：买家请求语言解析。取 {@code Accept-Language} 首选标签（第一个语言区间，去掉权重），
 * 供邮件按中/英出文案；缺失时返回 {@code null}，由 {@code EmailNotificationService.preferEn} 回落 en。
 *
 * <p>与 {@link ClientIpResolver} 同址、同「服务端解析、忽略伪造值」的取向：语言只认请求头，
 * 不认请求体。收银台发货/退款邮件另有更准的来源（{@code CheckoutSession.locale}），不走本组件。
 */
@Component
public class AcceptLanguageResolver {

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return null;
        }
        // 取第一个语言区间：如 "zh-CN,zh;q=0.9,en;q=0.8" → "zh-CN"；去掉可能的 ;q= 权重
        String primary = header.split(",")[0].trim();
        int semi = primary.indexOf(';');
        if (semi >= 0) {
            primary = primary.substring(0, semi).trim();
        }
        return primary.isEmpty() ? null : primary;
    }
}
