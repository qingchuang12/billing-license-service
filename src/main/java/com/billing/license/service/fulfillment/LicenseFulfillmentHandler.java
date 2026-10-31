package com.billing.license.service.fulfillment;

import com.billing.license.entity.License;
import com.billing.license.entity.Order;
import com.billing.license.repository.CheckoutSessionRepository;
import com.billing.license.service.LicenseService;
import com.billing.license.service.RedeemCodeService;
import com.billing.license.service.notification.EmailNotificationService;
import com.billing.license.util.LogMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * License 交付适配器：机器码订单直签，否则生成兑换码并发通知邮件。
 * 幂等由 LicenseService / RedeemCodeService 按订单保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseFulfillmentHandler implements FulfillmentHandler {

    public static final String TYPE = "LICENSE";

    private final LicenseService licenseService;
    private final RedeemCodeService redeemCodeService;
    private final EmailNotificationService emailNotificationService;
    private final CheckoutSessionRepository checkoutSessionRepository;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void fulfill(Order order) {
        String orderId = order.getOrderNo();
        String machineCode = order.getMachineCode();
        String productName = order.getTitle() != null ? order.getTitle() : "License";
        String email = order.getEmail();
        // 直接读会话 locale，避免 Handler → CheckoutService → FulfillmentService 的循环依赖
        String locale = checkoutSessionRepository.findByOrderNumber(orderId)
            .map(com.billing.license.entity.CheckoutSession::getLocale).orElse(null);

        if (machineCode != null && !machineCode.isEmpty()) {
            log.info("签发绑定设备的 License：orderId={}, machineCode={}", orderId, LogMask.secret(machineCode));
            License issued = licenseService.issueLicense(orderId, machineCode);
            if (email != null && !email.isEmpty()) {
                emailNotificationService.sendPaymentSuccessEmail(
                    email, orderId, productName,
                    order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : 0.0,
                    order.getCurrency(), locale);
                emailNotificationService.sendLicenseIssuedEmail(email, issued.getLicenseKey(), productName,
                    issued.getExpiresAt() != null ? issued.getExpiresAt().toLocalDate().toString() : null, locale);
            }
        } else {
            log.info("生成兑换码：orderId={}", orderId);
            String code = redeemCodeService.generateCode(orderId);
            if (email != null && !email.isEmpty()) {
                emailNotificationService.sendPaymentSuccessEmail(
                    email, orderId, productName,
                    order.getTotalAmount() != null ? order.getTotalAmount().doubleValue() : 0.0,
                    order.getCurrency(), locale);
                emailNotificationService.sendRedeemCodeEmail(email, code, productName,
                    order.getPaidAt() != null ? order.getPaidAt().toString() : "", locale);
            }
        }
    }
}
