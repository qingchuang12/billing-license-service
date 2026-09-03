package com.billing.license.service.payment.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormParamParserTest {

    @Test
    void parse_splitKeyValuePairs() {
        Map<String, String> params = FormParamParser.parse("a=1&b=2&c=3");
        assertEquals(3, params.size());
        assertEquals("1", params.get("a"));
        assertEquals("2", params.get("b"));
        assertEquals("3", params.get("c"));
    }

    @Test
    void parse_urlDecodesValues() {
        Map<String, String> params = FormParamParser.parse("subject=%E6%B5%8B%E8%AF%95&code=x%20y");
        assertEquals("测试", params.get("subject"));
        assertEquals("x y", params.get("code"));
    }

    @Test
    void parse_skipsSegmentWithoutEquals() {
        // 无 '=' 的片段（含开头为 '=' 的脏数据）应被跳过，不抛异常
        Map<String, String> params = FormParamParser.parse("good=1&=bad&alsobad");
        assertEquals(1, params.size());
        assertEquals("1", params.get("good"));
    }

    @Test
    void parse_nullOrEmpty_returnsEmptyMap() {
        assertTrue(FormParamParser.parse(null).isEmpty());
        assertTrue(FormParamParser.parse("").isEmpty());
        assertTrue(FormParamParser.parse("   ").isEmpty());
    }
}
