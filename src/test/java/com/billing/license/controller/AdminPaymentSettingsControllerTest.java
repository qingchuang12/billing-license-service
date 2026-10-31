package com.billing.license.controller;

import com.billing.license.dto.PaymentChannelStatus;
import com.billing.license.dto.PaymentSettingsView;
import com.billing.license.dto.PaymentToggleRequest;
import com.billing.license.exception.BusinessException;
import com.billing.license.service.payment.PaymentFeature;
import com.billing.license.service.payment.PaymentSettingsService;
import com.billing.license.service.payment.impl.PaymentServiceFactory;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminPaymentSettingsControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PaymentServiceFactory factory;
    private PaymentSettingsService settingsService;
    private AdminPaymentSettingsController controller;

    @BeforeEach
    void setUp() {
        factory = mock(PaymentServiceFactory.class);
        settingsService = mock(PaymentSettingsService.class);
        controller = new AdminPaymentSettingsController(factory, settingsService);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ADMIN_ID.toString(), null, List.of()));
        when(factory.getChannelStatuses()).thenReturn(List.of(
            new PaymentChannelStatus("ALIPAY", true, true, List.of(), "",
                List.of("ONE_TIME", "LIFETIME"), false, List.of("QRCODE", "REDIRECT", "WAP"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void settings_listsChannelsAndFeatures() {
        when(settingsService.isFeatureEnabled(any())).thenReturn(false);

        PaymentSettingsView view = controller.settings().getBody();

        assertNotNull(view);
        assertEquals(1, view.channels().size());
        assertEquals("ALIPAY", view.channels().get(0).method());
        assertFalse(view.features().isEmpty());
    }

    @Test
    void toggleChannel_passesAdminActor_andRefreshesFactory() {
        PaymentToggleRequest request = new PaymentToggleRequest();
        request.setEnabled(false);

        when(settingsService.isFeatureEnabled(any())).thenReturn(false);
        controller.toggleChannel("ALIPAY", request);

        verify(settingsService).setChannelEnabled(PaymentMethod.ALIPAY, false, ADMIN_ID);
        verify(factory).refreshChannels();
    }

    @Test
    void toggleChannel_rejectsEnablingUnconfiguredChannel() {
        when(factory.getChannelStatuses()).thenReturn(List.of(
            new PaymentChannelStatus("ALIPAY", false, false, List.of("payment.alipay.app-id"), "",
                List.of("ONE_TIME"), false, List.of("QRCODE"))));
        PaymentToggleRequest request = new PaymentToggleRequest();
        request.setEnabled(true);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> controller.toggleChannel("ALIPAY", request));
        assertEquals("PAYMENT_CHANNEL_NOT_CONFIGURED", ex.getErrorCode());
        verify(settingsService, never()).setChannelEnabled(any(), anyBoolean(), any());
    }

    @Test
    void toggleFeature_rejectsPlannedFeature() {
        PaymentToggleRequest request = new PaymentToggleRequest();
        request.setEnabled(true);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> controller.toggleFeature(PaymentFeature.ALIPAY_RECURRING.key(), request));
        assertEquals("PAYMENT_FEATURE_NOT_IMPLEMENTED", ex.getErrorCode());
    }
}
