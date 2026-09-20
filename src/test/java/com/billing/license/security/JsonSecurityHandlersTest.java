package com.billing.license.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N3（2026-09-20）契约测试：401 / 403 必须有**可读 JSON 响应体**。
 *
 * <p>此前 {@code HttpStatusEntryPoint} 只回状态码、body 为空，页面端取不到任何提示，
 * 只能落兜底文案「操作失败，请稍后重试」；被 {@code anyRequest().denyAll()} 拦下的未知路径同样如此。
 * 现在统一写 {@code ApiResponse.fail(...)}，结构与业务错误同壳同构，前端 {@code buildApiError} 可直接消费。
 */
class JsonSecurityHandlersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(MockHttpServletResponse res) throws Exception {
        return mapper.readTree(res.getContentAsString());
    }

    @Test
    void entryPoint_returns401WithJsonEnvelope() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/account/me");
        MockHttpServletResponse res = new MockHttpServletResponse();

        new JsonAuthenticationEntryPoint().commence(req, res, null);

        assertEquals(401, res.getStatus());
        assertFalse(res.getContentAsString().isBlank(), "401 不得再是空 body");
        assertTrue(String.valueOf(res.getContentType()).startsWith("application/json"),
            "响应须声明 JSON 且带 UTF-8 字符集，实际=" + res.getContentType());

        JsonNode node = parse(res);
        assertFalse(node.path("success").asBoolean(true), "失败响应顶层 success 必须为 false");
        assertEquals("UNAUTHORIZED", node.path("code").asText());
        assertFalse(node.path("message").asText().isBlank(), "必须给出可读提示");
        assertFalse(node.path("traceId").asText().isBlank(), "必须带 traceId 便于服务端对照日志");
    }

    @Test
    void accessDeniedHandler_returns403WithJsonEnvelope() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/licenses");
        MockHttpServletResponse res = new MockHttpServletResponse();

        new JsonAccessDeniedHandler().handle(req, res, null);

        assertEquals(403, res.getStatus());
        assertFalse(res.getContentAsString().isBlank(), "403 不得再是空 body");

        JsonNode node = parse(res);
        assertFalse(node.path("success").asBoolean(true));
        assertEquals("ACCESS_DENIED", node.path("code").asText());
        assertFalse(node.path("message").asText().isBlank());
    }

    /** 脱敏：不得以任何形式把异常原因写进响应体 */
    @Test
    void handlers_doNotLeakExceptionMessage() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new JsonAuthenticationEntryPoint().commence(
            new MockHttpServletRequest("GET", "/api/account/me"), res,
            new org.springframework.security.authentication.BadCredentialsException("secret-token-value"));

        String body = res.getContentAsString();
        assertFalse(body.contains("secret-token-value"), "响应不得泄漏凭证/异常原文");
        assertFalse(body.contains("BadCredentials"), "响应不得泄漏异常类型");
    }

    /** 与成功响应同壳同构：字段名一一对应，前端无需分叉解析 */
    @Test
    void errorEnvelope_usesSameKeysAsSuccess() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new JsonAccessDeniedHandler().handle(new MockHttpServletRequest("GET", "/x"), res, null);

        JsonNode node = parse(res);
        assertTrue(node.has("success") && node.has("code") && node.has("message"),
            "错误壳字段须与 ApiResponse 一致");
        assertEquals(StandardCharsets.UTF_8.name(), res.getCharacterEncoding().toUpperCase());
    }
}
