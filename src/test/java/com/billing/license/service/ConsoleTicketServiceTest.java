package com.billing.license.service;

import com.billing.license.config.BillingProperties;
import com.billing.license.exception.BusinessException;
import com.billing.license.infrastructure.crypto.LicenseIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTicketServiceTest {

    private static final String SECRET = "ticket-secret-for-test";
    private static final String SALT = "product-salt-for-test";

    private BillingProperties properties;
    private LicenseIssuer licenseIssuer;
    private ConsoleTicketService service;

    @BeforeEach
    void setUp() {
        properties = new BillingProperties();
        properties.getConsole().setTicketSecret(SECRET);
        properties.getConsole().setProductSalt(SALT);
        properties.getConsole().setProductCode("ai-tools");
        licenseIssuer = Mockito.mock(LicenseIssuer.class);
        service = new ConsoleTicketService(properties, licenseIssuer);
    }

    @Test
    @DisplayName("未登录：设备/账号摘要稳定，票据可用同一密钥验签")
    void anonymousTicketIsStableAndVerifiable() throws Exception {
        ConsoleTicketService.Ticket first = service.issue(null, "MID-0001", "ai-tools");
        ConsoleTicketService.Ticket second = service.issue(null, "MID-0001", "ai-tools");

        assertTrue(first.deviceKeyHash().matches("[0-9a-f]{64}"));
        assertTrue(first.accountKeyHash().matches("[0-9a-f]{64}"));
        assertEquals(first.deviceKeyHash(), second.deviceKeyHash());
        assertEquals(first.accountKeyHash(), second.accountKeyHash());
        assertEquals(first.accountKeyHash(), hmac(SALT, "anonymous:MID-0001"));

        String[] parts = first.installTicket().split("\\.");
        assertEquals(2, parts.length);
        assertEquals(hmacB64(SECRET, parts[0]), parts[1]);
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        assertTrue(payload.startsWith("v1|ai-tools|" + first.deviceKeyHash() + "|"));
    }

    @Test
    @DisplayName("不同机器码的匿名账号摘要不同")
    void differentMachineHasDifferentAnonymousAccount() {
        ConsoleTicketService.Ticket a = service.issue(null, "MID-A", "ai-tools");
        ConsoleTicketService.Ticket b = service.issue(null, "MID-B", "ai-tools");
        assertNotEquals(a.deviceKeyHash(), b.deviceKeyHash());
        assertNotEquals(a.accountKeyHash(), b.accountKeyHash());
    }

    @Test
    @DisplayName("有效 token 且 mid 匹配：账号摘要取 cid，user_type 按 SKU 映射")
    void validTokenUsesCustomerId() {
        Mockito.when(licenseIssuer.verifyLicense("tok")).thenReturn(true);
        Mockito.when(licenseIssuer.decodePayload("tok")).thenReturn(Map.of(
            "cid", "customer-1",
            "sku", "pro-plus-buyout",
            "mid", "MID-0001",
            "exp", Instant.now().getEpochSecond() + 3600
        ));

        ConsoleTicketService.Ticket ticket = service.issue("tok", "MID-0001", "ai-tools");
        assertEquals(hmac(SALT, "account:customer-1"), ticket.accountKeyHash());
        assertEquals("pro_plus", ticket.userType());
    }

    @Test
    @DisplayName("token 的 mid 与机器码不一致：回退匿名摘要")
    void mismatchedMachineFallsBackToAnonymous() {
        Mockito.when(licenseIssuer.verifyLicense("tok")).thenReturn(true);
        Mockito.when(licenseIssuer.decodePayload("tok")).thenReturn(Map.of(
            "cid", "customer-1",
            "sku", "pro-buyout",
            "mid", "OTHER-MID",
            "exp", Instant.now().getEpochSecond() + 3600
        ));

        ConsoleTicketService.Ticket ticket = service.issue("tok", "MID-0001", "ai-tools");
        assertEquals(hmac(SALT, "anonymous:MID-0001"), ticket.accountKeyHash());
        assertEquals("free", ticket.userType());
    }

    @Test
    @DisplayName("密钥/盐缺失或产品码不匹配：返回业务异常")
    void configurationAndProductGuards() {
        properties.getConsole().setTicketSecret("");
        BusinessException missing = assertThrows(BusinessException.class,
            () -> service.issue(null, "MID-0001", "ai-tools"));
        assertEquals("TICKET_SECRET_NOT_CONFIGURED", missing.getErrorCode());
        assertFalse(service.isConfigured());

        properties.getConsole().setTicketSecret(SECRET);
        BusinessException mismatch = assertThrows(BusinessException.class,
            () -> service.issue(null, "MID-0001", "other-product"));
        assertEquals("PRODUCT_MISMATCH", mismatch.getErrorCode());
    }

    private static String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacB64(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
