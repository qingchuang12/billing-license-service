package com.billing.license.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号页静态页（原生 ES5，无构建期校验）的<b>契约自证</b>（plan-7.0 账户基础功能）。
 *
 * <p>背景（2026-09-25 实测缺陷）：开启 MFA 的账号在 {@code /account/} 登录「点了没反应」——
 * {@code account.js} 的登录回调只认 {@code accessToken}，而开启二次因子的账号第一响应
 * {@code accessToken=null}、只带 {@code mfaTicket}，于是静默落到「未返回令牌」分支。
 * 静态页没有编译器，这类缺陷只能靠契约用例拦截：
 * <ol>
 *   <li>{@code account.js} 引用的每个 DOM id 都必须在 {@code index.html} 中存在；</li>
 *   <li>登录响应必须<b>先判 MFA 两阶段</b>再取令牌，且第二步调用的路径 / 请求体与后端契约一致；</li>
 *   <li>密码不出现在查询串。</li>
 * </ol>
 */
class AccountStaticPageContractTest {

    private static final Pattern ID_IN_HTML = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern DOLLAR_REF = Pattern.compile("\\$\\(\\s*'([^']+)'\\s*\\)");
    private static final Pattern DOLLAR_REF_DQ = Pattern.compile("\\$\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern BY_ID = Pattern.compile("getElementById\\(\\s*'([^']+)'\\s*\\)");

    private static String read(String classpath) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpath);
        assertTrue(resource.exists(), "静态资源缺失：" + classpath);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 去掉注释，避免注释里的示例代码造成假阳性 / 假阴性。 */
    private static String stripComments(String js) {
        String noBlock = js.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("//[^\\n]*", "");
    }

    private static Set<String> findAll(Pattern pattern, String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("account.js 引用的每一个 DOM id 都必须在 index.html 中存在")
    void everyDomIdReferencedByScriptExistsInHtml() throws IOException {
        String html = read("static/account/index.html");
        String js = stripComments(read("static/account/account.js"));

        Set<String> htmlIds = findAll(ID_IN_HTML, html);
        Set<String> refs = new LinkedHashSet<>();
        refs.addAll(findAll(DOLLAR_REF, js));
        refs.addAll(findAll(DOLLAR_REF_DQ, js));
        refs.addAll(findAll(BY_ID, js));

        assertFalse(refs.isEmpty(), "未解析到任何 id 引用，正则失效了");
        for (String id : refs) {
            assertTrue(htmlIds.contains(id),
                "account.js 引用了 index.html 中不存在的 id：" + id);
        }
    }

    /**
     * 两阶段登录契约：开启 MFA 的账号登录第一响应没有 accessToken——
     * 必须先判 {@code mfaRequired && mfaTicket} 走第二步，否则表现为「点了没反应」。
     */
    @Test
    @DisplayName("登录回调先判 MFA 两阶段分支，再取 accessToken")
    void loginMustHandleTwoPhaseMfaBeforeUsingAccessToken() throws IOException {
        String js = stripComments(read("static/account/account.js"));

        assertTrue(js.contains("data.mfaRequired && data.mfaTicket"),
            "登录回调必须先判 mfaRequired && mfaTicket（否则 MFA 账号表现为登录无反应）");
        assertTrue(js.contains("showMfaLoginForm"), "缺少第二因子表单切换入口");
        assertTrue(js.contains("/api/account/mfa/verify"), "缺少第二因子校验调用");
        assertTrue(js.contains("{ ticket: state.mfaTicket, code: code }"),
            "verify 请求体须为 { ticket, code }，与 MfaVerifyRequest 一致");
        assertTrue(js.contains("/api/account/mfa/challenge"), "缺少邮箱兜底发码调用");
        assertTrue(js.contains("{ ticket: state.mfaTicket }"),
            "challenge 请求体须为 { ticket }，与 MfaTicketRequest 一致");
    }

    /** 密码绝不进查询串（访问日志 / 浏览器历史会留存）。 */
    @Test
    @DisplayName("密码不出现在任何请求查询串中")
    void passwordMustNotAppearInQueryString() throws IOException {
        String js = stripComments(read("static/account/account.js"));
        assertFalse(js.contains("password="), "密码不得出现在查询串中");
    }
}
