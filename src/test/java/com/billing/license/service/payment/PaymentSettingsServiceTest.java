package com.billing.license.service.payment;

import com.billing.license.entity.PaymentChannelSetting;
import com.billing.license.entity.PaymentFeatureSetting;
import com.billing.license.repository.PaymentChannelSettingRepository;
import com.billing.license.repository.PaymentFeatureSettingRepository;
import com.billing.license.service.payment.strategy.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentSettingsServiceTest {

    private PaymentChannelSettingRepository channelRepo;
    private PaymentFeatureSettingRepository featureRepo;
    private PaymentSettingsService service;

    @BeforeEach
    void setUp() {
        channelRepo = mock(PaymentChannelSettingRepository.class);
        featureRepo = mock(PaymentFeatureSettingRepository.class);
        when(channelRepo.findAll()).thenReturn(List.of());
        when(featureRepo.findAll()).thenReturn(List.of());
        service = new PaymentSettingsService(channelRepo, featureRepo);
        service.reload();
    }

    @Test
    void defaults_areUsedWhenNoDatabaseOverride() {
        assertNull(service.channelOverride(PaymentMethod.ALIPAY));
        assertTrue(service.isFeatureEnabled(PaymentFeature.ALIPAY_WAP));
        assertFalse(service.isFeatureEnabled(PaymentFeature.CREEM_CUSTOMER_CREDITS));
    }

    @Test
    void channelOverride_isAppliedAndReloaded() {
        PaymentChannelSetting setting = new PaymentChannelSetting();
        setting.setChannel("ALIPAY");
        setting.setEnabled(true);
        when(channelRepo.findById("ALIPAY")).thenReturn(Optional.of(setting));
        when(channelRepo.findAll()).thenReturn(List.of(setting));

        service.setChannelEnabled(PaymentMethod.ALIPAY, true, UUID.randomUUID());

        assertEquals(Boolean.TRUE, service.channelOverride(PaymentMethod.ALIPAY));
        verify(channelRepo).save(any(PaymentChannelSetting.class));
    }

    @Test
    void implementedFeature_canBeToggled() {
        PaymentFeatureSetting setting = new PaymentFeatureSetting();
        setting.setFeatureKey(PaymentFeature.ALIPAY_WAP.key());
        setting.setEnabled(false);
        when(featureRepo.findById(PaymentFeature.ALIPAY_WAP.key())).thenReturn(Optional.of(setting));
        when(featureRepo.findAll()).thenReturn(List.of(setting));

        service.setFeatureEnabled(PaymentFeature.ALIPAY_WAP, false, UUID.randomUUID());

        assertFalse(service.isFeatureEnabled(PaymentFeature.ALIPAY_WAP));
        verify(featureRepo).save(setting);
    }

    @Test
    void plannedFeature_cannotBeEnabled() {
        assertThrows(IllegalArgumentException.class,
            () -> service.setFeatureEnabled(PaymentFeature.ALIPAY_RECURRING, true, UUID.randomUUID()));
    }
}
