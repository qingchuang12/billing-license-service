package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.PaymentSettingsView;
import com.billing.license.dto.PaymentToggleRequest;
import com.billing.license.exception.BusinessException;
import com.billing.license.security.CurrentUserResolver;
import com.billing.license.service.payment.PaymentFeature;
import com.billing.license.service.payment.PaymentSettingsService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;

/**
 * Web 管理端：支付渠道启停 + 功能开关。
 *
 * <p>渠道停用只影响新建支付；已支付订单的 Webhook/退款仍继续处理。
 */
@Tag(name = "支付运行时开关", description = "渠道启停、功能开关（需 ROLE_ADMIN）")
@RestController
@RequestMapping("/api/admin/payment-settings")
@RequiredArgsConstructor
public class AdminPaymentSettingsController {

    private final PaymentServiceFactory paymentServiceFactory;
    private final PaymentSettingsService paymentSettingsService;

    @Operation(summary = "查询支付渠道/功能开关")
    @Audit(action = "VIEW_PAYMENT_SETTINGS")
    @GetMapping
    public ResponseEntity<PaymentSettingsView> settings() {
        return ResponseEntity.ok(buildView());
    }

    @Operation(summary = "启用/停用支付渠道")
    @Audit(action = "TOGGLE_PAYMENT_CHANNEL", target = "#method")
    @PutMapping("/channels/{method}")
    public ResponseEntity<PaymentSettingsView> toggleChannel(
            @PathVariable String method,
            @RequestBody(required = false) PaymentToggleRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException("PAYMENT_TOGGLE_INVALID", "enabled 必填");
        }
        PaymentMethod target;
        try {
            target = PaymentMethod.valueOf(method.trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException("INVALID_CHANNEL", "未知支付渠道：" + method);
        }
        if (request.getEnabled()) {
            boolean configured = paymentServiceFactory.getChannelStatuses().stream()
                .anyMatch(c -> c.method().equals(target.name()) && c.configured());
            if (!configured) {
                throw new BusinessException("PAYMENT_CHANNEL_NOT_CONFIGURED",
                    "渠道关键配置不完整，不能启用：" + target.name());
            }
        }
        paymentSettingsService.setChannelEnabled(target, request.getEnabled(),
            CurrentUserResolver.currentUserId());
        paymentServiceFactory.refreshChannels();
        return ResponseEntity.ok(buildView());
    }

    @Operation(summary = "启用/停用支付功能")
    @Audit(action = "TOGGLE_PAYMENT_FEATURE", target = "#key")
    @PutMapping("/features/{key}")
    public ResponseEntity<PaymentSettingsView> toggleFeature(
            @PathVariable String key,
            @RequestBody(required = false) PaymentToggleRequest request) {
        if (request == null || request.getEnabled() == null) {
            throw new BusinessException("PAYMENT_TOGGLE_INVALID", "enabled 必填");
        }
        PaymentFeature feature = PaymentFeature.fromKey(key);
        if (feature == null) {
            throw new BusinessException("INVALID_PAYMENT_FEATURE", "未知支付功能：" + key);
        }
        if (!feature.implemented() && request.getEnabled()) {
            throw new BusinessException("PAYMENT_FEATURE_NOT_IMPLEMENTED",
                "功能尚未接入，不能开启：" + feature.key());
        }
        paymentSettingsService.setFeatureEnabled(feature, request.getEnabled(),
            CurrentUserResolver.currentUserId());
        return ResponseEntity.ok(buildView());
    }

    private PaymentSettingsView buildView() {
        var channels = paymentServiceFactory.getChannelStatuses().stream()
            .map(c -> {
                PaymentMethod method = PaymentMethod.valueOf(c.method());
                return new PaymentSettingsView.ChannelSettingView(
                    c.method(), method.getName(), c.enabled(), c.configured(),
                    paymentSettingsService.channelOverride(method), c.missingConfig());
            })
            .toList();
        var features = Arrays.stream(PaymentFeature.values())
            .map(f -> new PaymentSettingsView.FeatureSettingView(
                f.key(), f.displayName(), paymentSettingsService.isFeatureEnabled(f),
                f.implemented(), paymentSettingsService.featureOverrides().get(f.key())))
            .toList();
        return new PaymentSettingsView(channels, features);
    }
}
