package com.billing.license.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMaskTest {

    @Test
    void secret_shouldKeepHeadAndTailOnlyForLongValues() {
        assertEquals("abcd****wxyz", LogMask.secret("abcdefghijklmnopwxyz"));
        assertEquals("****", LogMask.secret("123456"));
        assertEquals("****", LogMask.secret("12345678"));
        assertEquals("", LogMask.secret(null));
        assertEquals("", LogMask.secret(""));
    }
}
