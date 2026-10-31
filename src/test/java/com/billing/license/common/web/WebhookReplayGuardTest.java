package com.billing.license.common.web;

import com.billing.license.config.BillingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookReplayGuardTest {

    private WebhookReplayGuard guard;

    @BeforeEach
    void setUp() {
        BillingProperties properties = new BillingProperties();
        BillingProperties.Webhook webhook = new BillingProperties.Webhook();
        webhook.setMaxClockSkewSeconds(300);
        properties.setWebhook(webhook);
        guard = new WebhookReplayGuard(properties);
    }

    @Test
    void noTimestamp_passes() {
        assertNull(guard.check(Map.of()));
    }

    @Test
    void freshStripeTimestamp_passes() {
        long now = Instant.now().getEpochSecond();
        assertNull(guard.check(Map.of("Stripe-Signature", "t=" + now + ",v1=abc")));
    }

    @Test
    void staleStripeTimestamp_rejects() {
        long old = Instant.now().getEpochSecond() - 301;
        String error = guard.check(Map.of("Stripe-Signature", "t=" + old + ",v1=abc"));
        assertNotNull(error);
        assertTrue(error.contains("Stale"));
    }

    @Test
    void freshPaddleTimestamp_passes() {
        long now = Instant.now().getEpochSecond();
        assertNull(guard.check(Map.of("Paddle-Signature", "ts=" + now + ";h1=abc")));
    }

    @Test
    void stalePaddleTimestamp_rejects() {
        long old = Instant.now().getEpochSecond() - 301;
        assertNotNull(guard.check(Map.of("Paddle-Signature", "ts=" + old + ";h1=abc")));
    }

    @Test
    void wechatMilliseconds_tolerated() {
        String ms = String.valueOf(System.currentTimeMillis());
        assertNull(guard.check(Map.of("Wechatpay-Timestamp", ms)));
    }

    @Test
    void paypalIsoTimestamp_passes() {
        assertNull(guard.check(Map.of("Paypal-Transmission-Time", Instant.now().toString())));
    }
}
