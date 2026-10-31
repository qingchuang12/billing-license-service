package com.billing.license.common.web;

import com.billing.license.config.BillingProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Webhook 防重放：只对**签名中携带时间戳**的渠道做时钟偏差校验。
 *
 * <p>支付宝与 Creem 的签名字段没有时间戳，不能凭空拒绝，否则会破坏其重投机制；
 * 这两家依赖 payment_events(provider, event_id) 唯一约束去重。
 */
@Component
@RequiredArgsConstructor
public class WebhookReplayGuard {

    private static final Logger logger = LoggerFactory.getLogger(WebhookReplayGuard.class);

    private final BillingProperties billingProperties;

    /** @return null 表示通过；否则返回拒绝原因 */
    public String check(Map<String, String> headers) {
        Long timestamp = timestampOf(headers);
        if (timestamp == null) {
            return null;
        }
        long skew = billingProperties.getWebhook().getMaxClockSkewSeconds();
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - timestamp) > skew) {
            logger.warn("Webhook 时间戳超出允许窗口：timestamp={}, now={}, skew={}s", timestamp, now, skew);
            return "Stale webhook timestamp";
        }
        return null;
    }

    private Long timestampOf(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        Long value = parseEpoch(header(headers, "X-Webhook-Timestamp"));
        if (value != null) {
            return value;
        }
        value = parseEpoch(header(headers, "Wechatpay-Timestamp"));
        if (value != null) {
            return value;
        }
        value = parseSignedHeader(header(headers, "Stripe-Signature"), "t=");
        if (value != null) {
            return value;
        }
        value = parseSignedHeader(header(headers, "Paddle-Signature"), "ts=");
        if (value != null) {
            return value;
        }
        String paypalTime = header(headers, "Paypal-Transmission-Time");
        if (paypalTime != null) {
            try {
                return Instant.parse(paypalTime.trim()).getEpochSecond();
            } catch (Exception e) {
                logger.debug("无法解析 PayPal 传输时间，跳过防重放时间戳校验：{}", paypalTime);
            }
        }
        return null;
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Stripe: t=123,v1=...；Paddle: ts=123;h1=... */
    private static Long parseSignedHeader(String value, String prefix) {
        if (value == null) {
            return null;
        }
        for (String part : value.split("[,;]")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return parseEpoch(trimmed.substring(prefix.length()));
            }
        }
        return null;
    }

    private static Long parseEpoch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            // 兼容毫秒时间戳
            return parsed > 10_000_000_000L ? parsed / 1000 : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
