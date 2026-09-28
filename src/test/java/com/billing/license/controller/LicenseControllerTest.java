package com.billing.license.controller;

import com.billing.license.common.web.ClientIpResolver;
import com.billing.license.dto.PendingLicenseResponse;
import com.billing.license.service.CredentialBindingService;
import com.billing.license.service.LicenseService;
import com.billing.license.service.risk.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * LicenseController 频控与响应形态单测（plan-1.0 / S1、S5）。
 *
 * <p>只测控制层该负责的两件事：**限流口径**（机器码先规范化、IP 取服务端解析值）与
 * **429 的空 body 契约**（客户端据此把「被限流」归入 unknown，绝不可当业务失败处理）。
 * 筛选逻辑本身在 {@code LicenseServiceTest} 覆盖。
 */
class LicenseControllerTest {

    private LicenseService licenseService;
    private RateLimitService rateLimitService;
    private ClientIpResolver clientIpResolver;
    private LicenseController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        licenseService = mock(LicenseService.class);
        rateLimitService = mock(RateLimitService.class);
        clientIpResolver = mock(ClientIpResolver.class);
        controller = new LicenseController(licenseService, rateLimitService,
            mock(CredentialBindingService.class), clientIpResolver);
        request = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.7");
    }

    @Test
    void pending_shouldReturnLicenses_whenNotRateLimited() {
        when(licenseService.findPendingForMachine("M1")).thenReturn(PendingLicenseResponse.builder()
            .licenses(List.of(PendingLicenseResponse.Item.builder().licenseKey("LIC-1").build()))
            .build());

        ResponseEntity<PendingLicenseResponse> resp = controller.pending("M1", request);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, resp.getBody().getLicenses().size());
    }

    /**
     * 429 必须**空 body**：客户端先判 status 再取 JSON，若这里回了业务壳，
     * 会被解析成畸形响应而掩盖「服务端在限流」这一真实原因。
     */
    @Test
    void pending_shouldReturn429WithEmptyBody_whenRateLimited() {
        doThrow(new RateLimitService.RateLimitExceededException("license-pending", "M1", 46, 45))
            .when(rateLimitService).checkLicensePending(anyString(), anyString());

        ResponseEntity<PendingLicenseResponse> resp = controller.pending("M1", request);

        assertEquals(429, resp.getStatusCode().value());
        assertNull(resp.getBody());
        // 被限流时绝不查库
        verify(licenseService, never()).findPendingForMachine(any());
    }

    @Test
    void pending_shouldNormalizeMachineCodeAndUseServerResolvedIp_forRateLimitKey() {
        when(licenseService.findPendingForMachine(anyString())).thenReturn(
            PendingLicenseResponse.builder().licenses(List.of()).build());

        controller.pending("  M1  ", request);

        // 与 service 的 trim 口径一致，否则 " M1" 与 "M1" 落进不同计数桶（限流形同虚设）
        verify(rateLimitService).checkLicensePending("M1", "203.0.113.7");
    }
}
