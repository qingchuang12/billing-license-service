package com.billing.license.web;

import com.billing.license.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-1（plan-7.0，2026-09-30）安全响应头<b>契约自证</b>。
 *
 * <p>两条独立保证：
 * <ol>
 *   <li><b>CSP 内联脚本哈希不漂移</b>：三静态页 {@code <head>} 里那段给 {@code <html>} 加 {@code js}
 *       类的内联脚本被 CSP 以 SHA-256 白名单放行。这里从三份 HTML <b>重算</b>哈希并与
 *       {@link SecurityConfig#INLINE_SCRIPT_SHA256} 比对——一旦有人改了内联脚本却忘了重算常量，
 *       本用例立即变红，而不是等上线后页面静默退化成「无脚本态」（内容可见但登录/支付失效）。</li>
 *   <li><b>四个头真的下发</b>：走完整 Spring Security 过滤器链（含 HeaderWriterFilter），
 *       对静态页与 {@code /api/**} 各打一发，断言 X-Frame-Options=DENY、nosniff、
 *       Referrer-Policy、CSP（含 frame-ancestors 'none'）都在响应里。</li>
 * </ol>
 *
 * <p>⚠️ 本用例只证「头存在且哈希自洽」，<b>不</b>证「浏览器真机渲染不受影响」——那需要真人打开
 * 三页跑登录/支付回调/License 校验（真机走查项见 `docs/上线准备工作.md` §七 E 段）。
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityHeaderContractTest {

    /** 只匹配<b>无属性</b>的裸内联脚本 {@code <script>...</script>}；带 src= 的外链脚本不匹配。 */
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script>([^<]*)</script>");

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
    }

    private static String read(String classpath) throws Exception {
        ClassPathResource resource = new ClassPathResource(classpath);
        assertTrue(resource.exists(), "静态资源缺失：" + classpath);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String sha256Base64(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return "sha256-" + Base64.getEncoder().encodeToString(digest);
    }

    @Test
    @DisplayName("三静态页内联脚本的 SHA-256 必须等于 SecurityConfig 白名单里的哈希")
    void inlineScriptHashMatchesCspWhitelist() throws Exception {
        for (String page : new String[]{
                "static/admin/index.html", "static/account/index.html", "static/checkout/index.html"}) {
            Matcher m = INLINE_SCRIPT.matcher(read(page));
            assertTrue(m.find(), page + " 未找到裸内联脚本（正则失效或脚本被改成外链/带属性）");
            String body = m.group(1);
            assertEquals("document.documentElement.classList.add('js');", body.trim(),
                page + " 的内联脚本内容变了——若确属有意修改，必须同步重算 SecurityConfig.INLINE_SCRIPT_SHA256");
            assertEquals(SecurityConfig.INLINE_SCRIPT_SHA256, sha256Base64(body),
                page + " 内联脚本哈希与 CSP 白名单不一致，CSP 会拦掉它导致页面退化为无脚本态");
        }
    }

    @Test
    @DisplayName("CSP 策略串含关键指令且内联脚本走哈希白名单（非 'unsafe-inline'）")
    void cspPolicyShape() {
        String csp = SecurityConfig.CONTENT_SECURITY_POLICY;
        assertTrue(csp.contains("default-src 'self'"), "缺 default-src 'self' 兜底");
        assertTrue(csp.contains("frame-ancestors 'none'"), "缺 frame-ancestors 'none'（防点击劫持）");
        assertTrue(csp.contains("object-src 'none'"), "缺 object-src 'none'");
        assertTrue(csp.contains("'" + SecurityConfig.INLINE_SCRIPT_SHA256 + "'"),
            "script-src 未把内联脚本哈希列入白名单");
        assertTrue(!csp.contains("'unsafe-inline'"), "不得用 'unsafe-inline' 放行脚本（会架空 CSP）");
        assertTrue(!csp.contains("'unsafe-eval'"), "不得用 'unsafe-eval'");
    }

    @Test
    @DisplayName("静态页响应带齐四个安全头")
    void staticPageCarriesSecurityHeaders() throws Exception {
        mockMvc.perform(get("/checkout/index.html"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andExpect(header().string("Content-Security-Policy",
                org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }

    @Test
    @DisplayName("API 响应同样带齐安全头（头由过滤器链统一注入，不挑路径）")
    void apiResponseCarriesSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
            .andExpect(header().string("Content-Security-Policy",
                org.hamcrest.Matchers.containsString("default-src 'self'")));
    }
}
