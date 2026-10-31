package com.billing.license.config;

import com.billing.license.infrastructure.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductionConfigValidatorTest {

    private AccountProperties properties;
    private KmsService kmsService;
    private ProductionConfigValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AccountProperties();
        properties.setCodePepper("0123456789abcdef");
        kmsService = mock(KmsService.class);
        when(kmsService.sign(any())).thenReturn(new byte[]{1, 2, 3});
        when(kmsService.verify(any(), any())).thenReturn(true);
        when(kmsService.getAlgorithm()).thenReturn("ED25519");
        validator = new ProductionConfigValidator(properties, kmsService);
        ReflectionTestUtils.setField(validator, "appBaseUrl", "https://billing.example.com");
    }

    @Test
    void validProductionConfig_passes() {
        assertDoesNotThrow(() -> validator.run(null));
    }

    @Test
    void localhostBaseUrl_rejectsStartup() {
        ReflectionTestUtils.setField(validator, "appBaseUrl", "http://localhost:8000");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(ex.getMessage().contains("app.base-url"));
    }

    @Test
    void missingCodePepper_rejectsStartup() {
        properties.setCodePepper("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(ex.getMessage().contains("code-pepper"));
    }

    @Test
    void licenseKeySelfCheckFailure_rejectsStartup() {
        when(kmsService.verify(any(), any())).thenReturn(false);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.run(null));
        assertTrue(ex.getMessage().contains("License 签名密钥自检失败"));
    }
}
