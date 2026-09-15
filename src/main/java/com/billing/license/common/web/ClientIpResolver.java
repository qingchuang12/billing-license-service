package com.billing.license.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 客户端真实 IP 解析（H8 策略集中化）。
 *
 * <p>未显式信任反向代理（{@code billing.trust-x-forwarded-for=false}，默认）时，
 * <b>绝不信任可伪造的 {@code X-Forwarded-For} / {@code X-Real-IP}</b>，直接采用直连 peer 地址；
 * 仅在确认反代可信后才解析转发头。抽取为组件是为避免「限流/频控」各处各写一份导致策略漂移。
 */
@Component
public class ClientIpResolver {

    private final boolean trustXForwardedFor;

    public ClientIpResolver(@Value("${billing.trust-x-forwarded-for:false}") boolean trustXForwardedFor) {
        this.trustXForwardedFor = trustXForwardedFor;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        if (!trustXForwardedFor) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            // 取第一个（最原始客户端）
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
