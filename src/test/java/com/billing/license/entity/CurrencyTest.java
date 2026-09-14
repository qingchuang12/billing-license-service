package com.billing.license.entity;

import com.billing.license.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1：Currency 枚举验证——大小写不敏感解析、非法值拒绝、minorUnits、JSON 契约、取值覆盖。
 */
class CurrencyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fromCode_isCaseInsensitive_andTrims() {
        assertEquals(Currency.USD, Currency.fromCode("usd"));
        assertEquals(Currency.CNY, Currency.fromCode(" cny "));
        assertEquals(Currency.JPY, Currency.fromCode("JPY"));
    }

    @Test
    void fromCode_nullOrBlank_returnsNull() {
        assertNull(Currency.fromCode(null));
        assertNull(Currency.fromCode("  "));
        assertNull(Currency.fromCodeOrNull(null));
        assertNull(Currency.fromCodeOrNull(""));
    }

    @Test
    void fromCode_unknown_throwsBusinessException_butOrNullReturnsNull() {
        BusinessException ex = assertThrows(BusinessException.class, () -> Currency.fromCode("XYZ"));
        assertEquals("UNSUPPORTED_CURRENCY", ex.getErrorCode());
        assertNull(Currency.fromCodeOrNull("XYZ"));
    }

    @Test
    void minorUnits_areCorrect() {
        assertEquals(0, Currency.JPY.minorUnits());
        assertEquals(0, Currency.KRW.minorUnits());
        assertEquals(0, Currency.VND.minorUnits());
        assertEquals(2, Currency.USD.minorUnits());
        assertEquals(2, Currency.EUR.minorUnits());
        assertEquals(2, Currency.CNY.minorUnits());
    }

    @Test
    void coversAsianAndG20Currencies() {
        String[] codes = {"CNY", "JPY", "KRW", "HKD", "TWD", "SGD", "MYR", "THB", "IDR", "PHP", "VND", "INR",
                "USD", "EUR", "GBP", "CAD", "AUD", "BRL", "MXN", "RUB", "SAR", "TRY", "ZAR", "ARS"};
        for (String code : codes) {
            assertNotNull(Currency.fromCodeOrNull(code), "缺少货币：" + code);
        }
    }

    @Test
    void json_serializesToUpperCode_andDeserializesCaseInsensitively() throws Exception {
        assertEquals("\"USD\"", mapper.writeValueAsString(Currency.USD));
        assertEquals("\"CNY\"", mapper.writeValueAsString(Currency.CNY));
        assertEquals(Currency.USD, mapper.readValue("\"usd\"", Currency.class));
        assertEquals(Currency.CNY, mapper.readValue("\"CNY\"", Currency.class));
    }

    @Test
    void json_unknownCode_isRejected() {
        assertThrows(Exception.class, () -> mapper.readValue("\"XYZ\"", Currency.class));
    }
}
